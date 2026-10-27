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
        PreparationToggle("Prepare only while charging", chargingOnly, !saving && enabled, onChargingChanged)
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
