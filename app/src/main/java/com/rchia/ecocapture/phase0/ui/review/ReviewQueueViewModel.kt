package com.rchia.ecocapture.phase0.ui.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rchia.ecocapture.phase0.data.RoomClipRepository
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

class ReviewQueueViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoomClipRepository(
        EcologicalCaptureDatabase.getInstance(application).clipDao(),
    )

    val uiState: StateFlow<ReviewQueueUiState> = repository.observeReviewQueue()
        .map { ReviewQueueUiState(clips = it, isLoading = false) }
        .catch { emit(ReviewQueueUiState(isLoading = false, error = "Recordings could not be loaded. Please restart the app to try again.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewQueueUiState())
}
