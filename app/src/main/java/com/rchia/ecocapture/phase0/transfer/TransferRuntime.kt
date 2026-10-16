package com.rchia.ecocapture.phase0.transfer

import android.content.Context
import com.rchia.ecocapture.phase0.BuildConfig
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.vlm.VlmExecutionGate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Application-owned simulation attempts survive screen navigation, but never auto-resume after death. */
class TransferRuntime private constructor(context: Context) {
    val database = EcologicalCaptureDatabase.getInstance(context)
    val provisioning = TransferProvisioning(context)
    private val attempts = ConcurrentHashMap<String, Job>()
    val repository = TransferRepository(database, File(context.filesDir, "event-exports"),
        File(context.filesDir, "recordings"), provisioning, FakeTransferTransport(fail = provisioning::consumeFailure), BuildConfig.VERSION_NAME,
        onAttempt = { id ->
            currentCoroutineContext()[Job]?.let { job ->
                attempts[id] = job
                job.invokeOnCompletion { attempts.remove(id, job) }
            }
        })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = scope.async { repository.recoverInterrupted() }
    init {
        scope.launch {
            initialized.await()
            database.transferJobDao().observeAll().collect { jobs ->
                jobs.filter { it.state == "CANCELLED" }.forEach { attempts[it.jobId]?.cancel() }
            }
        }
    }

    fun send(clipId: String, onError: (String) -> Unit) = scope.launch {
        try {
            initialized.await()
            val attempted = VlmExecutionGate.execute(wait = false) { repository.send(clipId); true }
            if (attempted == null) onError("Wait for recording or AI preparation to finish, then try again.")
        } catch (_: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                if (database.transferJobDao().latest(clipId)?.state != "CANCELLED")
                    onError("Preparation was interrupted. Your recording remains on this phone; retry when ready.")
            }
        } catch (_: Exception) { onError("Could not prepare this recording. Check sending is enabled and the recording is available.") }
    }
    fun retry(jobId: String, onError: (String) -> Unit) = scope.launch {
        try {
            initialized.await()
            if (VlmExecutionGate.execute(wait = false) { repository.retry(jobId); true } == null)
                onError("Wait for recording or AI preparation to finish, then retry.")
        } catch (_: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                if (database.transferJobDao().get(jobId)?.state != "CANCELLED")
                    onError("Retry was interrupted. Your recording remains on this phone.")
            }
        } catch (_: Exception) { onError("Retry could not start. If this recording changed, choose Send updated recording.") }
    }
    fun cancel(jobId: String, onError: (String) -> Unit) = scope.launch {
        try { initialized.await(); repository.cancel(jobId) }
        catch (_: Exception) { onError("Cancellation could not be saved. Try again.") }
    }

    companion object {
        @Volatile private var instance: TransferRuntime? = null
        fun get(context: Context): TransferRuntime = instance ?: synchronized(this) {
            instance ?: TransferRuntime(context.applicationContext).also { instance = it }
        }
    }
}
