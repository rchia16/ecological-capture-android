package com.rchia.ecocapture.phase0.transfer.gateway

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import com.rchia.ecocapture.phase0.BuildConfig
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.data.local.TransferJobEntity
import com.rchia.ecocapture.phase0.transfer.*
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class GatewayFailure(val status: Int, val accepted: Long? = null) : Exception("GATEWAY_REQUEST_FAILED") {
    val retryable get() = status >= 500 || status == 429
}

/** App-private preferences contain only a Keystore-encrypted bearer credential. */
private class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("gateway_transfer",Context.MODE_PRIVATE)
    private fun key(): SecretKey = synchronized(CredentialVault::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ecocapture-gateway-v1",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("ecocapture-gateway-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun read(): String? = prefs.getString("credential",null)?.let {
        val bytes = Base64.decode(it,Base64.NO_WRAP)
        require(bytes.size > 28)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
        }
    }
    fun create(): String = read() ?: Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also { token ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        check(prefs.edit().putString("credential",encoded).commit())
    }
}

class GatewayBackend(private val context: Context) : LiveTransferBackend {
    private val prefs = context.getSharedPreferences("gateway_transfer",Context.MODE_PRIVATE)
    private val vault = CredentialVault(context)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override val enabled = MutableStateFlow(prefs.contains("profile") && runCatching { vault.read()!=null }.getOrDefault(false))
    override fun permitsSimulation(clipId: String) = enabled.value
    override fun participantCode(): String? = prefs.getString("profile",null)?.let { JSONObject(it).getString("participant_code") }
    override val transport = object : TransferTransport {
        override val mode = "GATEWAY"
        override val activeState = "QUEUED"
        override suspend fun deliver(job: TransferJobEntity,bundle: File,progress: suspend (Int)->Unit): String {
            check(enabled.value);progress(0)
            val request = OneTimeWorkRequestBuilder<GatewayUploadWorker>().setInputData(workDataOf("jobId" to job.jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            withContext(Dispatchers.IO) { WorkManager.getInstance(context).enqueueUniqueWork("gateway-upload-${job.jobId}",ExistingWorkPolicy.KEEP,request).result.get() }
            receipts()
            return activeState
        }
    }
    private fun receipts() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("gateway-receipts",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GatewayReceiptWorker>(15,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    override suspend fun recover() {
        if(!enabled.value)return
        val jobs = EcologicalCaptureDatabase.getInstance(context).transferJobDao().observeAll().first().filter { it.transportMode=="GATEWAY" }
        for(job in jobs.filter { it.state in setOf("QUEUED","ENCRYPTING","UPLOADING") })transport.deliver(job,File(context.filesDir,"event-exports")) { }
        if(jobs.any { it.state in setOf("AWAITING_GB10","NEEDS_ATTENTION") })receipts()
    }
    override fun cancel(jobId: String) {
        if(!cancelled.add(jobId))return
        WorkManager.getInstance(context).cancelUniqueWork("gateway-upload-$jobId")
        scope.launch {
            try {
                val job = EcologicalCaptureDatabase.getInstance(context).transferJobDao().get(jobId)
                if(job?.transportMode=="GATEWAY")call("/packages/${job.snapshotId}/cancel",method="POST")
            }catch(e: CancellationException){throw e}catch(_: Exception){ /* Local cancellation never promises remote withdrawal. */ }
        }
    }
    override fun openEnrolment(context: Context) { context.startActivity(Intent(context,GatewayEnrolmentActivity::class.java)) }
    suspend fun enrol(code: String) {
        check(!enabled.value)
        val installation = prefs.getString("installation",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("installation",it).commit()) }
        val token = withContext(Dispatchers.IO) { vault.create() }
        val profile = call("/enrol",JSONObject().put("code",code).put("installation_id",installation).put("token",token),authenticated=false)
        check(prefs.edit().putString("profile",profile.toString()).commit());enabled.value=true
    }
    suspend fun profile(): JSONObject {
        val fresh = call("/profile")
        check(participantCode()==fresh.getString("participant_code"))
        check(prefs.edit().putString("profile",fresh.toString()).commit())
        return fresh
    }
    suspend fun call(route: String,body: JSONObject?=null,method: String=if(body!=null)"POST" else "GET",
                     authenticated: Boolean=true,binary: ByteArray?=null,chunkSha: String?=null): JSONObject = withContext(Dispatchers.IO) {
        require(route.matches(Regex("/[A-Za-z0-9_/-]+")))
        val endpoint=URL(BuildConfig.GATEWAY_API_ENDPOINT)
        require(endpoint.protocol=="https"&&endpoint.query==null&&endpoint.userInfo==null)
        val connection=URL(BuildConfig.GATEWAY_API_ENDPOINT+route).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=30000;connection.readTimeout=120000;connection.requestMethod=method
        try {
            if(authenticated)connection.setRequestProperty("Authorization","Bearer ${vault.read() ?: throw GatewayFailure(401)}")
            if(chunkSha!=null)connection.setRequestProperty("X-Chunk-SHA256",chunkSha)
            val payload=binary ?: body?.toString()?.toByteArray(Charsets.UTF_8)
            if(method=="POST"||method=="PUT") {
                connection.doOutput=true
                connection.setRequestProperty("Content-Type",if(binary!=null)"application/octet-stream" else "application/json")
                connection.setFixedLengthStreamingMode(payload?.size ?: 0)
                connection.outputStream.use { if(payload!=null)it.write(payload) }
            }
            val status=connection.responseCode
            if(status!=200) {
                val error=runCatching { connection.errorStream?.use { JSONObject(it.bounded(32768).toString(Charsets.UTF_8)) } }.getOrNull()
                val accepted=error?.optJSONObject("error")?.let { if(it.has("accepted_bytes"))it.getLong("accepted_bytes") else null }
                throw GatewayFailure(status,accepted)
            }
            connection.inputStream.use { JSONObject(it.bounded(1024*1024).toString(Charsets.UTF_8)) }
        }finally{connection.disconnect()}
    }
}

private fun java.io.InputStream.bounded(max: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val count=read(buffer);if(count<0)break;check(output.size()+count<=max);output.write(buffer,0,count)}
    return output.toByteArray()
}
