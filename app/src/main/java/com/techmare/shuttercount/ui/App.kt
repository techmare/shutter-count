package com.techmare.shuttercount.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.techmare.shuttercount.MainViewModel

enum class Tab(val label: String, val icon: ImageVector) {
    PHOTO("Photo", Icons.Outlined.PhotoCamera),
    USB("USB (Canon)", Icons.Outlined.Usb),
    HISTORY("History", Icons.Outlined.History),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel, tab: Tab, onTab: (Tab) -> Unit) {
    var noteFor by remember { mutableStateOf<Long?>(null) }
    val addNote = { noteFor = vm.latestEntryId() }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Shutter Count") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.PHOTO -> PhotoScreen(vm, addNote)
                Tab.USB -> UsbScreen(vm, addNote)
                Tab.HISTORY -> HistoryScreen(vm)
            }
        }
    }

    noteFor?.let { id ->
        val initial = vm.history.value.firstOrNull { it.id == id }?.note.orEmpty()
        NoteDialog(initial = initial, onDismiss = { noteFor = null }, onSave = { vm.updateNote(id, it); noteFor = null })
    }
}
