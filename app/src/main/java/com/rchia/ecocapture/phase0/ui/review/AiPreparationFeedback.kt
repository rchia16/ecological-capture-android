package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.vlm.background.BackgroundAiState

/** Historical scheduler failures must not be replayed for preparation the user switched off. */
internal fun VlmReviewState.withPreparationFeedback(progress: BackgroundAiState,
    automaticEnabled: Boolean, backgroundEnabled: Boolean, foregroundGenerating: Boolean): VlmReviewState {
    val disabledFailure = progress.phase == VlmReviewPhase.ERROR &&
        if (progress.automatic) !automaticEnabled else !backgroundEnabled
    if (disabledFailure) {
        val sameOrigin = if (progress.automatic) isAutomaticGeneration else isBackgroundGeneration
        return if (sameOrigin && phase == VlmReviewPhase.ERROR && error == progress.error)
            copy(phase = if (run == null) VlmReviewPhase.IDLE else VlmReviewPhase.SUCCESS, error = null,
                isAutomaticGeneration = false, isBackgroundGeneration = false)
        else this
    }
    if ((progress.automatic || backgroundEnabled || isBackgroundGeneration) &&
        (isBackgroundGeneration || !foregroundGenerating)) return copy(phase = progress.phase,
        error = progress.error, isAutomaticGeneration = progress.automatic, isBackgroundGeneration = backgroundEnabled)
    return this
}
