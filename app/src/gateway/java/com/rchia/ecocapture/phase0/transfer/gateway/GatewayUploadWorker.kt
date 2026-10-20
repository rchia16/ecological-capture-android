package com.rchia.ecocapture.phase0.transfer.gateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.export.EventManifest
import com.rchia.ecocapture.phase0.transfer.TransferRuntime
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.ecocapture.crypto.ecocrypto.Ecocrypto
import org.json.JSONObject

class GatewayUploadWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("gateway-uploads","Secure recording uploads",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"gateway-uploads").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending approved recording").setContentText("Your recording remains on this phone.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).build()
        return ForegroundInfo(5104,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runtime=TransferRuntime.get(applicationContext)
        val backend=runtime.live as? GatewayBackend ?: return@withContext Result.failure()
        val dao=runtime.database.transferJobDao()
        val id=inputData.getString("jobId") ?: return@withContext Result.failure()
        var job=dao.get(id) ?: return@withContext Result.failure()
        if(job.transportMode!="GATEWAY"||job.state in setOf("CANCELLED","AWAITING_GB10","VERIFIED_GB10","NEEDS_ATTENTION"))return@withContext Result.success()
        suspend fun active(){
            currentCoroutineContext().ensureActive()
            check(backend.enabled.value&&dao.get(id)?.state!="CANCELLED")
            check(runtime.database.clipDao().getClip(job.clipId)?.approvalState=="APPROVED")
        }
        suspend fun state(next: String,percent: Int=0){
            active();val current=checkNotNull(dao.get(id))
            check(dao.transition(id,current.state,next,percent,System.currentTimeMillis(),null)==1)
        }
        try {
            active();setForeground(getForegroundInfo())
            val profile=backend.profile()
            val snapshot=checkNotNull(runtime.database.exportSnapshotDao().get(job.snapshotId))
            val bundle=File(applicationContext.filesDir,"event-exports/${job.snapshotId}/event_${job.clipId}_snapshot_${job.snapshotId}")
            val expected=JSONObject().put("video.mp4",snapshot.mp4Sha256).put("event.json",snapshot.eventJsonSha256).put("manifest.json",snapshot.manifestSha256)
            for(name in listOf("video.mp4","event.json","manifest.json")){
                active();check(EventManifest.digest(File(bundle,name)).sha256==expected.getString(name))
            }
            val event=JSONObject(File(bundle,"event.json").readText())
            check(event.getString("study_participant_code")==profile.getString("participant_code"))
            state("ENCRYPTING")
            val artifact=File(applicationContext.filesDir,"event-exports/${job.snapshotId}/approved-event.tar.age")
            val policyFile=File(artifact.parentFile,"encryption-policy.json")
            val policy=if(policyFile.isFile)JSONObject(policyFile.readText()) else {
                val frozen=JSONObject().put("recipients",profile.getJSONArray("recipients")).put("key_versions",profile.getJSONArray("key_versions"))
                val temporary=File(policyFile.path+".part")
                FileOutputStream(temporary).use { it.write(frozen.toString().toByteArray(Charsets.UTF_8));it.fd.sync() }
                check(temporary.renameTo(policyFile));frozen
            }
            if(!artifact.exists()){
                val partial=File(artifact.path+".part");check(!partial.exists()||partial.delete())
                val keys=policy.getJSONArray("recipients");check(keys.length()==2)
                Ecocrypto.encrypt(bundle.path,partial.path,"${keys.getString(0)}\n${keys.getString(1)}",expected.toString())
                active();check(partial.renameTo(artifact))
            }
            val digest=EventManifest.digest(artifact);check(digest.sizeBytes<=256L*1024*1024)
            if(job.ciphertextSha256!=null)check(job.ciphertextSha256==digest.sha256)
            check(dao.artifact(id,artifact.path,digest.sha256,policy.getJSONArray("key_versions").toString())==1)
            active()
            val registration=JSONObject().put("snapshot_id",job.snapshotId).put("event_id",job.clipId)
                .put("recorded_at_epoch_ms",event.getJSONObject("capture").getLong("created_at_epoch_ms"))
                .put("size_bytes",digest.sizeBytes).put("ciphertext_sha256",digest.sha256)
                .put("manifest_sha256",snapshot.manifestSha256).put("key_versions",policy.getJSONArray("key_versions"))
            val remote=backend.call("/packages",registration)
            state("UPLOADING")
            if(remote.getString("status")=="RECEIVING"){
                var offset=remote.getLong("accepted_bytes")
                val chunkSize=remote.getInt("max_chunk_bytes");check(chunkSize in 1..16*1024*1024)
                check(offset in 0..digest.sizeBytes)
                RandomAccessFile(artifact,"r").use { source ->
                    while(offset<digest.sizeBytes){
                        active();source.seek(offset)
                        val bytes=ByteArray(minOf(chunkSize.toLong(),digest.sizeBytes-offset).toInt());source.readFully(bytes)
                        val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                        val sentAt=offset
                        val reply=try { backend.call("/packages/${job.snapshotId}/chunks/$offset",method="PUT",binary=bytes,chunkSha=sha) }
                        catch(error: GatewayFailure){
                            // Resolve a conflict from authoritative status; never loop blindly on 409.
                            if(error.status!=409)throw error
                            val current=backend.call("/packages/${job.snapshotId}")
                            val accepted=current.getLong("accepted_bytes")
                            check(accepted>sentAt&&accepted<=digest.sizeBytes&&current.getString("status")=="RECEIVING")
                            JSONObject().put("accepted_bytes",accepted)
                        }
                        offset=reply.getLong("accepted_bytes");check(offset>sentAt&&offset<=digest.sizeBytes)
                        state("UPLOADING",(100*offset/digest.sizeBytes).toInt())
                    }
                }
            }
            active()
            saveReceipt(dao,id,backend.call("/packages/${job.snapshotId}/complete",method="POST"))
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("gateway-check-${job.jobId}",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GatewayReceiptWorker>().setInitialDelay(10,TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            Result.success()
        }catch(error: CancellationException){throw error}
        catch(error: Exception){
            job=dao.get(id) ?: return@withContext Result.failure()
            if(job.state=="CANCELLED")return@withContext Result.success()
            val retry=(error is java.io.IOException||error is GatewayFailure&&error.retryable)&&runAttemptCount<8
            dao.transition(id,job.state,if(retry)"QUEUED" else "FAILED_RETRYABLE",0,System.currentTimeMillis(),"TRANSFER_FAILED")
            if(retry)Result.retry() else Result.failure()
        }
    }
}

private suspend fun saveReceipt(dao: TransferJobDao,id: String,receipt: JSONObject){
    val status=receipt.getString("status");check(status in setOf("AWAITING_GB10","VERIFIED_GB10","NEEDS_ATTENTION"))
    val job=checkNotNull(dao.get(id))
    check(receipt.getString("snapshot_id")==job.snapshotId&&receipt.getString("ciphertext_sha256")==job.ciphertextSha256)
    dao.receipt(id,status,receipt.toString(),System.currentTimeMillis())
}

class GatewayReceiptWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters){
    override suspend fun doWork(): Result = withContext(Dispatchers.IO){
        val runtime=TransferRuntime.get(applicationContext);val backend=runtime.live as? GatewayBackend ?: return@withContext Result.failure()
        try {
            val dao=runtime.database.transferJobDao();var pending=false
            for(job in dao.observeAll().first().filter { it.transportMode=="GATEWAY"&&it.state in setOf("AWAITING_GB10","NEEDS_ATTENTION") }){
                val receipt=backend.call("/packages/${job.snapshotId}");saveReceipt(dao,job.jobId,receipt)
                if(receipt.getString("status")=="AWAITING_GB10")pending=true
            }
            if(pending&&runAttemptCount<8)Result.retry() else Result.success()
        }catch(error: CancellationException){throw error}catch(_: Exception){Result.retry()}
    }
}
