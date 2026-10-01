package com.rchia.ecocapture.phase0.ui.review

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rchia.ecocapture.phase0.data.RoomClipRepository
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoomClipRepository(EcologicalCaptureDatabase.getInstance(application).clipDao())

    private val _uiState = MutableStateFlow(ClipReviewUiState())
    val uiState = _uiState.asStateFlow()
    private var observation: Job? = null

    fun selectClip(clipId: String) {
        observation?.cancel()
        _uiState.value = ClipReviewUiState()
        observation = viewModelScope.launch {
            try {
                repository.observeClip(clipId).collect { clip ->
                    _uiState.update { it.copy(clip = clip, isLoading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ClipReview", "Could not load recording", error)
                _uiState.update { it.copy(isLoading = false, error = "Recording could not be loaded. Please go back and try again.") }
            }
        }
    }

    suspend fun reviewLater(): Boolean {
        val current = _uiState.value
        val clip = current.clip ?: return false
        if (current.isSaving) return false
        _uiState.update { it.copy(isSaving = true, error = null) }
        return try {
            withContext(Dispatchers.IO) { repository.deferClip(clip.clipId) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ClipReview", "Could not defer ${clip.clipId}", error)
            _uiState.update { it.copy(error = "Could not save Review Later. The recording is still on this phone. Please try again.") }
            false
        } finally {
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    fun onPlaybackError() {
        _uiState.update {
            it.copy(
                playbackFailed = true,
                error = "Recording could not be played. The file has not been deleted.",
            )
        }
    }

    suspend fun approve(): Boolean {
        val current = _uiState.value
        val clip = current.clip ?: return false
        if (current.isSaving || current.playbackFailed) return false
        _uiState.update { it.copy(isSaving = true, error = null) }
        return try {
            withContext(Dispatchers.IO) {
                check(clip.videoFile.isFile && clip.videoFile.length() > 0L) {
                    "Recording file unavailable."
                }
                repository.approveClip(clip.clipId)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ClipReview", "Could not approve ${clip.clipId}", error)
            _uiState.update { it.copy(error = "Could not save approval. No recording files were changed. Please try again.") }
            false
        } finally {
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    suspend fun delete(): Boolean {
        val current = _uiState.value
        val clip = current.clip ?: return false
        if (current.isSaving) return false
        _uiState.update { it.copy(isSaving = true, error = null) }
        return try {
            repository.deleteClip(clip.clipId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ClipReview", "Could not delete ${clip.clipId}", error)
            _uiState.update {
                it.copy(error = "Could not finish deleting this recording. Some files may already have been removed. Please retry Delete.")
            }
            false
        } finally {
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
