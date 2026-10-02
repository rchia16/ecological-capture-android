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

class AiPreparationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = supervisorScope {
        val clipId = inputData.getString("clipId") ?: return@supervisorScope failure("Recording is unavailable.")
        val database = EcologicalCaptureDatabase.getInstance(applicationContext)
        // Same work ID across system retries: a committed result is never generated or saved twice.
        if (database.vlmRunDao().getRun(id.toString()) != null) return@supervisorScope Result.success()
        if (!AiSuggestionPreferences(applicationContext).backgroundEnabled.value || !AiPreparationNotifications.allowed(applicationContext)) {
            return@supervisorScope failure("Background AI preparation is off or its notifications are unavailable. You can still review and write a description.")
        }
        val clip = database.clipDao().getClip(clipId)
        if (clip == null || clip.approvalState == "DELETED") return@supervisorScope failure("Recording is unavailable.")
        val coordinator = BackgroundAiPreparation.get(applicationContext)
        val execution = async {
            VlmExecutionGate.execute(wait = false) {
                val job = currentCoroutineContext()[Job]!!
                coordinator.started(clipId, job, "automatic" in tags)
                val engine = VlmEngineFactory.create(applicationContext, VlmRuntimeMode.QWEN_PARTICIPANT)
                try {
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
                    finally { coordinator.finished(clipId, job) }
                }
            }
        }
        try {
            val result = execution.await()
            if (result == null) {
                setProgress(workDataOf("phase" to if (VlmExecutionGate.captureBusy) "WAITING_RECORDING" else "QUEUED"))
                Result.retry()
            } else {
                if (result is Result.Success) AiPreparationNotifications.ready(applicationContext, clipId)
                result
            }
        } catch (cancelled: CancellationException) {
            // Recording cancels just the engine child; scheduler/notification cancellation cancels this parent too.
            currentCoroutineContext().ensureActive()
            if (VlmExecutionGate.captureBusy) {
                setProgress(workDataOf("phase" to "WAITING_RECORDING"))
                Result.retry()
            } else throw cancelled
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
