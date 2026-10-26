package com.techmare.shuttercount.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.data.buildReport
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.data.formatDate
import com.techmare.shuttercount.data.wearPercent

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val entries by vm.history.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Every camera you check is saved here, with the date and any note you add (customer, price, condition…).",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().clickable { editing = e }, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.camera, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${formatCount(e.count)} shots" + (e.ratedLife?.let { r ->
                                        " · ${wearPercent(e.count, r)}% · ${CameraModels.wear(e.count, r).label}"
                                    } ?: "") + if (e.experimental) " · experimental" else "",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    listOfNotNull(formatDate(e.time), e.source, e.serial?.let { "S/N $it" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = {
                                shareText(context, buildReport(e.camera, e.serial, e.count, e.ratedLife,
                                    if (e.source == "USB") "USB (lecture directe)" else "photo (EXIF)", e.time, e.experimental))
                            }) { Icon(Icons.Outlined.Share, "Share") }
                            IconButton(onClick = { editing = e }) { Icon(Icons.Outlined.Edit, "Note") }
                            IconButton(onClick = { deleting = e }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        NoteDialog(initial = e.note, onDismiss = { editing = null }, onSave = { vm.updateNote(e.id, it); editing = null })
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this check?") },
            text = { Text("${e.camera} · ${formatCount(e.count)} shots") },
            confirmButton = { TextButton(onClick = { vm.deleteEntry(e.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Customer, price, condition…") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
