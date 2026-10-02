package com.rchia.ecocapture.phase0.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AutomaticPreparationSetting(enabled: Boolean, saving: Boolean, error: String?, onChanged: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = enabled, enabled = !saving, role = Role.Switch, onValueChange = onChanged),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Prepare AI suggestions automatically", fontSize = 18.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null, enabled = !saving)
        }
        Text("Off by default. When enabled, all saved recordings without a description or AI suggestion are queued, including new recordings. " +
            "One recording is prepared at a time. Existing requests are not duplicated. Failed or cancelled attempts are retried only when you request them or turn this setting on again. " +
            "You choose whether to review or use it. Regeneration is always your choice. " +
            "AI preparation runs on this phone and may take about 10 to 13 minutes. " +
            "Keep the app open unless background preparation is enabled below. " +
            "You can cancel and continue reviewing without a suggestion.", fontSize = 16.sp)
        error?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
