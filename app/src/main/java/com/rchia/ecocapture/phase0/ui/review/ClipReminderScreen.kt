package com.rchia.ecocapture.phase0.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rchia.ecocapture.phase0.data.local.ClipContextEntity
import com.rchia.ecocapture.phase0.data.RecordingLocation
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.rchia.ecocapture.phase0.domain.ClipRecord
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun ClipReminderScreen(
    clip: ClipRecord,
    context: ClipContextEntity?,
    ai: VlmReviewState,
    isCaptureBusy: Boolean,
    onBack: () -> Unit,
    onSaveSuburb: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancelGeneration: () -> Unit,
    onOutputVisible: (String) -> Unit,
) {
    val androidContext = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var speechReady by remember { mutableStateOf(false) }
    var speechChecked by remember { mutableStateOf(false) }
    val currentRun = rememberUpdatedState(ai.run?.vlmRunId)
    val currentVisible = rememberUpdatedState(onOutputVisible)
    val speech = remember(clip.clipId) {
        ReminderSpeech(androidContext, { currentRun.value?.let(currentVisible.value) },
            { speechReady = it; speechChecked = true })
    }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) speech.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.close() }
    }
    LaunchedEffect(ai.run?.vlmRunId, isCaptureBusy) { speech.stop() }
    val close = { speech.stop(); onBack() }
    BackHandler(onBack = close)
    val formatter = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT)
    formatter.timeZone = context?.recordingTimeZoneId?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
    val whenText = formatter.format(Date.from(clip.createdAt))
    val zoneNote = if (context?.recordingTimeZoneId == null) "device time zone (original time zone unavailable)"
        else "recording time zone: ${formatter.timeZone.displayName}"
    val suburb = context?.suburb?.takeIf { it.isNotBlank() }
    val locationAllowed = RecordingLocation.enabled(androidContext) &&
        ContextCompat.checkSelfPermission(androidContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    var editingSuburb by remember(clip.clipId) { mutableStateOf(false) }
    var suburbDraft by remember(clip.clipId, suburb) { mutableStateOf(suburb.orEmpty()) }
    val intro = "Recorded $whenText, $zoneNote. " +
        if (suburb == null) "Location unavailable. " else "Approximately in $suburb. "
    val spoken = intro + if (ai.run == null) "No AI visual reminder is available."
        else "AI visual reminder. This may be incomplete or incorrect. "
    MaterialTheme {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = "Clip reminder" }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Clip reminder", fontSize = 24.sp, modifier = Modifier.semantics { heading() })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Recorded: $whenText\n$zoneNote", fontSize = 18.sp)
                    Text("Approximate suburb: ${suburb ?: "Location unavailable"}", fontSize = 18.sp)
                    if (locationAllowed && suburb != null && editingSuburb) {
                        OutlinedTextField(value = suburbDraft, onValueChange = { suburbDraft = it },
                            label = { Text("Suburb or nearby area") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Button(onClick = { onSaveSuburb(suburbDraft); editingSuburb = false },
                            enabled = suburbDraft.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("SAVE AREA") }
                    } else if (locationAllowed && suburb != null) Button(onClick = { editingSuburb = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("EDIT SUBURB")
                    }
                    if (speechChecked && !speechReady) {
                        Text("To listen, install an English offline text-to-speech voice in Android settings.", fontSize = 18.sp)
                    }
                    AiDescriptionSection(ai, canPresent = true, onOutputVisible = onOutputVisible)
                    if (ai.isGenerating) Button(onClick = onCancelGeneration,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("CANCEL AI PREPARATION") }
                    else Button(onClick = onGenerate, enabled = !ai.isLoading && !ai.isUpdating && !isCaptureBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (ai.run == null) "GENERATE VISUAL REMINDER" else "REGENERATE VISUAL REMINDER")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { speech.speak(spoken, ai.run?.description) },
                        enabled = speechReady, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("LISTEN")
                    }
                    Button(onClick = speech::stop, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("STOP")
                    }
                    Button(onClick = close, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("BACK")
                    }
                }
            }
        }
    }
}
