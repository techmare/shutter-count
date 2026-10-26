package com.techmare.shuttercount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
    val c = LocalAppColors.current
    val showWear by vm.showWear.collectAsState()
    val reportWear by vm.reportWear.collectAsState()
    var confirmAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            UsbState.NoCamera -> {
                InstrumentPanel(
                    title = "No camera connected", titleDim = true, count = null,
                    caption = "Plug in a Canon with a USB cable to read its count",
                )
                Steps(
                    "Connect a Canon",
                    listOf(
                        "Turn off the camera's Wi-Fi and Bluetooth. They block the USB port.",
                        "Connect it to this phone with a USB cable, plus an OTG adapter if needed.",
                        "Turn the camera on in photo mode, not movie mode.",
                        "Tap Read camera and allow access when Android asks.",
                    ),
                )
                Button(onClick = { vm.readCanon(allowAdvanced = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Read camera")
                }
                Note("Nikon, Sony, or a Canon R50, R100, R8, R6 or R5? Use the Photo tab instead.", warning = false)
            }

            is UsbState.Connected -> {
                InstrumentPanel(title = s.name, count = null, caption = "Connected and ready")
                Button(onClick = { vm.readCanon(allowAdvanced = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Read camera")
                }
                Note("Keep the camera on and plugged in until the count appears.", warning = false)
            }

            is UsbState.Reading -> {
                InstrumentPanel(
                    title = s.name, count = null, caption = "Reading… keep the camera plugged in",
                    footer = {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(Modifier.size(22.dp), color = c.signal, strokeWidth = 2.5.dp)
                    },
                )
            }

            is UsbState.Error -> {
                InstrumentPanel(title = "Couldn't read the camera", titleDim = true, count = null, caption = "Nothing was changed on the camera")
                Note(s.message, warning = true)
                Steps(
                    "Try this",
                    listOf(
                        "Turn the camera's Wi-Fi off and close Canon Camera Connect.",
                        "Unplug, switch the camera off and on, and plug it in again.",
                        "Try another cable. Some cables only charge.",
                    ),
                )
                Button(onClick = { vm.readCanon(allowAdvanced = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Read camera again")
                }
            }

            is UsbState.Done -> {
                val r = s.reading
                val count = r.shutterCount
                val experimental = r.method == CanonMethod.FACTORY && !r.confirmed
                InstrumentPanel(
                    title = r.model,
                    trailing = r.serial?.let { "S/N $it" },
                    count = count,
                    caption = if (count != null) "shutter actuations" else "No count yet",
                    ratedLife = r.ratedLife,
                    badge = if (experimental) "Experimental reading" else null,
                    showWear = showWear,
                )

                if (count == null) {
                    Note(r.problem ?: "The camera didn't return a count.", warning = !r.canTryAdvanced)
                    if (r.canTryAdvanced) {
                        Button(onClick = { confirmAdvanced = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("Try advanced read")
                        }
                    }
                }
                r.notes.forEach { Note(it, warning = experimental) }

                val rows = buildList {
                    if (count != null) previousLine(count, s.previous)?.let { add(it) }
                    r.counters.drop(1).forEach { add(it.label to formatCount(it.value)) }
                    r.method?.let { add("Method" to it.label) }
                    r.firmware?.let { add("Firmware" to it) }
                }
                DetailRows(rows)

                if (count != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                shareText(context, buildReport(r.model, r.serial, count, r.ratedLife.takeIf { reportWear }, "USB (lecture directe)", experimental = experimental))
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Share report") }
                        OutlinedButton(onClick = onAddNote, modifier = Modifier.weight(1f).height(48.dp)) { Text("Add note") }
                    }
                    TextButton(onClick = { vm.readCanon(allowAdvanced = r.method == CanonMethod.FACTORY) }) { Text("Read again") }
                }

                val raw = r.counters.mapNotNull { ct -> ct.raw?.let { "${ct.label}\n$it" } }
                if (raw.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Raw data from the camera", style = MaterialTheme.typography.titleSmall, color = c.muted)
                        Text(raw.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                if (r.diagnostics.isNotBlank()) {
                    TextButton(onClick = {
                        shareText(context, "Shutter Count diagnostic report\n${r.model}\n\n${r.diagnostics}")
                    }) { Text("Share diagnostic report") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmAdvanced) {
        AlertDialog(
            onDismissRequest = { confirmAdvanced = false },
            title = { Text("Advanced read") },
            text = {
                Text(
                    "This asks the camera's shutter unit for its counters. The commands only read, but a camera can still stop with Err 70 if it doesn't handle them well.\n\n" +
                        "It's confirmed on the EOS 5D Mark IV. On other models, check it: read, take exactly 5 photos, read again, and the count should go up by 5.\n\n" +
                        "If that happens, take the battery out for 30 seconds and put it back. Only use this on cameras you can afford to restart.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAdvanced = false; vm.readCanon(allowAdvanced = true) }) { Text("Read") }
            },
            dismissButton = { TextButton(onClick = { confirmAdvanced = false }) { Text("Cancel") } },
        )
    }
}
