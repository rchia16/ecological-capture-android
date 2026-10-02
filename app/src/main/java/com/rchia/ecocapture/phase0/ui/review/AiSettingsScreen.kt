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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AiSettingsScreen(
    automaticPreparationEnabled: Boolean,
    backgroundPreparationEnabled: Boolean,
    chargingOnly: Boolean,
    saving: Boolean,
    error: String?,
    onAutomaticPreparationChanged: (Boolean) -> Unit,
    onBackgroundPreparationChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    automaticPreparationStatus: String? = null,
) {
    val titleFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { titleFocus.requestFocus() }
    MaterialTheme {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = "Settings" }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SETTINGS", fontSize = 28.sp,
                    modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("AI descriptions", fontSize = 24.sp, modifier = Modifier.semantics { heading() })
                    Text("Changes are saved automatically. AI suggestions are optional. You can always write a description and make recording decisions without them.",
                        fontSize = 18.sp)
                    AutomaticPreparationSetting(automaticPreparationEnabled, saving, error, onAutomaticPreparationChanged)
                    automaticPreparationStatus?.let { message ->
                        Text(message, fontSize = 18.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    HorizontalDivider()
                    BackgroundPreparationSetting(backgroundPreparationEnabled, chargingOnly, saving,
                        onBackgroundPreparationChanged, onChargingOnlyChanged)
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                    Text("BACK", fontSize = 20.sp)
                }
            }
        }
    }
}
