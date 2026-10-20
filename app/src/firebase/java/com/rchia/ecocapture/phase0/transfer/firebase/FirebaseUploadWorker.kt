package com.rchia.ecocapture.phase0.transfer.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.export.EventManifest
import com.rchia.ecocapture.phase0.transfer.TransferRuntime
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.ecocapture.crypto.ecocrypto.Ecocrypto
import org.json.JSONArray
import org.json.JSONObject

class FirebaseUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("secure-uploads", "Secure recording uploads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "secure-uploads")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Sending approved recording")
            .setContentText("Your recording remains on this phone.").setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return ForegroundInfo(5103, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runtime = TransferRuntime.get(applicationContext)
        val backend = runtime.live as FirebaseBackend
        val dao = runtime.database.transferJobDao()
        val id = inputData.getString("jobId") ?: return@withContext Result.failure()
        var job = dao.get(id) ?: return@withContext Result.failure()
        if (job.transportMode != "FIREBASE" || job.state in setOf("CANCELLED", "AWAITING_GB10", "VERIFIED_GB10", "NEEDS_ATTENTION"))
            return@withContext Result.success()
        suspend fun active() {
            currentCoroutineContext().ensureActive()
            check(dao.get(id)?.state != "CANCELLED" && backend.enabled.value)
            check(runtime.database.clipDao().getClip(job.clipId)?.approvalState == "APPROVED")
        }
        suspend fun state(next: String, percent: Int = 0) {
            active()
            val old = checkNotNull(dao.get(id))
            check(dao.transition(id, old.state, next, percent, System.currentTimeMillis(), null) == 1)
        }
        try {
            active(); setForeground(getForegroundInfo())
            val profile = backend.profile()
            val snapshot = checkNotNull(runtime.database.exportSnapshotDao().get(job.snapshotId))
            val bundle = File(applicationContext.filesDir, "event-exports/${job.snapshotId}/event_${job.clipId}_snapshot_${job.snapshotId}")
            val event = JSONObject(File(bundle,"event.json").readText())
            check(event.getString("study_participant_code") == profile.getString("participant_code"))
            val expected = JSONObject().put("video.mp4", snapshot.mp4Sha256)
                .put("event.json", snapshot.eventJsonSha256).put("manifest.json", snapshot.manifestSha256)
            for (name in listOf("video.mp4", "event.json", "manifest.json"))
                check(EventManifest.digest(File(bundle,name)).sha256 == expected.getString(name))
            state("ENCRYPTING")
            val artifact = File(applicationContext.filesDir,"event-exports/${job.snapshotId}/approved-event.tar.age")
            val policyFile = File(artifact.parentFile,"encryption-policy.json")
            val policy = if (policyFile.isFile) JSONObject(policyFile.readText()) else {
                val frozen = JSONObject().put("recipients", profile.getJSONArray("recipients"))
                    .put("key_versions", profile.getJSONArray("key_versions"))
                val temp = File(policyFile.path + ".part")
                FileOutputStream(temp).use { it.write(frozen.toString().toByteArray()); it.fd.sync() }
                check(temp.renameTo(policyFile)); frozen
            }
            if (!artifact.exists()) {
                val partial = File(artifact.path + ".part"); partial.delete()
                val keys = policy.getJSONArray("recipients")
                check(keys.length() == 2)
                Ecocrypto.encrypt(bundle.path, partial.path, "${keys.getString(0)}\n${keys.getString(1)}", expected.toString())
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(artifact))
            }
            val digest = EventManifest.digest(artifact)
            check(digest.sizeBytes <= 256L*1024*1024)
            if (job.ciphertextSha256 != null) check(job.ciphertextSha256 == digest.sha256)
            check(dao.artifact(id, artifact.path, digest.sha256, policy.getJSONArray("key_versions").toString()) == 1)
            active()
            val registration = JSONObject().put("snapshot_id",job.snapshotId).put("event_id",job.clipId)
                .put("recorded_at_epoch_ms",event.getJSONObject("capture").getLong("created_at_epoch_ms"))
                .put("size_bytes",digest.sizeBytes).put("ciphertext_sha256",digest.sha256)
                .put("manifest_sha256",snapshot.manifestSha256).put("key_versions",policy.getJSONArray("key_versions"))
            val registered = backend.call("/register",registration)
            state("UPLOADING")
            if (registered.getString("status") == "UPLOADING") {
                val reference = backend.storage.reference.child(registered.getString("object_path"))
                val exists = try { reference.metadata.await(); true } catch (e: StorageException) {
                    if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw e
                }
                if (!exists) {
                    val task = reference.putFile(android.net.Uri.fromFile(artifact),
                        StorageMetadata.Builder().setContentType("application/octet-stream").build())
                    val watcher = CoroutineScope(currentCoroutineContext()).launch {
                        var last = -1
                        while (isActive) {
                            active()
                            val snap = task.snapshot
                            val percent = if (snap.totalByteCount > 0) (100*snap.bytesTransferred/snap.totalByteCount).toInt() else 0
                            if (last != percent) { state("UPLOADING",percent); last = percent }
                            delay(500)
                        }
                    }
                    try { task.await() } finally { watcher.cancelAndJoin(); if (!task.isComplete) task.cancel() }
                }
            }
            active()
            val receipt = backend.call("/complete",JSONObject().put("snapshot_id",job.snapshotId))
            saveReceipt(dao,id,receipt)
            Result.success()
        } catch (e: CancellationException) {
            throw e // WorkManager retains the durable request on system interruption.
        } catch (e: Exception) {
            job = dao.get(id) ?: return@withContext Result.failure()
            if (job.state == "CANCELLED") return@withContext Result.success()
            val retry = (e is java.io.IOException || e is StorageException || e is CloudFailure && e.retryable) && runAttemptCount < 8
            dao.transition(id,job.state,if (retry) "QUEUED" else "FAILED_RETRYABLE",0,System.currentTimeMillis(),"TRANSFER_FAILED")
            if (retry) Result.retry() else Result.failure()
        }
    }
}

internal suspend fun saveReceipt(dao: TransferJobDao, id: String, receipt: JSONObject) {
    val status = receipt.getString("status")
    check(status in setOf("AWAITING_GB10", "VERIFIED_GB10", "NEEDS_ATTENTION"))
    val job = checkNotNull(dao.get(id))
    check(receipt.getString("snapshot_id") == job.snapshotId && receipt.getString("ciphertext_sha256") == job.ciphertextSha256)
    dao.receipt(id,status,receipt.toString(),System.currentTimeMillis())
}

class FirebaseReceiptWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runtime = TransferRuntime.get(applicationContext)
        val backend = runtime.live as FirebaseBackend
        try {
            val dao = runtime.database.transferJobDao()
            for (job in dao.observeAll().first().filter { it.transportMode == "FIREBASE" && it.state in setOf("AWAITING_GB10", "NEEDS_ATTENTION") })
                saveReceipt(dao,job.jobId,backend.call("/status/${job.snapshotId}"))
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
