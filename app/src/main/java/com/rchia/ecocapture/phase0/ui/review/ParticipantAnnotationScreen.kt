package com.rchia.ecocapture.phase0.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Participant annotation remains an independent, optional draft. */
@Composable
fun ParticipantAnnotationScreen(
    state: DescriptionEditorState,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReminder: () -> Unit,
) {
    val draft = state.draft ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val savedText = state.annotation?.text.orEmpty()
    val requestBack = {
        if (!state.isSaving) {
            if (draft.text != savedText) { keyboard?.hide(); confirmDiscard = true }
            else onCancel()
        }
    }
    BackHandler(onBack = requestBack)
    MaterialTheme {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = "Your annotation" }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your annotation", fontSize = 24.sp, modifier = Modifier.semantics { heading() })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Describe the recording in your own words. An annotation is optional.", fontSize = 18.sp)
                    OutlinedTextField(value = draft.text, onValueChange = onTextChanged,
                        label = { Text("Your annotation") }, modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isSaving, minLines = 4, maxLines = 10)
                    Button(onClick = { keyboard?.hide(); onReminder() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("CLIP REMINDER", fontSize = 18.sp)
                    }
                    state.error?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = draft.canSave && !state.isSaving,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("SAVE ANNOTATION") }
                    Button(onClick = requestBack, enabled = !state.isSaving,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("BACK") }
                }
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") }, text = { Text("Your annotation has unsaved changes.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false }) { Text("KEEP EDITING") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("DISCARD") } })
    }
}
