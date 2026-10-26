package com.techmare.shuttercount

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techmare.shuttercount.core.PhotoReading
import com.techmare.shuttercount.core.PhotoShutterReader
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.data.HistoryStore
import com.techmare.shuttercount.ptp.CanonMethod
import com.techmare.shuttercount.ptp.CanonReader
import com.techmare.shuttercount.ptp.CanonReading
import com.techmare.shuttercount.ptp.PtpSession
import com.techmare.shuttercount.usb.UsbCameraConnection
import com.techmare.shuttercount.usb.cameraName
import com.techmare.shuttercount.usb.findCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PhotoState {
    data object Idle : PhotoState
    data object Loading : PhotoState
    data class Done(val reading: PhotoReading, val fileName: String?, val previous: HistoryEntry?) : PhotoState
}

sealed interface UsbState {
    data object NoCamera : UsbState
    data class Connected(val name: String) : UsbState
    data class Reading(val name: String) : UsbState
    data class Done(val reading: CanonReading, val previous: HistoryEntry?) : UsbState
    data class Error(val message: String) : UsbState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val store = HistoryStore(app)

    private val _photo = MutableStateFlow<PhotoState>(PhotoState.Idle)
    val photo: StateFlow<PhotoState> = _photo.asStateFlow()

    private val _usb = MutableStateFlow<UsbState>(UsbState.NoCamera)
    val usb: StateFlow<UsbState> = _usb.asStateFlow()

    private val _history = MutableStateFlow(store.load())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private var pendingAdvanced = false

    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _showWear = MutableStateFlow(settings.getBoolean(KEY_SHOW_WEAR, true))
    /** Show rated shutter life and wear in the app. */
    val showWear: StateFlow<Boolean> = _showWear.asStateFlow()

    private val _reportWear = MutableStateFlow(settings.getBoolean(KEY_REPORT_WEAR, true))
    /** Include rated shutter life and wear in shared reports. */
    val reportWear: StateFlow<Boolean> = _reportWear.asStateFlow()

    fun setShowWear(on: Boolean) {
        _showWear.value = on
        settings.edit().putBoolean(KEY_SHOW_WEAR, on).apply()
    }

    fun setReportWear(on: Boolean) {
        _reportWear.value = on
        settings.edit().putBoolean(KEY_REPORT_WEAR, on).apply()
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) readCanon(pendingAdvanced)
            else _usb.value = UsbState.Error("USB access was not allowed. Tap Read again and choose Allow.")
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_usb.value !is UsbState.Reading) refreshUsb()
        }
    }

    init {
        ContextCompat.registerReceiver(app, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
        refreshUsb()
    }

    override fun onCleared() {
        val app = getApplication<Application>()
        try { app.unregisterReceiver(permissionReceiver) } catch (_: Exception) { }
        try { app.unregisterReceiver(detachReceiver) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------- photo

    fun readPhoto(uri: Uri) {
        _photo.value = PhotoState.Loading
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (total < MAX_PHOTO_BYTES) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    PhotoShutterReader.read(bytes)
                } catch (e: Exception) {
                    null
                }
            }
            val name = fileName(uri)
            if (result == null) {
                _photo.value = PhotoState.Done(
                    PhotoShutterReader.read(ByteArray(0)).copy(problem = "Couldn't open this file. Try copying it to the phone first."),
                    name, null,
                )
                return@launch
            }
            var previous: HistoryEntry? = null
            if (result.shutterCount != null) {
                previous = previousFor(result.serial, result.displayName)
                addHistory(HistoryEntry(
                    id = System.nanoTime(), time = System.currentTimeMillis(), camera = result.displayName,
                    serial = result.serial, count = result.shutterCount, ratedLife = result.ratedLife, ratedOfficial = result.ratedOfficial, source = "Photo",
                ))
            }
            _photo.value = PhotoState.Done(result, name, previous)
        }
    }

    fun clearPhoto() { _photo.value = PhotoState.Idle }

    private fun fileName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    // --------------------------------------------------------------- USB

    fun refreshUsb() {
        if (_usb.value is UsbState.Reading) return
        val device = findCamera(usbManager)
        _usb.value = if (device == null) UsbState.NoCamera else UsbState.Connected(cameraName(device))
    }

    fun readCanon(allowAdvanced: Boolean) {
        val device = findCamera(usbManager)
        if (device == null) {
            _usb.value = UsbState.NoCamera
            return
        }
        if (!usbManager.hasPermission(device)) {
            pendingAdvanced = allowAdvanced
            requestPermission(device)
            return
        }
        val name = cameraName(device)
        _usb.value = UsbState.Reading(name)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    UsbCameraConnection.open(usbManager, device).use { conn ->
                        Result.success(CanonReader(PtpSession(conn)).read(device.productName, allowAdvanced))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            outcome.fold(
                onSuccess = { reading ->
                    var previous: HistoryEntry? = null
                    val count = reading.shutterCount
                    if (count != null) {
                        previous = previousFor(reading.serial, reading.model)
                        addHistory(HistoryEntry(
                            id = System.nanoTime(), time = System.currentTimeMillis(), camera = reading.model,
                            serial = reading.serial, count = count, ratedLife = reading.ratedLife, ratedOfficial = reading.ratedOfficial, source = "USB",
                            experimental = reading.method == CanonMethod.FACTORY && !reading.confirmed,
                        ))
                    }
                    _usb.value = UsbState.Done(reading, previous)
                },
                onFailure = { e ->
                    _usb.value = UsbState.Error(e.message ?: "Couldn't read the camera. Check the cable, turn the camera's Wi-Fi off, and try again.")
                },
            )
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val app = getApplication<Application>()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))
    }

    // ----------------------------------------------------------- history

    private fun previousFor(serial: String?, camera: String): HistoryEntry? =
        _history.value.firstOrNull { e ->
            if (!serial.isNullOrBlank()) e.serial == serial else e.camera == camera && e.serial.isNullOrBlank()
        }

    private fun addHistory(entry: HistoryEntry) {
        val list = listOf(entry) + _history.value
        _history.value = list
        store.save(list)
    }

    fun updateNote(id: Long, note: String) {
        val list = _history.value.map { if (it.id == id) it.copy(note = note) else it }
        _history.value = list
        store.save(list)
    }

    fun deleteEntry(id: Long) {
        val list = _history.value.filterNot { it.id == id }
        _history.value = list
        store.save(list)
    }

    /** Most recent entry for the reading currently on screen, so a note can be attached. */
    fun latestEntryId(): Long? = _history.value.firstOrNull()?.id

    companion object {
        private const val KEY_SHOW_WEAR = "show_wear"
        private const val KEY_REPORT_WEAR = "report_wear"
        const val ACTION_USB_PERMISSION = "com.techmare.shuttercount.USB_PERMISSION"
        private const val MAX_PHOTO_BYTES = 12 * 1024 * 1024
    }
}
