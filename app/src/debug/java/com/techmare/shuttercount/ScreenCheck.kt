package com.techmare.shuttercount

import android.content.Intent
import com.techmare.shuttercount.data.HistoryEntry
import com.techmare.shuttercount.ptp.CanonCounter
import com.techmare.shuttercount.ptp.CanonMethod
import com.techmare.shuttercount.ptp.CanonReading
import com.techmare.shuttercount.ui.Tab
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: `adb shell am start -n com.techmare.shuttercount/.MainActivity --es check <state>`
 * puts the app in one state with made-up data, so the emulator workflow can screenshot every
 * state in light and dark. Made-up serials only, never a real customer's camera.
 */
object ScreenCheck {
    fun handle(intent: Intent, vm: MainViewModel): Tab? {
        val state = intent.getStringExtra("check") ?: return null
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        return when (state) {
            "photo-idle" -> { vm.clearPhoto(); Tab.PHOTO }
            "photo-ok" -> { vm.replaceHistory(emptyList()); vm.showPhotoBytes(canon("Canon EOS R50", 0x0d29, 12_345, 123456789), "IMG_0412.JPG"); Tab.PHOTO }
            "photo-seen" -> {
                vm.replaceHistory(listOf(HistoryEntry(1, now - 40 * day, "Canon EOS R50", "123456789", 11_980, 100_000, "Photo", note = "Bought from Karim, small scratch")))
                vm.showPhotoBytes(canon("Canon EOS R50", 0x0d29, 12_345, 123456789), "IMG_0412.JPG"); Tab.PHOTO
            }
            "photo-high" -> { vm.replaceHistory(emptyList()); vm.showPhotoBytes(canon("Canon EOS R6", 0x0af1, 287_400, 987654321), "IMG_7731.JPG"); Tab.PHOTO }
            "photo-fail" -> { vm.showPhotoBytes(ByteArray(64), "Screenshot_2026.png"); Tab.PHOTO }
            "usb-idle" -> { vm.showUsbState(UsbState.NoCamera); Tab.USB }
            "usb-ok" -> {
                vm.replaceHistory(emptyList())
                vm.showUsbState(UsbState.Done(CanonReading(
                    "Canon EOS 4000D", "283032001879", "1.0.0", listOf(CanonCounter("Shutter actuations", 535)), CanonMethod.EVENT, true,
                    100_000, emptyList(), null, false, diagnostics = "GetDevicePropValue D1AC: 2005\nGetEvent #1: 11569 bytes",
                ), null)); Tab.USB
            }
            "usb-fail" -> {
                vm.showUsbState(UsbState.Done(CanonReading(
                    "Canon EOS 77D", null, "1.1.0", emptyList(), null, true, 100_000, emptyList(),
                    "This Canon doesn't offer a way to read its shutter count over USB that this app can use safely.", false,
                    diagnostics = "Operations (154): 1001 1002 1003 …\nGetDevicePropValue D1AC: 2005, 0 bytes",
                ), null)); Tab.USB
            }
            "usb-error" -> { vm.showUsbState(UsbState.Error("USB access was not allowed. Tap Read again and choose Allow.")); Tab.USB }
            "history" -> {
                vm.replaceHistory(listOf(
                    HistoryEntry(5, now - 2 * 3_600_000, "Canon EOS R50", "123456789", 12_345, 100_000, "Photo", note = "Customer: Youssef, 6 500 dh"),
                    HistoryEntry(4, now - day, "Sony α7 III", null, 48_633, 200_000, "Photo"),
                    HistoryEntry(3, now - 3 * day, "Canon EOS 4000D", "283032001879", 535, 100_000, "USB"),
                    HistoryEntry(2, now - 9 * day, "Nikon D750", "6021455", 151_204, 150_000, "Photo", ratedOfficial = true, note = "Shutter replaced?"),
                    HistoryEntry(1, now - 30 * day, "Canon EOS 5D Mark IV with a very long name that needs to wrap", "012345678901", 233_870, 150_000, "Photo", ratedOfficial = true),
                )); Tab.HISTORY
            }
            "history-empty" -> { vm.replaceHistory(emptyList()); Tab.HISTORY }
            else -> null
        }
    }

    /** Minimal Canon R TIFF: make, model, serial and the counter in CameraInfo at [offset]. */
    private fun canon(model: String, offset: Int, count: Int, serial: Int): ByteArray {
        val bb = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(tag: Int, type: Int, n: Int, v: Int) { bb.putShort(tag.toShort()); bb.putShort(type.toShort()); bb.putInt(n); bb.putInt(v) }
        bb.put("II".toByteArray()); bb.putShort(42); bb.putInt(8)
        bb.position(8); bb.putShort(3)
        entry(0x10F, 2, 6, 200); entry(0x110, 2, model.length + 1, 220); entry(0x8769, 4, 1, 300); bb.putInt(0)
        bb.position(200); bb.put("Canon\u0000".toByteArray())
        bb.position(220); bb.put((model + "\u0000").toByteArray())
        bb.position(300); bb.putShort(1); entry(0x927C, 7, 30, 400); bb.putInt(0)
        bb.position(400); bb.putShort(2); entry(0x000C, 4, 1, serial); entry(0x000D, 7, offset + 64, 1000); bb.putInt(0)
        bb.position(1000 + offset); bb.putInt(count)
        return bb.array()
    }
}
