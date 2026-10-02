package com.rchia.ecocapture.phase0.ui.review

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Announces readiness, never the generated text. This is not an exposure callback. */
@Composable
fun AiPreparationStatus(state: VlmReviewState, onCancel: () -> Unit,
    readyMessage: String = "AI suggestion ready.") {
    if (state.isGenerating) {
        Text(state.preparationMessage, fontSize = 18.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("CANCEL AI PREPARATION", fontSize = 18.sp)
        }
    } else if (state.run != null && !state.isSuggestionResolved) {
        Text(readyMessage, fontSize = 18.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

val VlmReviewState.preparationMessage: String get() = when (phase) {
    VlmReviewPhase.WAITING_APP -> "AI suggestion queued. Keep this app open to prepare it, or allow background preparation in Settings. Android may delay preparation."
    VlmReviewPhase.WAITING_CHARGE -> "AI suggestion queued. Waiting for this phone to charge. You can leave the app or cancel."
    VlmReviewPhase.WAITING_RECORDING -> "AI suggestion paused for recording. Preparation will restart later."
    VlmReviewPhase.WAITING_MEMORY -> "AI suggestion paused because this phone is low on memory. Preparation will restart later."
    VlmReviewPhase.CANCELLING -> "Stopping AI preparation and releasing memory. This may take some time."
    VlmReviewPhase.QUEUED -> "AI suggestion queued. One recording is prepared at a time. Android may delay preparation."
    VlmReviewPhase.PREPARING -> "Preparing AI suggestion. This may take some time."
    else -> "Generating AI description. This may take about 10 to 13 minutes. " +
        if (isBackgroundGeneration) "You can leave the app. A notification will tell you when it is ready." else "Keep the app open. You can continue reviewing or writing."
}
