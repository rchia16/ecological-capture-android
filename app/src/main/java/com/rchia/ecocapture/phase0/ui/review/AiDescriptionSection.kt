package com.rchia.ecocapture.phase0.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun AiDescriptionSection(
    state: VlmReviewState,
    canPresent: Boolean,
    onOutputVisible: (String) -> Unit,
) {
    // Once dealt with, the suggestion is represented only by the participant's description.
    // Rejection retains an existing description, or leaves the normal empty-description state.
    if (state.isSuggestionResolved && !state.isGenerating) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI SUGGESTION", fontSize = 21.sp, modifier = Modifier.semantics { heading() })
        if (state.run?.runtimeName == "FakeVlmEngine") {
            Text("This saved suggestion was a simulation. It did not analyze the recording.", fontSize = 18.sp)
        } else {
            Text("Optional visual context from sampled images. Fine details may be unclear.", fontSize = 18.sp)
        }
        val status = when {
            state.isGenerating -> state.preparationMessage
            state.isLoading -> "Loading AI review…"
            state.phase == VlmReviewPhase.PREPARING -> "Preparing AI description…"
            state.phase == VlmReviewPhase.RUNNING -> "Generating AI description… This may take some time."
            state.phase == VlmReviewPhase.CANCELLED -> "AI generation cancelled."
            state.phase == VlmReviewPhase.ERROR -> "AI description unavailable."
            state.run != null -> "AI description available."
            else -> "No AI description generated."
        }
        Text(status, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.error?.let { Text(it, fontSize = 18.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!state.isGenerating) {
            val run = state.run
            if (run != null) {
                Text("AI-generated. May be incomplete or incorrect.", fontSize = 18.sp)
                VisibleAiOutput(run.vlmRunId, run.description, canPresent, onOutputVisible)
            }
        }
    }
}

/** Composition alone does not establish exposure: text must intersect its clipped viewport. */
@Composable
private fun VisibleAiOutput(runId: String, output: String, canPresent: Boolean, onVisible: (String) -> Unit) {
    var visible by remember(runId) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState == Lifecycle.State.RESUMED }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(runId, visible, resumed, canPresent) {
        if (visible && resumed && canPresent) onVisible(runId)
    }
    // Deliberately no liveRegion on model text: it is read only when focused with TalkBack.
    Text(output, fontSize = 18.sp, modifier = Modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0
    })
}
