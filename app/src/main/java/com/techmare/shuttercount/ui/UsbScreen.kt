package com.techmare.shuttercount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel
import com.techmare.shuttercount.UsbState
import com.techmare.shuttercount.data.buildReport
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.ptp.CanonMethod

@Composable
fun UsbScreen(vm: MainViewModel, onAddNote: () -> Unit) {
    val state by vm.usb.collectAsState()
    val context = LocalContext.current
    var confirmAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val cameraName = when (val s = state) {
            is UsbState.Connected -> s.name
            is UsbState.Reading -> s.name
            is UsbState.Done -> s.reading.model
            else -> null
        }
        Button(
            onClick = { vm.readCanon(allowAdvanced = false) },
            enabled = state !is UsbState.Reading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Outlined.Usb, null)
            Spacer(Modifier.width(8.dp))
            Text(if (cameraName != null) "Read $cameraName" else "Read Canon over USB")
        }

        when (val s = state) {
            UsbState.NoCamera -> {
                StepsCard(
                    "Connect a Canon camera",
                    listOf(
                        "Turn OFF the camera's Wi-Fi / Bluetooth (it blocks the USB port).",
                        "Connect the camera to this phone with a USB cable and an OTG adapter if needed.",
                        "Turn the camera on (photo mode, not movie mode).",
                        "Tap Read and allow access when Android asks.",
                    ),
                )
                OutlinedButton(onClick = { vm.refreshUsb() }, modifier = Modifier.fillMaxWidth()) { Text("I've connected it") }
                NoticeCard("Nikon or Sony? Use the Photo tab — it's faster and works on almost every model.", warning = false)
            }
            is UsbState.Connected -> NoticeCard("${s.name} is connected. Tap Read.", warning = false)
            is UsbState.Reading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Reading ${s.name}… don't unplug it.")
                }
            }
            is UsbState.Error -> {
                NoticeCard(s.message, warning = true)
                StepsCard(
                    "If it keeps failing",
                    listOf(
                        "Turn the camera's Wi-Fi off and close the Canon Camera Connect app.",
                        "Unplug, turn the camera off and on, plug in again.",
                        "Try another cable — some cables only charge.",
                    ),
                )
            }
            is UsbState.Done -> {
                val r = s.reading
                val count = r.shutterCount
                if (count != null) {
                    CountCard(
                        r.model, count, r.ratedLife, s.previous,
                        badge = if (r.method == CanonMethod.FACTORY && !r.confirmed) "Experimental reading" else null,
                    )
                } else {
                    NoticeCard(r.problem ?: "No shutter count was returned.", warning = !r.canTryAdvanced)
                }
                if (r.canTryAdvanced) {
                    Button(onClick = { confirmAdvanced = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Try advanced read (experimental)")
                    }
                }
                r.notes.forEach { NoticeCard(it, warning = !r.confirmed) }

                val rows = buildList {
                    r.serial?.let { add("Serial no." to it) }
                    r.firmware?.let { add("Firmware" to it) }
                    r.method?.let { add("Method" to it.label) }
                    r.counters.drop(1).forEach { add(it.label to formatCount(it.value)) }
                }
                DetailsCard(rows)

                val raw = r.counters.mapNotNull { c -> c.raw?.let { "${c.label}: $it" } }
                if (raw.isNotEmpty()) {
                    Text("Raw data (for checking)", style = MaterialTheme.typography.labelMedium)
                    Text(raw.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }

                if (count != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                shareText(context, buildReport(
                                    r.model, r.serial, count, r.ratedLife, "USB (lecture directe)",
                                    experimental = r.method == CanonMethod.FACTORY && !r.confirmed,
                                ))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share report")
                        }
                        OutlinedButton(onClick = onAddNote, modifier = Modifier.weight(1f)) { Text("Add note") }
                    }
                    if (r.method == CanonMethod.FACTORY) {
                        OutlinedButton(onClick = { vm.readCanon(allowAdvanced = true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Read again")
                        }
                    }
                }
            }
        }
    }

    if (confirmAdvanced) {
        AlertDialog(
            onDismissRequest = { confirmAdvanced = false },
            title = { Text("Advanced read") },
            text = {
                Text(
                    "This asks the camera's shutter unit for its counters using a read-only method that is confirmed on the EOS 5D Mark IV. " +
                        "It only reads — it never writes to the camera. On other models the numbers may be wrong, so verify: " +
                        "take exactly 5 photos, read again, and check it went up by 5.\n\n" +
                        "If the camera shows an error, take the battery out and put it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAdvanced = false; vm.readCanon(allowAdvanced = true) }) { Text("Read") }
            },
            dismissButton = { TextButton(onClick = { confirmAdvanced = false }) { Text("Cancel") } },
        )
    }
}
