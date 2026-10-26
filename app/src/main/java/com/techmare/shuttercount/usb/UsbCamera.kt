package com.techmare.shuttercount.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.techmare.shuttercount.ptp.PtpException
import com.techmare.shuttercount.ptp.PtpTransport

const val CANON_VENDOR_ID = 0x04A9
const val NIKON_VENDOR_ID = 0x04B0
const val SONY_VENDOR_ID = 0x054C
private val CAMERA_VENDORS = setOf(CANON_VENDOR_ID, NIKON_VENDOR_ID, SONY_VENDOR_ID)

/** Finds a connected camera: a Canon device, or anything exposing a still-image (PTP) interface. */
fun findCamera(manager: UsbManager): UsbDevice? {
    val devices = manager.deviceList.values
    return devices.firstOrNull { it.vendorId in CAMERA_VENDORS }
        ?: devices.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } }
}

fun cameraName(device: UsbDevice): String =
    device.productName?.takeIf { it.isNotBlank() }
        ?: when (device.vendorId) {
            CANON_VENDOR_ID -> "Canon camera"
            NIKON_VENDOR_ID -> "Nikon camera"
            SONY_VENDOR_ID -> "Sony camera"
            else -> "USB camera"
        }

/** An opened, claimed PTP interface. Close it when done. */
class UsbCameraConnection private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : PtpTransport, AutoCloseable {

    override fun write(data: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(outEp, data, data.size, timeoutMs)

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(inEp, buf, buf.size, timeoutMs)

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) { }
        connection.close()
    }

    companion object {
        fun open(manager: UsbManager, device: UsbDevice): UsbCameraConnection {
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
                .let { list -> list.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } ?: list.firstOrNull { hasBulkPair(it) } }
                ?: throw PtpException("The camera isn't in a transfer mode the app can use. On Sony, set USB Connection to MTP in the menu, then plug it in again.")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            if (inEp == null || outEp == null) throw PtpException("This USB device has no camera interface.")
            val conn = manager.openDevice(device) ?: throw PtpException("Couldn't open the camera. Unplug it, plug it back in and allow access.")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw PtpException("Another app is using the camera. Close other camera apps and try again.")
            }
            return UsbCameraConnection(conn, iface, inEp, outEp)
        }

        private fun hasBulkPair(iface: UsbInterface): Boolean {
            var hasIn = false; var hasOut = false
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
            }
            return hasIn && hasOut
        }
    }
}
