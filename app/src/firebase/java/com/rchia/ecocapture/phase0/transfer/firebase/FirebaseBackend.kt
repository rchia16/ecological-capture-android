package com.rchia.ecocapture.phase0.transfer.firebase

import android.content.Context
import android.content.Intent
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.rchia.ecocapture.phase0.BuildConfig
import com.rchia.ecocapture.phase0.data.local.TransferJobEntity
import com.rchia.ecocapture.phase0.transfer.*
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CloudFailure(val retryable: Boolean) : Exception("CLOUD_REQUEST_FAILED")

class FirebaseBackend(private val context: Context) : LiveTransferBackend {
    private val preferences = context.getSharedPreferences("firebase_transfer", Context.MODE_PRIVATE)
    private val app = synchronized(FirebaseBackend::class.java) {
        FirebaseApp.getApps(context).firstOrNull { it.name == "ecocapture" } ?: FirebaseApp.initializeApp(context,
            FirebaseOptions.Builder().setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID).setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET).build(), "ecocapture")
    }
    val auth: FirebaseAuth = FirebaseAuth.getInstance(app)
    val storage: FirebaseStorage = FirebaseStorage.getInstance(app)
    override val enabled = MutableStateFlow(auth.currentUser != null && preferences.contains("profile"))
    init { auth.addAuthStateListener { enabled.value = it.currentUser != null && preferences.contains("profile") } }
    override fun permitsSimulation(clipId: String) = enabled.value
    override fun participantCode(): String? = cachedProfile()?.getString("participant_code")
    fun cachedProfile(): JSONObject? = preferences.getString("profile", null)?.let(::JSONObject)

    override val transport = object : TransferTransport {
        override val mode = "FIREBASE"
        override val activeState = "QUEUED"
        override suspend fun deliver(job: TransferJobEntity, bundle: File, progress: suspend (Int) -> Unit): String {
            check(enabled.value)
            progress(0)
            val request = OneTimeWorkRequestBuilder<FirebaseUploadWorker>()
                .setInputData(workDataOf("jobId" to job.jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context).enqueueUniqueWork("firebase-upload-${job.jobId}", ExistingWorkPolicy.KEEP, request)
                    .result.get()
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("firebase-receipts", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FirebaseReceiptWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            return activeState
        }
    }

    override fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("firebase-upload-$jobId")
    }
    override suspend fun recover() {
        if (!enabled.value) return
        val dao = com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase.getInstance(context).transferJobDao()
        val jobs = dao.observeAll().first().filter { it.transportMode == "FIREBASE" }
        for (job in jobs.filter { it.state in setOf("QUEUED", "ENCRYPTING", "UPLOADING") })
            transport.deliver(job, File(context.filesDir,"event-exports")) { }
        if (jobs.any { it.state in setOf("AWAITING_GB10", "NEEDS_ATTENTION") })
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("firebase-receipts", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FirebaseReceiptWorker>(15,TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    override fun openEnrolment(context: Context) {
        context.startActivity(Intent(context, FirebaseEnrolmentActivity::class.java))
    }

    suspend fun enrol(code: String) {
        check(auth.currentUser == null) { "ALREADY_ENROLLED" }
        val installation = preferences.getString("installation", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("installation", it).commit())
        }
        val response = call("/enrol", JSONObject().put("code", code).put("installation_id", installation), authenticated = false)
        val token = response.getString("custom_token")
        response.remove("custom_token")
        // Persist profile first: a crash after sign-in is recoverable without consuming another QR.
        check(preferences.edit().putString("profile", response.toString()).commit())
        auth.signInWithCustomToken(token).await()
        enabled.value = true
    }

    suspend fun profile(): JSONObject {
        val fresh = call("/profile")
        val previous = cachedProfile()
        check(previous == null || previous.getString("participant_code") == fresh.getString("participant_code"))
        check(preferences.edit().putString("profile", fresh.toString()).commit())
        return fresh
    }

    suspend fun call(route: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        require(route.matches(Regex("/[A-Za-z0-9_/-]+")))
        val endpoint = URL(BuildConfig.FIREBASE_API_ENDPOINT)
        require(endpoint.protocol == "https" && endpoint.query == null && endpoint.userInfo == null)
        val token = if (authenticated) (auth.currentUser ?: throw CloudFailure(false)).getIdToken(false).await().token else null
        val connection = URL(BuildConfig.FIREBASE_API_ENDPOINT.trimEnd('/') + route).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 30000; connection.readTimeout = 600000
        try {
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status != 200) throw CloudFailure(status >= 500 || status == 429)
            val bytes = connection.inputStream.use { it.readBytesBounded(1024 * 1024) }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        check(output.size() + count <= max)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
