package com.rchia.ecocapture.phase0.ui.review

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rchia.ecocapture.phase0.data.RoomClipRepository
import com.rchia.ecocapture.phase0.data.AnnotationRepository
import com.rchia.ecocapture.phase0.data.VlmRunRepository
import com.rchia.ecocapture.phase0.data.AiSuggestionPreferences
import com.rchia.ecocapture.phase0.vlm.VlmEngineFactory
import com.rchia.ecocapture.phase0.vlm.VlmRuntimeMode
import com.rchia.ecocapture.phase0.vlm.background.BackgroundAiPreparation
import com.rchia.ecocapture.phase0.vlm.background.AiPreparationNotifications
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
    private val aiPreferences = AiSuggestionPreferences(application)
    private val background = BackgroundAiPreparation.get(application)
    val backgroundPreparationEnabled = aiPreferences.backgroundEnabled
    val chargingOnly = aiPreferences.chargingOnly
    private val repository = RoomClipRepository(EcologicalCaptureDatabase.getInstance(application).clipDao())
    val descriptionEditor = DescriptionEditor(
        AnnotationRepository(EcologicalCaptureDatabase.getInstance(application)), viewModelScope,
    )
    val vlmReview = VlmReviewWorkflow(
        VlmRunRepository(EcologicalCaptureDatabase.getInstance(application)),
        VlmEngineFactory.create(application, VlmRuntimeMode.QWEN_PARTICIPANT), descriptionEditor, viewModelScope,
        background = background, allowBackground = { backgroundPreparationEnabled.value },
        chargingOnly = { chargingOnly.value },
    )
    val automaticPreparationEnabled = aiPreferences.enabled
    private val mutablePreferenceSaving = MutableStateFlow(false)
    val preferenceSaving = mutablePreferenceSaving.asStateFlow()
    private val mutablePreferenceError = MutableStateFlow<String?>(null)
    val preferenceError = mutablePreferenceError.asStateFlow()

    fun setBackgroundPreparationEnabled(enabled: Boolean) {
        if (enabled && !AiPreparationNotifications.allowed(getApplication())) {
            mutablePreferenceError.value = "Allow notifications to use background AI preparation. You can still generate while reviewing."
            return
        }
        updatePreparationPreference {
            aiPreferences.setBackgroundEnabled(enabled)
            if (!enabled) background.cancelAllAndJoin()
        }
    }

    fun setChargingOnly(enabled: Boolean) = updatePreparationPreference { aiPreferences.setChargingOnly(enabled) }

    private fun updatePreparationPreference(change: suspend () -> Unit) {
        if (mutablePreferenceSaving.value) return
        mutablePreferenceSaving.value = true
        mutablePreferenceError.value = null
        viewModelScope.launch {
            try { change() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutablePreferenceError.value = "The AI preparation preference could not be saved. Please try again." }
            finally { mutablePreferenceSaving.value = false }
        }
    }

    fun setAutomaticPreparationEnabled(enabled: Boolean) {
        if (mutablePreferenceSaving.value) return
        mutablePreferenceSaving.value = true
        mutablePreferenceError.value = null
        viewModelScope.launch {
            try {
                aiPreferences.setEnabled(enabled)
                if (!enabled) background.cancelAutomaticAndJoin()
                if (!enabled && vlmReview.state.value.isAutomaticGeneration) vlmReview.cancel()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutablePreferenceError.value = "The AI preparation preference could not be saved. Please try again." }
            finally { mutablePreferenceSaving.value = false }
        }
    }

    suspend fun prepareSuggestionAutomatically(clipId: String) {
        val current = _uiState.value
        val clip = current.clip ?: return
        if (clip.clipId != clipId || current.isSaving || !automaticPreparationEnabled.value ||
            clip.approvalState == com.rchia.ecocapture.phase0.domain.ApprovalState.DELETED) return
        vlmReview.prepareAutomatically(clip.videoFile) { aiPreferences.claimFirstPreparation(clipId) }
    }

    private val _uiState = MutableStateFlow(ClipReviewUiState())
    val uiState = _uiState.asStateFlow()
    private var observation: Job? = null

    fun selectClip(clipId: String) {
        observation?.cancel()
        descriptionEditor.selectClip(clipId)
        vlmReview.selectClip(clipId)
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
            vlmReview.cancelAndJoin()
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
