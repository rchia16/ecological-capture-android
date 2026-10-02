package com.rchia.ecocapture.phase0.ui.review

import com.rchia.ecocapture.phase0.data.AnnotationRepository
import com.rchia.ecocapture.phase0.data.local.AnnotationEntity
import com.rchia.ecocapture.phase0.data.local.VlmRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The original revision ID stays fixed while editing, so a stale save cannot overwrite a revision. */
data class DescriptionDraft(val text: String, val expectedCurrentId: String?, val parentVlmRunId: String? = null) {
    val canSave: Boolean get() = text.isNotBlank()
}

data class DescriptionEditorState(
    val annotation: AnnotationEntity? = null,
    val isLoading: Boolean = true,
    val draft: DescriptionDraft? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
)

/** Participant text is kept out of logs, clip decisions and all VLM requests. */
class DescriptionEditor(
    private val repository: AnnotationRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(DescriptionEditorState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var selectedClipId: String? = null
    private var session = 0L

    fun selectClip(clipId: String) {
        observation?.cancel()
        selectedClipId = clipId
        val selectedSession = ++session
        mutableState.value = DescriptionEditorState()
        observation = scope.launch {
            try {
                repository.observeCurrent(clipId).collect { annotation ->
                    if (session == selectedSession) {
                        mutableState.update { it.copy(annotation = annotation, isLoading = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (session == selectedSession) {
                    mutableState.update { it.copy(isLoading = false,
                        error = "Your description could not be loaded. Reopen this recording to try again.") }
                }
            }
        }
    }

    fun startEditing() {
        val current = mutableState.value
        if (current.isLoading || current.isSaving || current.error != null || current.draft != null) return
        mutableState.update { it.copy(draft = DescriptionDraft(
            current.annotation?.text.orEmpty(), current.annotation?.annotationId), error = null) }
    }

    fun changeText(text: String) {
        mutableState.update { current ->
            if (current.isSaving) current else current.copy(draft = current.draft?.copy(text = text), error = null)
        }
    }

    fun startFromRun(run: VlmRunEntity): Boolean {
        val current = mutableState.value
        if (run.clipId != selectedClipId || current.isLoading || current.isSaving ||
            current.error != null) return false
        mutableState.update { it.copy(draft = DescriptionDraft(
            run.description, if (current.draft != null) current.draft.expectedCurrentId else current.annotation?.annotationId,
            run.vlmRunId), error = null) }
        return true
    }

    fun cancelEditing() {
        mutableState.update { if (it.isSaving) it else it.copy(draft = null, error = null) }
    }

    fun save() {
        val clipId = selectedClipId ?: return
        val current = mutableState.value
        val draft = current.draft ?: return
        if (current.isSaving || !draft.canSave) return
        val selectedSession = session
        mutableState.update { it.copy(isSaving = true, error = null) }
        // ViewModel scope lets a started write finish if the review screen is left.
        scope.launch {
            try {
                val saved = if (draft.parentVlmRunId != null) {
                    repository.saveAmendmentFromDraft(draft.parentVlmRunId, draft.text, now(), draft.expectedCurrentId)
                } else if (draft.expectedCurrentId == null) {
                    repository.addDescription(clipId, draft.text, now())
                } else {
                    repository.reviseDescription(clipId, draft.expectedCurrentId, draft.text, now())
                }
                if (session == selectedSession) {
                    mutableState.update { it.copy(annotation = saved, draft = null, isSaving = false) }
                }
            } catch (cancelled: CancellationException) {
                if (session == selectedSession) mutableState.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (_: Exception) {
                if (session == selectedSession) {
                    mutableState.update { it.copy(isSaving = false,
                        error = "Your description could not be saved. Your draft is still here. Try again, or cancel and reopen the editor.") }
                }
            }
        }
    }
}
