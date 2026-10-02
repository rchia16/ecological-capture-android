package com.rchia.ecocapture.phase0.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BackgroundPreparationSetting(enabled: Boolean, chargingOnly: Boolean, saving: Boolean,
    onEnabledChanged: (Boolean) -> Unit, onChargingChanged: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreparationToggle("Allow AI preparation in the background", enabled, !saving, onEnabledChanged)
        Text("Off by default. When enabled, requested suggestions can continue while you use another app or turn off the screen. " +
            "A notification provides Cancel and tells you when a suggestion is ready. Recording takes priority. " +
            "AI text is never accepted or saved as your description automatically.", fontSize = 16.sp)
        if (enabled) {
            PreparationToggle("Prepare only while charging", chargingOnly, !saving, onChargingChanged)
            Text("Charging only is on by default. Without it, preparation uses battery and may warm the phone. " +
                "Changes apply to new requests. Unplugging pauses charging-only work; the attempt restarts later. " +
                "Android may delay background preparation.", fontSize = 16.sp)
        }
    }
}

@Composable
private fun PreparationToggle(label: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
