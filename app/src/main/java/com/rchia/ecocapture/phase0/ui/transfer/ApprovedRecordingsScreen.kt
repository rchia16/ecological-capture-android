package com.rchia.ecocapture.phase0.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rchia.ecocapture.phase0.domain.ClipRecord
import com.rchia.ecocapture.phase0.transfer.TransferState
import java.text.DateFormat
import java.util.Date

@Composable
fun ApprovedRecordingsScreen(viewModel: TransferViewModel, onBack: () -> Unit,
                             onReview: (ClipRecord) -> Unit, captureBusy: Boolean) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
                .semantics { paneTitle = "Approved recordings" }) {
                Text("APPROVED RECORDINGS", fontSize = 26.sp, modifier = Modifier.semantics { heading() })
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("Your approved videos remain on this phone. Sending requires a separate action.", fontSize = 18.sp)
                        Text(if (viewModel.liveSending) "Encrypted uploads wait for Wi-Fi. Uploading and verification on GB10 are separate steps."
                            else "Local transfer simulation. Nothing leaves this phone.", fontSize = 18.sp)
                        if (viewModel.liveSending) OutlinedButton(onClick = { viewModel.enrol(context) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("STUDY ENROLMENT", fontSize = 18.sp)
                        }
                        if (!viewModel.liveSending && com.rchia.ecocapture.phase0.BuildConfig.DEBUG) OutlinedButton(
                            onClick = { context.startActivity(android.content.Intent().setClassName(context.packageName,
                                "com.rchia.ecocapture.phase0.transfer.TransferSimulationActivity")) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("DISPOSABLE CLIP SETUP", fontSize = 18.sp)
                        }
                    }
                    if (state.loading) item { Text("Loading approved recordings") }
                    state.error?.let { error -> item {
                        Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 18.sp)
                    } }
                    if (!state.loading && state.rows.isEmpty()) item { Text("No approved recordings yet.", fontSize = 19.sp) }
                    items(state.rows, key = { it.clip.clipId }) { row ->
                        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(row.clip.createdAt))
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Recording from $date", fontSize = 20.sp, modifier = Modifier.semantics { heading() })
                                Text("${row.clip.durationMs / 1000} seconds. Approved locally.", fontSize = 18.sp)
                                if (!row.enabled) Text("Sending is not enabled for this recording.", fontSize = 18.sp)
                                if (!row.available) Text("Saved video is unavailable. Your review data remain on this phone.", fontSize = 18.sp)
                                row.job?.let { job ->
                                    Text(TransferState.valueOf(job.state).message(job.progressPercent), fontSize = 18.sp,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                                if (row.changed) Text("New local changes since last send. Send updated recording to create a new version.", fontSize = 18.sp)
                                val busy = row.job?.state in setOf("PREPARING", "SIMULATING", "QUEUED", "ENCRYPTING", "UPLOADING")
                                val sameFinished = row.job?.state in setOf("SIMULATED_COMPLETE", "AWAITING_GB10", "VERIFIED_GB10", "NEEDS_ATTENTION") && !row.changed
                                Button(onClick = { viewModel.send(row.clip.clipId) },
                                    enabled = row.enabled && row.available && !captureBusy && !busy && !sameFinished &&
                                        (row.job?.state != "FAILED_RETRYABLE" || row.changed),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Text(if (row.changed) "SEND UPDATED RECORDING" else "SEND APPROVED RECORDING", fontSize = 18.sp)
                                }
                                if (row.job?.state == "FAILED_RETRYABLE" && !row.changed) Button(
                                    onClick = { viewModel.retry(row.job.jobId) }, enabled = row.enabled && row.available && !captureBusy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (viewModel.liveSending) "RETRY SENDING" else "RETRY SIMULATION", fontSize = 18.sp) }
                                if (busy || row.job?.state == "FAILED_RETRYABLE") Button(
                                    onClick = { row.job?.let { viewModel.cancel(it.jobId) } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (viewModel.liveSending) "CANCEL SENDING" else "CANCEL SIMULATION", fontSize = 18.sp) }
                                OutlinedButton(onClick = { onReview(row.clip) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Text("REVIEW RECORDING", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("BACK", fontSize = 20.sp) }
            }
        }
    }
}
