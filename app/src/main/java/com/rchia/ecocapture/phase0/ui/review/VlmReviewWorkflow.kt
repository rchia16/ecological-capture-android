package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.data.VlmRunRepository
import com.rchia.ecocapture.phase0.data.local.VlmRunEntity
import com.rchia.ecocapture.phase0.domain.VlmDisposition
import com.rchia.ecocapture.phase0.vlm.*
import com.rchia.ecocapture.phase0.vlm.background.BackgroundAiPreparation
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VlmReviewPhase { IDLE, QUEUED, WAITING_CHARGE, WAITING_RECORDING, PREPARING, RUNNING, SUCCESS, ERROR, CANCELLED }

data class VlmReviewState(
    val phase: VlmReviewPhase = VlmReviewPhase.IDLE,
    val run: VlmRunEntity? = null,
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val error: String? = null,
    val isAutomaticGeneration: Boolean = false,
    val isBackgroundGeneration: Boolean = false,
) {
    val isGenerating get() = phase in setOf(VlmReviewPhase.QUEUED, VlmReviewPhase.WAITING_CHARGE,
        VlmReviewPhase.WAITING_RECORDING, VlmReviewPhase.PREPARING, VlmReviewPhase.RUNNING)
    val isSuggestionResolved get() = run?.disposition in setOf(
        VlmDisposition.AMENDED.name, VlmDisposition.IGNORED.name,
    )
}

