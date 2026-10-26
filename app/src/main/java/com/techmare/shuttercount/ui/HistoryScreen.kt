package com.techmare.shuttercount.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.data.ReportData
import com.techmare.shuttercount.data.shareReport
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.data.formatDate
import com.techmare.shuttercount.data.wearPercent

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val entries by vm.history.collectAsState()
    val context = LocalContext.current
    val c = LocalAppColors.current
    val showWear by vm.showWear.collectAsState()
    val reportWear by vm.reportWear.collectAsState()
    var editing by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No checks yet", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Each camera you read is saved here with its date. Add a note to keep the customer, price or condition with it.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                HistoryRow(
                    e, showWear,
                    onShare = {
                        shareReport(context, ReportData(
                            e.camera, e.serial, e.count, e.ratedLife.takeIf { reportWear },
                            if (e.source == "USB") "USB (lecture directe)" else "Photo (données EXIF)", e.time, e.experimental,
                            includesElectronic = e.source != "USB" && e.camera.startsWith("Canon"),
                        ))
                    },
                    onNote = { editing = e },
                    onDelete = { deleting = e },
                )
                if (i < entries.lastIndex) HorizontalDivider(color = c.rule)
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
            text = { Text("${e.camera}, ${formatCount(e.count)} shots on ${formatDate(e.time)}") },
            confirmButton = { TextButton(onClick = { vm.deleteEntry(e.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, showWear: Boolean, onShare: () -> Unit, onNote: () -> Unit, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onNote).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(e.camera, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(formatCount(e.count), style = MaterialTheme.typography.titleLarge, color = c.ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatDate(e.time)}, from ${if (e.source == "USB") "USB" else "photo"}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                val rated = e.ratedLife
                if (rated != null && showWear) {
                    val wear = CameraModels.wear(e.count, rated)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(wearColor(wear)))
                    Spacer(Modifier.width(6.dp))
                    Text("${wear.label}, ${wearPercent(e.count, rated)}%", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            if (e.experimental) Text("Experimental reading", style = MaterialTheme.typography.bodySmall, color = c.mid)
            if (e.serial != null) Text("S/N ${e.serial}", style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (e.note.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(e.note, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = c.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Share report") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(if (e.note.isBlank()) "Add note" else "Edit note") }, onClick = { menu = false; onNote() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
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
                placeholder = { Text("Customer, price, condition") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
