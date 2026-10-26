package com.techmare.shuttercount.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.techmare.shuttercount.MainViewModel

enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    PHOTO("Photo", "Shutter Count", Icons.Outlined.PhotoCamera),
    USB("USB", "Canon over USB", Icons.Outlined.Usb),
    HISTORY("History", "History", Icons.Outlined.History),
}

@Composable
fun App(vm: MainViewModel, tab: Tab, onTab: (Tab) -> Unit) {
    val c = LocalAppColors.current
    var noteFor by remember { mutableStateOf<Long?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val addNote = { noteFor = vm.latestEntryId() }

    Scaffold(
        containerColor = c.paper,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (settingsOpen) {
                    IconButton(onClick = { settingsOpen = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = c.ink) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (settingsOpen) "Settings" else tab.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium, color = c.ink,
                )
                if (!settingsOpen) {
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Settings, "Settings", tint = c.muted) }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && !settingsOpen,
                        onClick = { settingsOpen = false; onTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = c.signal,
                            selectedIconColor = Color(0xFF1C1F23),
                            selectedTextColor = c.ink,
                            unselectedIconColor = c.muted,
                            unselectedTextColor = c.muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column {
                if (settingsOpen) SettingsScreen(vm) else when (tab) {
                    Tab.PHOTO -> PhotoScreen(vm, addNote)
                    Tab.USB -> UsbScreen(vm, addNote)
                    Tab.HISTORY -> HistoryScreen(vm)
                }
            }
        }
    }

    noteFor?.let { id ->
        val initial = vm.history.value.firstOrNull { it.id == id }?.note.orEmpty()
        NoteDialog(initial = initial, onDismiss = { noteFor = null }, onSave = { vm.updateNote(id, it); noteFor = null })
    }
}
