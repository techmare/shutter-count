package com.techmare.shuttercount.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.height
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
import com.techmare.shuttercount.data.Midox
import com.techmare.shuttercount.data.shareReport
import com.techmare.shuttercount.data.formatCount
import com.techmare.shuttercount.data.formatDate
import com.techmare.shuttercount.data.wearPercent

@Composable
fun HistoryScreen(vm: MainViewModel, onCheck: () -> Unit) {
    val entries by vm.history.collectAsState()
    val context = LocalContext.current
    val c = LocalAppColors.current
    val showWear by vm.showWear.collectAsState()
    val reportWear by vm.reportWear.collectAsState()
    var editing by remember { mutableStateOf<HistoryEntry?>(null) }
    var deleting by remember { mutableStateOf<HistoryEntry?>(null) }
    val midox = remember { Midox.installed(context) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No checks yet", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Each camera you read is saved here with its date. Add a note to keep the customer, price or condition with it.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
                Spacer(Modifier.size(20.dp))
                androidx.compose.material3.Button(onClick = onCheck, modifier = Modifier.height(48.dp)) { Text("Check a photo") }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                HistoryRow(
                    e, showWear,
                    onShare = { shareReport(context, reportFor(e, reportWear)) },
                    onOpen = { vm.openCheck(e.id) },
                    onNote = { editing = e },
                    onMidox = if (midox) ({ Midox.addSerial(context, e.camera, e.serial, e.count.toLong()) }) else null,
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
            confirmButton = { QuietButton(onClick = { vm.deleteEntry(e.id); deleting = null }) { Text("Delete") } },
            dismissButton = { QuietButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, showWear: Boolean, onShare: () -> Unit, onOpen: () -> Unit, onNote: () -> Unit, onMidox: (() -> Unit)?, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(e.camera, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(formatCount(e.count), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.ink)
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
                if (onMidox != null) DropdownMenuItem(text = { Text("Add to Midox") }, onClick = { menu = false; onMidox() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** The customer report for a saved check. */
private fun reportFor(e: HistoryEntry, reportWear: Boolean) = ReportData(
    e.camera, e.serial, e.count, e.ratedLife.takeIf { reportWear },
    if (e.source == "USB") "USB (lecture directe)" else "Photo (données EXIF)", e.time, e.experimental,
    includesElectronic = e.source != "USB" && e.camera.startsWith("Canon"),
    ratedOfficial = e.ratedOfficial,
)

/**
 * One saved check in full: the same panel as a fresh reading, its note (add or edit right
 * here), its other checks of the same body, and the actions from the row's menu.
 */
@Composable
fun CheckScreen(vm: MainViewModel, id: Long) {
    val entries by vm.history.collectAsState()
    val e = entries.firstOrNull { it.id == id } ?: return
    val context = LocalContext.current
    val c = LocalAppColors.current
    val showWear by vm.showWear.collectAsState()
    val reportWear by vm.reportWear.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val midox = remember { Midox.installed(context) }
    val others = remember(entries, e) { vm.otherChecks(e) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InstrumentPanel(
            title = e.camera,
            trailing = e.serial?.let { "S/N $it" },
            count = e.count,
            caption = "shutter actuations on ${formatDate(e.time)}",
            ratedLife = e.ratedLife,
            ratedOfficial = e.ratedOfficial,
            badge = if (e.experimental) "Experimental reading" else null,
            showWear = showWear,
            roll = false,
        )

        // the note, editable in place
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { editing = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Note", style = MaterialTheme.typography.titleSmall, color = c.muted)
            Text(
                e.note.ifBlank { "Add the customer, price or condition…" },
                style = MaterialTheme.typography.bodyLarge, color = if (e.note.isBlank()) c.muted else c.ink,
            )
            Text(if (e.note.isBlank()) "Tap to add" else "Tap to edit", style = MaterialTheme.typography.labelMedium, color = c.muted)
        }

        DetailRows(buildList {
            add("Checked" to formatDate(e.time))
            add("From" to if (e.source == "USB") "USB cable" else "Photo")
            e.ratedLife?.let { add((if (e.ratedOfficial) "Rated life" else "Estimated life") to formatCount(it.toLong())) }
        })

        if (others.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Other checks of this camera", style = MaterialTheme.typography.titleMedium, color = c.ink)
                others.forEach { o ->
                    val diff = e.count - o.count
                    Row(Modifier.fillMaxWidth().clickable { vm.openCheck(o.id) }.padding(vertical = 6.dp)) {
                        Text(formatDate(o.time), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        Text(formatCount(o.count), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.ink)
                        Text(
                            when { diff > 0 -> "  (+${formatCount(diff)})"; diff < 0 -> "  (−${formatCount(-diff)})"; else -> "  (same)" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = if (diff < 0) c.high else c.muted,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Button(
                onClick = { shareReport(context, reportFor(e, reportWear)) },
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Share report") }
            GhostButton(onClick = { editing = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(if (e.note.isBlank()) "Add note" else "Edit note")
            }
        }
        if (midox) {
            GhostButton(onClick = { Midox.addSerial(context, e.camera, e.serial, e.count) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Add to Midox serial numbers")
            }
        }
        QuietButton(onClick = { deleting = true }) { Text("Delete this check", color = c.high) }
        Spacer(Modifier.height(8.dp))
    }

    if (editing) NoteDialog(initial = e.note, onDismiss = { editing = false }, onSave = { vm.updateNote(e.id, it); editing = false })
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this check?") },
            text = { Text("${e.camera}, ${formatCount(e.count)} shots on ${formatDate(e.time)}") },
            confirmButton = { QuietButton(onClick = { deleting = false; vm.deleteEntry(e.id) }) { Text("Delete") } },
            dismissButton = { QuietButton(onClick = { deleting = false }) { Text("Cancel") } },
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
                placeholder = { Text("Customer, price, condition") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
        },
        confirmButton = { QuietButton(onClick = { onSave(text.trim()) }) { Text("Save note") } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
