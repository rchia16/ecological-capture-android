package com.rchia.ecocapture.phase0.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.Lifecycle
import com.rchia.ecocapture.phase0.domain.ReviewState
import com.rchia.ecocapture.phase0.domain.ApprovalState
import com.rchia.ecocapture.phase0.ui.review.player.LocalClipPlayer
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun ClipReviewScreen(
    viewModel: ClipReviewViewModel,
    onBack: () -> Unit,
    onDeferred: () -> Unit,
    onApproved: () -> Unit,
    onDeleted: () -> Unit,
    onError: (String) -> Unit,
    onPlaybackStateChanged: (Boolean) -> Unit,
    isCaptureBusy: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val description by viewModel.descriptionEditor.state.collectAsStateWithLifecycle()
    val ai by viewModel.vlmReview.state.collectAsStateWithLifecycle()
    val automaticPreparation by viewModel.automaticPreparationEnabled.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val clip = state.clip
    var confirmDelete by remember(clip?.clipId) { mutableStateOf(false) }
    var playbackReleased by remember(clip?.clipId) { mutableStateOf(false) }
    var releasePlayer by remember { mutableStateOf<(() -> Unit)?>(null) }
    val descriptionActionFocus = remember { FocusRequester() }
    var editorWasOpen by remember { mutableStateOf(false) }
    val canDecide = clip != null && !state.isSaving &&
        clip.approvalState == ApprovalState.UNDECIDED &&
        clip.reviewState in setOf(ReviewState.UNREVIEWED, ReviewState.DEFERRED)

    BackHandler(enabled = description.draft == null) { if (!state.isSaving) onBack() }
    LaunchedEffect(state.error) { state.error?.let(onError) }
    LaunchedEffect(description.error) { description.error?.let(onError) }
    LaunchedEffect(ai.error) { ai.error?.let(onError) }
    DisposableEffect(viewModel, clip?.clipId) {
        onDispose { viewModel.vlmReview.cancelForeground() }
    }
    // Real CPU inference can outlast the normal display timeout. Release this flag on exit.
    DisposableEffect(view, ai.isGenerating, ai.isBackgroundGeneration, lifecycleState) {
        val previous = view.keepScreenOn
        if (ai.isGenerating && !ai.isBackgroundGeneration && lifecycleState == Lifecycle.State.RESUMED) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(clip?.clipId, automaticPreparation, ai.isLoading, ai.phase, state.isSaving,
        isCaptureBusy, lifecycleState) {
        if (isCaptureBusy) {
            viewModel.vlmReview.cancelForeground()
        } else if (lifecycleState != Lifecycle.State.RESUMED) {
            viewModel.vlmReview.cancelForeground()
        } else if (automaticPreparation && lifecycleState == Lifecycle.State.RESUMED && !state.isSaving) {
            clip?.let { viewModel.prepareSuggestionAutomatically(it.clipId) }
        }
    }
    LaunchedEffect(description.draft != null) {
        if (description.draft != null) editorWasOpen = true
        else if (editorWasOpen) {
            editorWasOpen = false
            descriptionActionFocus.requestFocus()
        }
    }
    if (description.draft != null) {
        DescriptionEditorScreen(
            state = description, ai = ai,
            isCaptureBusy = isCaptureBusy,
            onTextChanged = viewModel.descriptionEditor::changeText,
            onSave = viewModel.descriptionEditor::save,
            onCancel = viewModel.descriptionEditor::cancelEditing,
            onGenerate = { if (!isCaptureBusy) clip?.let { viewModel.vlmReview.generate(it.videoFile) } },
            onCancelGeneration = viewModel.vlmReview::cancel,
            onLeaveGeneration = viewModel.vlmReview::cancelForeground,
            onUse = viewModel.vlmReview::useAsStartingPoint,
            onReject = viewModel.vlmReview::ignore,
            onOutputVisible = viewModel.vlmReview::onOutputVisible,
        )
        return
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize().semantics { paneTitle = "Recording review" }) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isLoading) {
                    Text("Loading recording…", fontSize = 21.sp)
                } else if (clip == null) {
                    Text("Recording unavailable.", fontSize = 21.sp)
                } else {
                    Text(
                        "Recorded: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(clip.createdAt))}\n" +
                            "${clip.durationMs / 1000} seconds · ${clip.reviewState.name.lowercase().replace('_', ' ')}",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // Split the space remaining after the recording header and pinned actions.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(0.6f).background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (clip != null) {
                            if (playbackReleased) {
                                Text("Playback stopped.", color = Color.White, fontSize = 20.sp)
                            } else if (clip.videoFile.exists()) {
                                LocalClipPlayer(
                                    file = clip.videoFile,
                                    onPlaybackError = viewModel::onPlaybackError,
                                    modifier = Modifier.fillMaxSize(),
                                    onReleaseReady = { releasePlayer = it },
                                    onPlaybackStateChanged = onPlaybackStateChanged,
                                )
                            } else {
                                Text(
                                    "Recording file unavailable.",
                                    modifier = Modifier.semantics {
                                        contentDescription = "Recording file unavailable."
                                    },
                                    fontSize = 20.sp,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(0.4f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.error?.let { message ->
                            Text(message, fontSize = 18.sp,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (clip != null) {
                            Text("YOUR DESCRIPTION", fontSize = 21.sp,
                                modifier = Modifier.semantics { heading() })
                            Text(
                                if (description.isLoading) "Loading description…"
                                else description.annotation?.text ?: "No description added.",
                                fontSize = 18.sp,
                            )
                            AiPreparationStatus(ai, viewModel.vlmReview::cancel,
                                "AI suggestion ready. Open Add/Edit Description to review it.")
                            if (description.draft == null) {
                                description.error?.let { message ->
                                    Text(message, fontSize = 18.sp,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                            }
                        }
                    }
                }
                val actions = buildList {
                    if (clip != null) {
                        add(ReviewAction(
                            if (description.annotation == null) "ADD DESCRIPTION" else "EDIT DESCRIPTION",
                            !description.isLoading && !description.isSaving && description.error == null &&
                                !state.isSaving && clip.approvalState != ApprovalState.DELETED,
                            viewModel.descriptionEditor::startEditing,
                            isDescriptionAction = true,
                        ))
                        add(ReviewAction("APPROVE",
                            canDecide && !state.playbackFailed && !playbackReleased && clip.videoFile.isFile,
                            { scope.launch { if (viewModel.approve()) onApproved() } },
                        ))
                        add(ReviewAction("REVIEW LATER", canDecide && !playbackReleased,
                            { scope.launch { if (viewModel.reviewLater()) onDeferred() } },
                        ))
                        add(ReviewAction("DELETE", !state.isSaving && clip.approvalState != ApprovalState.DELETED,
                            { confirmDelete = true },
                        ))
                    }
                    add(ReviewAction("BACK", !state.isSaving, onBack))
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { action ->
                                Button(
                                    onClick = action.onClick,
                                    enabled = action.enabled,
                                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)
                                        .then(if (action.isDescriptionAction) Modifier.focusRequester(descriptionActionFocus) else Modifier),
                                ) {
                                    Text(action.label, fontSize = 18.sp)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this recording?") },
                text = {
                    Text("This removes it from this phone. This cannot be undone.")
                },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { confirmDelete = false },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("CANCEL")
                        }
                        Button(
                            onClick = {
                                confirmDelete = false
                                // Release synchronously before launching any filesystem work.
                                releasePlayer?.invoke()
                                playbackReleased = true
                                scope.launch { if (viewModel.delete()) onDeleted() }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("DELETE RECORDING")
                        }
                    }
                },
            )
        }
    }
}

private data class ReviewAction(val label: String, val enabled: Boolean, val onClick: () -> Unit,
    val isDescriptionAction: Boolean = false)
