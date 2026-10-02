package com.rchia.ecocapture.phase0.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rchia.ecocapture.phase0.domain.ClipRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun ReviewQueueScreen(
    viewModel: ReviewQueueViewModel,
    onBack: () -> Unit,
    onClipSelected: (ClipRecord) -> Unit,
    automaticPreparationEnabled: Boolean,
    preferenceSaving: Boolean,
    preferenceError: String?,
    onAutomaticPreparationChanged: (Boolean) -> Unit,
    backgroundPreparationEnabled: Boolean,
    chargingOnly: Boolean,
    onBackgroundPreparationChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                Text("REVIEW RECORDINGS", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${state.clips.size} recordings waiting",
                    modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                    fontSize = 19.sp,
                )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "automatic_preparation_setting") {
                            AutomaticPreparationSetting(
                                enabled = automaticPreparationEnabled,
                                saving = preferenceSaving,
                                error = preferenceError,
                                onChanged = onAutomaticPreparationChanged,
                            )
                        }
                        if (state.isLoading) item { Text("Loading recordings...", fontSize = 19.sp) }
                        item(key = "background_preparation_setting") {
                            BackgroundPreparationSetting(backgroundPreparationEnabled, chargingOnly, preferenceSaving,
                                onBackgroundPreparationChanged, onChargingOnlyChanged)
                        }
                        state.error?.let { message ->
                            item { Text(message, fontSize = 19.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        }
                        if (!state.isLoading && state.error == null && state.clips.isEmpty()) {
                            item { Text("No recordings are waiting for review.", fontSize = 19.sp) }
                        }
                        items(state.clips, key = { it.clipId }) { clip ->
                            QueueItem(clip = clip, onClick = { onClipSelected(clip) })
                        }
                    }
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) { Text("BACK", fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun QueueItem(clip: ClipRecord, onClick: () -> Unit) {
    val capturedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date.from(clip.createdAt))
    val durationSeconds = clip.durationMs / 1000
    val description = "Recording from $capturedAt. Duration $durationSeconds seconds. " +
        "${if (clip.reviewState == com.rchia.ecocapture.phase0.domain.ReviewState.UNREVIEWED) "Not reviewed" else "Deferred"}. Double tap to review."
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        Column(modifier = Modifier.padding(18.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recording from $capturedAt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("$durationSeconds seconds", fontSize = 18.sp)
            Text(clip.reviewState.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, fontSize = 18.sp)
        }
    }
}