/** Preparation may be explicitly requested or enabled by preference; viewing remains explicit. */
class VlmReviewWorkflow(
    private val repository: VlmRunRepository,
    private val engine: VlmEngine,
    private val editor: DescriptionEditor,
    private val scope: CoroutineScope,
    private val background: BackgroundAiPreparation? = null,
    private val allowBackground: () -> Boolean = { false },
    private val chargingOnly: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(VlmReviewState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var generation: Job? = null
    private var presentation: Job? = null
    private var backgroundObservation: Job? = null
    private var selectedClipId: String? = null
    private var session = 0L

    fun selectClip(clipId: String) {
        cancelForeground()
        backgroundObservation?.cancel()
        observation?.cancel()
        presentation?.cancel()
        presentation = null
        selectedClipId = clipId
        val selectedSession = ++session
        mutableState.value = VlmReviewState()
        backgroundObservation = background?.let { controller -> scope.launch {
            controller.observe(clipId).collect { progress ->
                if (progress != null && (allowBackground() || state.value.isBackgroundGeneration) &&
                    (state.value.isBackgroundGeneration || generation?.isActive != true)) update(selectedSession) {
                    it.copy(phase = progress.phase, error = progress.error,
                        isAutomaticGeneration = progress.automatic,
                        isBackgroundGeneration = true)
                }
            }
        } }
        observation = scope.launch {
            try {
                repository.observeLatest(clipId).collect { run ->
                    if (session == selectedSession) mutableState.update {
                        it.copy(run = run, isLoading = false,
                            phase = if (it.phase == VlmReviewPhase.IDLE && run != null) VlmReviewPhase.SUCCESS else it.phase)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update(selectedSession) { it.copy(isLoading = false, phase = VlmReviewPhase.ERROR,
                    error = "AI description could not be loaded. Reopen this recording to try again.") }
            }
        }
    }

    suspend fun prepareAutomatically(videoFile: File, claimAttempt: suspend () -> Boolean) {
        val selectedSession = session
        fun eligible() = session == selectedSession && state.value.phase == VlmReviewPhase.IDLE &&
            !state.value.isLoading && !state.value.isUpdating && state.value.run == null && generation?.isActive != true
        if (eligible() && claimAttempt() && eligible()) generate(videoFile, automatic = true)
    }

    fun generate(videoFile: File, automatic: Boolean = false) {
        val clipId = selectedClipId ?: return
        if (generation?.isActive == true || state.value.isGenerating || state.value.isLoading || state.value.isUpdating ||
            (state.value.run != null && state.value.run?.disposition != VlmDisposition.IGNORED.name)) return
        val selectedSession = session
        if (background != null && allowBackground()) {
            update(selectedSession) { it.copy(phase = VlmReviewPhase.QUEUED, error = null,
                isAutomaticGeneration = automatic, isBackgroundGeneration = true) }
            generation = scope.launch {
                try { background.enqueue(clipId, automatic, chargingOnly()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { update(selectedSession) { it.copy(phase = VlmReviewPhase.ERROR,
                    error = "AI preparation could not be queued. Check notifications and try again. You can still review without it.") } }
            }
            return
        }
        update(selectedSession) { it.copy(phase = VlmReviewPhase.PREPARING, error = null,
            isAutomaticGeneration = automatic, isBackgroundGeneration = false) }
        generation = scope.launch { VlmExecutionGate.execute {
            var finalPhase = VlmReviewPhase.ERROR
            var message: String? = null
            try {
                when (val loaded = engine.load()) {
                    is VlmLoadResult.Failure -> message = preparationError(loaded.reason)
                    VlmLoadResult.Ready -> {
                        update(selectedSession) { it.copy(phase = VlmReviewPhase.RUNNING) }
                        when (val result = engine.generate(VlmRequest(clipId, videoFile))) {
                            is VlmResult.Failure -> message = "AI description could not be generated. The recording has not been changed. Please try again."
                            is VlmResult.Success -> {
                                ensureActive()
                                val run = repository.save(result)
                                update(selectedSession) { it.copy(run = run) }
                                finalPhase = VlmReviewPhase.SUCCESS
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                finalPhase = VlmReviewPhase.CANCELLED
                throw cancelled
            } catch (_: Exception) {
                message = "AI description could not be generated or saved. The recording has not been changed. Please try again."
            } finally {
                // Cancellation must still release the engine before a new run or Delete.
                withContext(NonCancellable) {
                    try { engine.unload() }
                    catch (_: Exception) {
                        finalPhase = VlmReviewPhase.ERROR
                        message = "AI description could not finish safely. You can still review this recording."
                    }
                }
                update(selectedSession) { it.copy(phase = finalPhase, error = message, isAutomaticGeneration = false) }
            }
        } ?: update(selectedSession) { it.copy(phase = VlmReviewPhase.CANCELLED, isAutomaticGeneration = false) } }
        val started = generation!!
        started.invokeOnCompletion { cause ->
            // A queued run may be cancelled before acquiring the lifecycle mutex.
            if (cause is CancellationException && generation === started) {
                update(selectedSession) {
                    if (it.isGenerating) it.copy(phase = VlmReviewPhase.CANCELLED,
                        isAutomaticGeneration = false) else it
                }
            }
        }
    }

    private fun preparationError(reason: VlmFailure): String = when (reason) {
        VlmFailure.ModelMissing -> "AI suggestions are unavailable because the model is not installed on this phone. You can still write a description and review the recording."
        VlmFailure.HashMismatch, VlmFailure.ModelVerificationFailed -> "The AI model could not be verified. Ask the research team for help. You can still write a description and review the recording."
        VlmFailure.OutOfMemory -> "There is not enough memory to prepare an AI suggestion. You can still write a description and review the recording."
        else -> "AI description could not be prepared. You can still write a description and review this recording."
    }

    fun cancel() { generation?.cancel(); selectedClipId?.let { background?.cancel(it) } }
    fun cancelForeground() { if (!state.value.isBackgroundGeneration) generation?.cancel() }
    suspend fun cancelAndJoin() {
        generation?.cancelAndJoin()
        selectedClipId?.let { background?.cancelAndJoin(it) }
    }

    /** Called only by the visible text UI, never by generation completion or history loading. */
    fun onOutputVisible(runId: String) {
        val run = state.value.run ?: return
        if (run.vlmRunId != runId || run.firstPresentedAtEpochMs != null || presentation?.isActive == true) return
        val selectedSession = session
        val time = now()
        presentation = scope.launch {
            try { repository.markPresented(runId, time) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                update(selectedSession) { it.copy(error = "AI exposure could not be saved. Reopen this recording to try again.") }
            }
        }
    }

    fun useAsStartingPoint() = updateDisposition(use = true)
    fun ignore() = updateDisposition(use = false)

    private fun updateDisposition(use: Boolean) {
        val run = state.value.run ?: return
        if (state.value.isUpdating || state.value.isGenerating || state.value.isSuggestionResolved ||
            run.firstPresentedAtEpochMs == null) return
        val selectedSession = session
        update(selectedSession) { it.copy(isUpdating = true, error = null) }
        scope.launch {
            try {
                if (use) {
                    repository.useAsStartingPoint(run.vlmRunId)
                    if (session == selectedSession && !editor.startFromRun(run)) {
                        update(selectedSession) { it.copy(error = "The suggestion could not be copied into your draft. Please try again.") }
                    }
                } else repository.ignore(run.vlmRunId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                update(selectedSession) { it.copy(error = "Your AI review choice could not be saved. Please try again.") }
            } finally {
                update(selectedSession) { it.copy(isUpdating = false) }
            }
        }
    }

    private fun update(selectedSession: Long, transform: (VlmReviewState) -> VlmReviewState) {
        if (session == selectedSession) mutableState.update(transform)
    }
}
