package com.rchia.ecocapture.phase0.vlm.background

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import com.rchia.ecocapture.phase0.data.AiSuggestionPreferences
import com.rchia.ecocapture.phase0.data.VlmRunRepository
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.vlm.*
import java.io.File
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

open class AiPreparationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = supervisorScope {
        val database = openDatabase()
        try { perform(database) } finally { closeDatabase(database) }
    }

    protected open fun openDatabase() = EcologicalCaptureDatabase.getInstance(applicationContext)
    protected open fun closeDatabase(database: EcologicalCaptureDatabase) = Unit
    protected open fun createEngine(): VlmEngine = VlmEngineFactory.create(applicationContext, VlmRuntimeMode.QWEN_PARTICIPANT)
    protected open fun preparationAllowed(): Boolean {
        val preferences = AiSuggestionPreferences(applicationContext)
        return if (preferences.backgroundEnabled.value) AiPreparationNotifications.allowed(applicationContext)
        else "automatic" in tags && BackgroundAiPreparation.get(applicationContext).appForeground.value
    }
    protected open fun memoryAvailable() = VlmMemoryPressure.available(applicationContext)
    protected open suspend fun ready(clipId: String, database: EcologicalCaptureDatabase) {
        currentCoroutineContext().ensureActive()
        if (database.clipDao().getClip(clipId)?.let { it.approvalState != "DELETED" } == true && database.vlmRunDao().getRun(id.toString()) != null)
            AiPreparationNotifications.ready(applicationContext, clipId)
    }

    private suspend fun perform(database: EcologicalCaptureDatabase): Result = supervisorScope {
        val clipId = inputData.getString("clipId") ?: return@supervisorScope failure("Recording is unavailable.")
        val clip = database.clipDao().getClip(clipId)
        if (clip == null || clip.approvalState == "DELETED") return@supervisorScope failure("Recording is unavailable.")
        // Same work ID across system retries: a committed result is never generated or saved twice.
        if (database.vlmRunDao().getRun(id.toString()) != null) {
            ready(clipId, database)
            return@supervisorScope Result.success()
        }
        val automatic = "automatic" in tags
        val preferences = AiSuggestionPreferences(applicationContext)
        val coordinator = BackgroundAiPreparation.get(applicationContext)
        fun backgroundAllowed() = AiSuggestionPreferences(applicationContext).backgroundEnabled.value
        if (automatic && !preferences.enabled.value) return@supervisorScope failure("Automatic AI preparation is off.")
        suspend fun noLongerEligible() = automatic && (database.annotationDao().current(clipId) != null || database.vlmRunDao().history(clipId).isNotEmpty())
        if (noLongerEligible()) return@supervisorScope Result.success(workDataOf("skipped" to true))
        if (automatic && !backgroundAllowed() && !coordinator.appForeground.value) {
            setProgress(workDataOf("phase" to "WAITING_APP"))
            return@supervisorScope Result.retry()
        }
        if (!preparationAllowed()) {
            return@supervisorScope failure("Background AI preparation is off or its notifications are unavailable. You can still review and write a description.")
        }
        if (!memoryAvailable()) {
            setProgress(workDataOf("phase" to "WAITING_MEMORY"))
            return@supervisorScope Result.retry()
        }
        val memoryPressure = AtomicBoolean(false)
        val execution = async {
            VlmExecutionGate.execute(wait = false) {
                if (noLongerEligible()) return@execute Result.success(workDataOf("skipped" to true))
                val job = currentCoroutineContext()[Job]!!
                val engine = createEngine()
                coordinator.started(clipId, job, "automatic" in tags)
                var pressure: VlmMemoryPressure? = null
                try {
                    if (automatic && !backgroundAllowed() && !coordinator.appForeground.value) {
                        setProgress(workDataOf("phase" to "WAITING_APP"))
                        return@execute Result.retry()
                    }
                    if (!preparationAllowed()) return@execute failure("AI preparation is unavailable. You can still review without it.")
                    pressure = VlmMemoryPressure(applicationContext) { memoryPressure.set(true); job.cancel() }
                    setForeground(foreground("Preparing AI suggestion"))
                    setProgress(workDataOf("phase" to "PREPARING"))
                    withTimeout(45 * 60 * 1000L) {
                        when (val loaded = engine.load()) {
                            is VlmLoadResult.Failure -> return@withTimeout failure(loadError(loaded.reason))
                            VlmLoadResult.Ready -> Unit
                        }
                        setForeground(foreground("Generating AI suggestion"))
                        setProgress(workDataOf("phase" to "RUNNING"))
                        when (val generated = engine.generate(VlmRequest(clipId, File(clip.videoPath)))) {
                            is VlmResult.Failure -> failure("AI description could not be generated. You can try again or review without it.")
                            is VlmResult.Success -> {
                                currentCoroutineContext().ensureActive()
                                VlmRunRepository(database).save(generated, id.toString())
                                Result.success()
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    failure("AI preparation took too long and was stopped. You can try again or review without it.")
                } finally {
                    try { withContext(NonCancellable) { engine.unload() } }
                    finally { pressure?.close(); coordinator.finished(clipId, job) }
                }
            }
        }
        try {
            val result = execution.await()
            if (result == null) {
                setProgress(workDataOf("phase" to if (VlmExecutionGate.captureBusy) "WAITING_RECORDING" else "QUEUED"))
                Result.retry()
            } else {
                if (result is Result.Success && database.vlmRunDao().getRun(id.toString()) != null) ready(clipId, database)
                result
            }
        } catch (cancelled: CancellationException) {
            // Recording cancels just the engine child; scheduler/notification cancellation cancels this parent too.
            currentCoroutineContext().ensureActive()
            val waitingForApp = cancelled is AiPreparationPausedException ||
                (automatic && !backgroundAllowed() && !coordinator.appForeground.value)
            if (VlmExecutionGate.captureBusy || memoryPressure.get() || waitingForApp) {
                setProgress(workDataOf("phase" to when {
                    waitingForApp -> "WAITING_APP"
                    memoryPressure.get() -> "WAITING_MEMORY"
                    else -> "WAITING_RECORDING"
                }))
                Result.retry()
            } else throw cancelled
        } catch (_: OutOfMemoryError) {
            failure("There is not enough memory to prepare an AI suggestion. You can still review without it.")
        } catch (_: Exception) {
            failure("AI preparation could not finish. The recording has not been changed. You can try again.")
        }
    }

    private fun foreground(title: String) = ForegroundInfo(AiPreparationNotifications.ONGOING_ID,
        AiPreparationNotifications.notification(applicationContext, title, true, id),
        if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun failure(message: String) = Result.failure(workDataOf("error" to message))
    private fun loadError(reason: VlmFailure) = when (reason) {
        VlmFailure.ModelMissing -> "The AI model is not installed on this phone. You can still review and write a description."
        VlmFailure.HashMismatch, VlmFailure.ModelVerificationFailed -> "The AI model could not be verified. Ask the research team for help."
        VlmFailure.OutOfMemory -> "There is not enough memory to prepare an AI suggestion. You can still review without it."
        else -> "The AI model could not be prepared. You can still review without it."
    }
}
