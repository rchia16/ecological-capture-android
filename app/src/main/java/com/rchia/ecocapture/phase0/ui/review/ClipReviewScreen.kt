package com.rchia.ecocapture.phase0.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clip = state.clip
    var confirmDelete by remember(clip?.clipId) { mutableStateOf(false) }
    var playbackReleased by remember(clip?.clipId) { mutableStateOf(false) }
    var releasePlayer by remember { mutableStateOf<(() -> Unit)?>(null) }
    val canDecide = clip != null && !state.isSaving &&
        clip.approvalState == ApprovalState.UNDECIDED &&
        clip.reviewState in setOf(ReviewState.UNREVIEWED, ReviewState.DEFERRED)

    BackHandler { if (!state.isSaving) onBack() }
    LaunchedEffect(state.error) { state.error?.let(onError) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
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
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
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
                state.error?.let { message ->
                    Text(
                        message,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (clip != null) {
                        Button(
                            onClick = { scope.launch { if (viewModel.approve()) onApproved() } },
                            enabled = canDecide && !state.playbackFailed && !playbackReleased &&
                                clip.videoFile.isFile,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("APPROVE", fontSize = 18.sp)
                        }
                        Button(
                            onClick = { scope.launch { if (viewModel.reviewLater()) onDeferred() } },
                            enabled = canDecide && !playbackReleased,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("REVIEW LATER", fontSize = 18.sp)
                        }
                        Button(
                            onClick = { confirmDelete = true },
                            enabled = !state.isSaving && clip.approvalState != ApprovalState.DELETED,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("DELETE", fontSize = 18.sp)
                        }
                    }
                    Button(
                        onClick = onBack,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("BACK", fontSize = 18.sp)
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
