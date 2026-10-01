package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.domain.ClipRecord

data class ReviewQueueUiState(
    val clips: List<ClipRecord> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)
