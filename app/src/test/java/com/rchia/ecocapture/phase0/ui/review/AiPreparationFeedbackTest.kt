package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.vlm.background.BackgroundAiState
import org.junit.Assert.*
import org.junit.Test

class AiPreparationFeedbackTest {
    private val failedAutomatic = BackgroundAiState(VlmReviewPhase.ERROR, true, "Historical automatic failure")
    private val failedManual = BackgroundAiState(VlmReviewPhase.ERROR, false, "Historical background failure")

    @Test fun disabledAutomaticFailureIsNotReplayedOnReopen() {
        val original = VlmReviewState(isLoading = false)
        assertEquals(original, original.withPreparationFeedback(failedAutomatic, false, true, false))
    }
    @Test fun turningAutomaticOffClearsOnlyItsMatchingVisibleFailure() {
        val shown = VlmReviewState(phase = VlmReviewPhase.ERROR, error = failedAutomatic.error,
            isLoading = false, isAutomaticGeneration = true)
        val cleared = shown.withPreparationFeedback(failedAutomatic, false, true, false)
        assertNull(cleared.error)
        assertEquals(VlmReviewPhase.IDLE, cleared.phase)
        assertFalse(cleared.isAutomaticGeneration)
    }
    @Test fun enabledAutomaticFailureRemainsVisible() {
        val shown = VlmReviewState().withPreparationFeedback(failedAutomatic, true, false, false)
        assertEquals(failedAutomatic.error, shown.error)
        assertEquals(VlmReviewPhase.ERROR, shown.phase)
    }
    @Test fun disabledBackgroundFailureIsNotReplayed() {
        val original = VlmReviewState()
        assertEquals(original, original.withPreparationFeedback(failedManual, true, false, false))
    }
    @Test fun turningBackgroundOffClearsItsMatchingFailure() {
        val shown = VlmReviewState(phase = VlmReviewPhase.ERROR, error = failedManual.error, isBackgroundGeneration = true)
        assertNull(shown.withPreparationFeedback(failedManual, true, false, false).error)
    }
    @Test fun foregroundManualErrorsRemainWhenAutomaticIsOff() {
        val original = VlmReviewState(phase = VlmReviewPhase.ERROR, error = "Explicit foreground generation failed")
        assertEquals(original, original.withPreparationFeedback(failedAutomatic, false, false, false))
        assertEquals(original, original.withPreparationFeedback(failedManual, false, false, false))
    }
    @Test fun databaseOrReviewErrorsAreNotClearedWithHistoricalFailure() {
        val original = VlmReviewState(phase = VlmReviewPhase.ERROR, error = "AI description could not be loaded.", isAutomaticGeneration = true)
        assertEquals(original, original.withPreparationFeedback(failedAutomatic, false, true, false))
    }
    @Test fun disablingFailureFeedbackKeepsSavedOutputAndNullExposure() {
        val run = com.rchia.ecocapture.phase0.data.local.VlmRunEntity("run", "clip", "fixture", "fixture", "lm", "projector",
            "local", "commit", "prompt", 1000, null, 20, "{}", "{}", "Exact original output", "Exact description", "NOT_PRESENTED")
        val state = VlmReviewState(phase = VlmReviewPhase.ERROR, run = run, error = failedAutomatic.error, isAutomaticGeneration = true)
        val cleared = state.withPreparationFeedback(failedAutomatic, false, false, false)
        assertSame(run, cleared.run)
        assertNull(cleared.run!!.firstPresentedAtEpochMs)
        assertEquals("Exact original output", cleared.run!!.rawOutput)
        assertEquals(VlmReviewPhase.SUCCESS, cleared.phase)
        assertNull(cleared.error)
    }
}
