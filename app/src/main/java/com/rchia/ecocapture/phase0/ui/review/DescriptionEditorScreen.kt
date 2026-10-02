package com.rchia.ecocapture.phase0.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.rchia.ecocapture.phase0.domain.VlmDisposition

/** Description editing and suggestion review share a draft, but use separate full-screen steps. */
@Composable
fun DescriptionEditorScreen(
    state: DescriptionEditorState,
    ai: VlmReviewState,
    isCaptureBusy: Boolean,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,
    onLeaveGeneration: () -> Unit = onCancelGeneration,
    onUse: () -> Unit,
    onReject: () -> Unit,
    onOutputVisible: (String) -> Unit,
) {
    val draft = state.draft ?: return
    var reviewing by remember { mutableStateOf(false) }
    var copyRequested by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val savedText = remember { state.annotation?.text.orEmpty() }
    val hasUnsavedChanges = draft.text != savedText || draft.parentVlmRunId != null
    val titleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { if (!state.isSaving && !ai.isUpdating) { onLeaveGeneration(); onCancel() } }
    val requestBack = {
        if (!state.isSaving && !ai.isUpdating) {
            if (hasUnsavedChanges) { keyboard?.hide(); confirmDiscard = true } else close()
        }
    }
    BackHandler {
        if (reviewing && !ai.isUpdating) {
            onLeaveGeneration()
            reviewing = false
        } else if (!reviewing) requestBack()
    }
    LaunchedEffect(reviewing) { titleFocus.requestFocus() }
    LaunchedEffect(copyRequested, ai.isUpdating, draft.parentVlmRunId) {
        if (copyRequested && !ai.isUpdating && draft.parentVlmRunId == ai.run?.vlmRunId) {
            copyRequested = false
            reviewing = false
        }
    }
    LaunchedEffect(ai.run?.disposition, ai.phase) {
        if (ai.run?.disposition == VlmDisposition.IGNORED.name && !ai.isGenerating) reviewing = false
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = if (reviewing) "Review AI suggestion" else "Description" }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (reviewing) "Review AI suggestion" else "Description", fontSize = 24.sp,
                    modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reviewing) {
                        AiDescriptionSection(
                            state = ai,
                            canPresent = true,
                            onOutputVisible = onOutputVisible,
                        )
                    } else {
                        Text("Describe the situation in your own words. This is optional.", fontSize = 18.sp)
                        OutlinedTextField(value = draft.text, onValueChange = onTextChanged,
                            label = { Text("Your description") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isSaving, minLines = 4, maxLines = 10)
                        if (draft.parentVlmRunId != null) {
                            Text("Suggestion copied into your draft. You can edit it before saving. Choose Save description to keep it.",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        AiPreparationStatus(ai, onCancelGeneration)
                        if (!ai.isGenerating && (ai.run == null || ai.run.disposition == VlmDisposition.IGNORED.name)) {
                            Button(onClick = { keyboard?.hide(); reviewing = true; onGenerate() },
                                enabled = !state.isSaving && !ai.isLoading && !ai.isUpdating && !isCaptureBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(if (ai.run == null) "GENERATE AI SUGGESTION" else "GENERATE ANOTHER SUGGESTION",
                                    fontSize = 18.sp)
                            }
                        } else if (!ai.isGenerating && !ai.isSuggestionResolved && ai.run != null) {
                            Button(onClick = { keyboard?.hide(); reviewing = true },
                                enabled = !state.isSaving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text("REVIEW AI SUGGESTION", fontSize = 18.sp)
                            }
                        }
                        ai.error?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                    if (state.isSaving) Text("Saving description…",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    state.error?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                if (reviewing) {
                    HorizontalDivider()
                    val back = { onLeaveGeneration(); reviewing = false }
                    if (ai.run != null && !ai.isSuggestionResolved && !ai.isGenerating) {
                        val canChoose = !state.isSaving && !ai.isUpdating && ai.run.firstPresentedAtEpochMs != null
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("You can edit this suggestion before saving.", fontSize = 18.sp)
                            Button(onClick = { copyRequested = true; onUse() }, enabled = canChoose,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text("USE SUGGESTION", fontSize = 18.sp)
                            }
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReviewActionButton("REJECT SUGGESTION", canChoose, onReject)
                                ReviewActionButton("BACK TO DESCRIPTION", !ai.isUpdating, back)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ai.isGenerating) {
                                ReviewActionButton("CANCEL AI PREPARATION", true, { onCancelGeneration(); reviewing = false })
                            } else {
                                ReviewActionButton("GENERATE AI SUGGESTION",
                                    !state.isSaving && !ai.isLoading && !ai.isUpdating && !isCaptureBusy, onGenerate)
                            }
                            ReviewActionButton("BACK TO DESCRIPTION", !ai.isUpdating, back)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave, enabled = draft.canSave && !state.isSaving && !ai.isUpdating,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text("SAVE DESCRIPTION", fontSize = 18.sp)
                        }
                        Button(onClick = requestBack, enabled = !state.isSaving && !ai.isUpdating,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text("BACK", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard changes?") },
                text = { Text("Your description has unsaved changes.") },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("KEEP EDITING")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false; close() },
                        enabled = !state.isSaving && !ai.isUpdating, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("DISCARD CHANGES")
                    }
                },
            )
        }
    }
}

@Composable
private fun RowScope.ReviewActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)) {
        Text(label, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
