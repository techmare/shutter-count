package com.techmare.shuttercount.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel
import com.techmare.shuttercount.PhotoState
import com.techmare.shuttercount.data.buildReport

@Composable
fun PhotoScreen(vm: MainViewModel, onAddNote: () -> Unit) {
    val state by vm.photo.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readPhoto(uri)
    }
    val pick = { picker.launch(arrayOf("image/*", "application/octet-stream")) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = pick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.Image, null)
            Spacer(Modifier.width(8.dp))
            Text("Choose a photo (Nikon or Sony)")
        }

        when (val s = state) {
            PhotoState.Idle -> {
                StepsCard(
                    "How to check",
                    listOf(
                        "Take a new photo with the camera (JPEG or RAW). Use the mechanical shutter, not silent mode.",
                        "Copy the original file to this phone: card reader / OTG, or the camera's app set to \"Original\" size.",
                        "Tap the button above and pick the file.",
                    ),
                )
                NoticeCard(
                    "Don't use photos sent by WhatsApp, Messenger or edited apps — they remove the data this app needs.",
                    warning = false,
                )
                NoticeCard("Canon? Canon doesn't store the count in photos. Use the USB tab.", warning = false)
            }
            PhotoState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is PhotoState.Done -> {
                val r = s.reading
                val count = r.shutterCount
                if (count != null) {
                    CountCard(r.displayName, count, r.ratedLife, s.previous)
                } else {
                    NoticeCard(r.problem ?: "No shutter count found in this file.", warning = true)
                }
                r.warnings.forEach { NoticeCard(it, warning = true) }

                val rows = buildList {
                    s.fileName?.let { add("File" to it) }
                    if (r.make != null) add("Camera" to listOfNotNull(r.make, r.model).joinToString(" "))
                    r.serial?.let { add("Serial no." to it) }
                    r.dateTaken?.let { add("Photo taken" to it) }
                    r.software?.let { add("Software" to it) }
                    addAll(r.details)
                }
                DetailsCard(rows)
                if (count != null && r.dateTaken != null) {
                    NoticeCard("The count is from when this photo was taken (${r.dateTaken}). For the current count, use a photo taken just now.", warning = false)
                }

                if (count != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { shareText(context, buildReport(r.displayName, r.serial, count, r.ratedLife, "photo (EXIF)")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share report")
                        }
                        OutlinedButton(onClick = onAddNote, modifier = Modifier.weight(1f)) { Text("Add note") }
                    }
                }
                OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Check another photo") }
            }
        }
    }
}
