package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.domain.ClipRecord

data class ClipReviewUiState(
    val clip: ClipRecord? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val playbackFailed: Boolean = false,
)
