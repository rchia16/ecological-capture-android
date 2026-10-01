package com.rchia.ecocapture.phase0.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.RegistrationState
import com.rchia.ecocapture.phase0.Phase0ViewModel

@Composable
fun Phase0Screen(
    viewModel: Phase0ViewModel,
    onRegister: () -> Unit,
    onStartCamera: () -> Unit,
    onReviewRecordings: () -> Unit,
    pendingCount: Int,
    reviewQueueError: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Ecological Capture",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Android Phase 1",
                    fontSize = 22.sp,
                )

                BigButton(
                    text = "REVIEW RECORDINGS",
                    description = "Review recordings. $pendingCount recordings waiting.",
                    enabled = !state.isRecordingRequested && !state.isFinalizingRecording,
                    onClick = onReviewRecordings,
                )

                Text("$pendingCount recordings waiting", fontSize = 18.sp)
                reviewQueueError?.let { Text(it, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

                StatusCard(
                    status = state.status,
                    registration = state.registrationState.name,
                    glasses = if (state.hasActiveDevice) "Detected" else "Not detected",
                    camera = if (state.isCameraReady) "Ready" else (state.streamState?.name ?: "Stopped"),
                    fps = state.streamFps,
                    width = state.frameWidth,
                    height = state.frameHeight,
                )

                state.error?.let { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "Needs attention",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(message, fontSize = 18.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = viewModel::clearError,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp),
                            ) {
                                Text("Dismiss", fontSize = 18.sp)
                            }
                        }
                    }
                }

                if (state.registrationState != RegistrationState.REGISTERED) {
                    BigButton(
                        text = if (state.registrationState == RegistrationState.REGISTERING) {
                            "REGISTERING..."
                        } else {
                            "REGISTER GLASSES APP"
                        },
                        description = "Register this study app with Meta Wearables",
                        enabled = state.sdkInitialized &&
                            state.registrationState != RegistrationState.REGISTERING,
                        onClick = onRegister,
                    )
                }

                if (state.isRegistered && !state.isCameraReady) {
                    BigButton(
                        text = "START GLASSES CAMERA",
                        description = "Connect to the Ray-Ban Meta camera stream",
                        enabled = state.hasActiveDevice,
                        onClick = onStartCamera,
                    )
                }

                if (state.isCameraReady) {
                    if (state.isFinalizingRecording) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "FINALIZING RECORDING",
                                modifier = Modifier.padding(24.dp),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else if (!state.isRecordingRequested) {
                        BigButton(
                            text = "RECORD A CLIP",
                            description = "Start a short first-person study recording",
                            enabled = state.canRecord,
                            onClick = viewModel::startRecording,
                        )
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = if (state.isRecordingConfirmed) {
                                        "RECORDING"
                                    } else {
                                        "PREPARING RECORDING"
                                    },
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "${state.recordingElapsedSeconds} seconds",
                                    fontSize = 24.sp,
                                )
                            }
                        }

                        BigButton(
                            text = "STOP RECORDING",
                            description = "Stop and save the current recording",
                            enabled = true,
                            onClick = viewModel::stopRecording,
                        )
                    }
                }

                if (state.streamState != null) {
                    OutlinedButton(
                        onClick = viewModel::stopCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                        enabled = !state.isRecordingRequested,
                    ) {
                        Text("Stop glasses camera", fontSize = 19.sp)
                    }
                }

                state.lastSavedFile?.let { path ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "Last clip saved",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${state.lastSavedBytes / 1024} KB",
                                fontSize = 18.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = path,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }

                Text(
                    text = "Recordings and review decisions are saved in this app's private local storage. " +
                        "There is no participant upload, annotation, VLM, or backend in this build.",
                    fontSize = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: String,
    registration: String,
    glasses: String,
    camera: String,
    fps: Float,
    width: Int,
    height: Int,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription =
                    "$status. Registration $registration. Glasses $glasses. Camera $camera."
            },
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(status, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("Registration: $registration", fontSize = 17.sp)
            Text("Glasses: $glasses", fontSize = 17.sp)
            Text("Camera: $camera", fontSize = 17.sp)
            if (width > 0 && height > 0) {
                Text(
                    "Observed stream: ${width}x$height, ${"%.1f".format(fps)} fps",
                    fontSize = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun BigButton(
    text: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
