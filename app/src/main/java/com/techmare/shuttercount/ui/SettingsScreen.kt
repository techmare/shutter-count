package com.techmare.shuttercount.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val c = LocalAppColors.current
    val showWear by vm.showWear.collectAsState()
    val reportWear by vm.reportWear.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Shutter life", Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = c.ink)
        SettingSwitch(
            title = "Show shutter life in the app",
            body = "The rated life, wear scale and condition next to each count. Turn off to see the count only.",
            checked = showWear,
            onChange = vm::setShowWear,
        )
        HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = c.rule)
        SettingSwitch(
            title = "Include shutter life in reports",
            body = "Adds rated life, % used and condition to the report you share with buyers.",
            checked = reportWear,
            onChange = vm::setReportWear,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.signal, checkedThumbColor = Color(0xFF1C1F23), checkedBorderColor = c.signal,
            ),
        )
    }
}
