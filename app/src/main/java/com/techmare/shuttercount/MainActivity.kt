package com.techmare.shuttercount

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.techmare.shuttercount.ui.App
import com.techmare.shuttercount.ui.LaunchReveal
import com.techmare.shuttercount.ui.LocalLaunching
import com.techmare.shuttercount.ui.ShutterTheme
import com.techmare.shuttercount.ui.Tab

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var tab by mutableStateOf(Tab.PHOTO)
    private var launching by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        if (savedInstanceState == null) handleIntent(intent)
        // The opening animation plays on a fresh start from the launcher only: not after a rotation,
        // and not when a shared photo or a plugged-in camera opened the app.
        launching = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN && intent?.hasExtra("check") != true
        // Take the system splash away in one step instead of letting it fade: LaunchReveal's first
        // frame is the same picture, so the aperture stays put and then starts to move.
        if (launching && Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        setContent {
            ShutterTheme {
                CompositionLocalProvider(LocalLaunching provides launching) {
                    LaunchReveal(active = launching, onDone = { launching = false }) {
                        App(vm, tab) { tab = it }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshUsb()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        ScreenCheck.handle(intent, vm)?.let { tab = it; return }
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                tab = Tab.USB
                vm.refreshUsb(keepResult = false)
            }
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    tab = Tab.PHOTO
                    vm.readPhoto(uri)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let {
                    tab = Tab.PHOTO
                    vm.readPhoto(it)
                }
            }
        }
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
