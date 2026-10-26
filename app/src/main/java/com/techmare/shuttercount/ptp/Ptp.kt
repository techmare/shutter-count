package com.techmare.shuttercount.ptp

import java.io.IOException

/** Raw bulk pipe to a camera. Implemented over Android USB host, or a fake in tests. */
interface PtpTransport {
    /** Writes [data]; returns bytes written or a negative number on failure. */
    fun write(data: ByteArray, timeoutMs: Int): Int
    /** Reads into [buf]; returns bytes read (0 for a zero-length packet) or negative on failure. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
}

class PtpException(message: String) : IOException(message)

data class PtpResponse(val code: Int, val params: List<Long>, val data: ByteArray) {
    val ok get() = code == RC_OK
}

data class DeviceInfo(
    val vendorExtensionId: Long,
    val operations: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
)

const val RC_OK = 0x2001
const val RC_SESSION_ALREADY_OPEN = 0x201E

const val OP_GET_DEVICE_INFO = 0x1001
const val OP_OPEN_SESSION = 0x1002
const val OP_CLOSE_SESSION = 0x1003
const val OP_GET_DEVICE_PROP_VALUE = 0x1015

private const val TYPE_COMMAND = 1
private const val TYPE_DATA = 2
private const val TYPE_RESPONSE = 3
private const val TYPE_EVENT = 4
private const val HEADER = 12

/**
 * Just enough PTP (PIMA 15740) over USB bulk to run a few read-only operations.
 */
class PtpSession(
    private val transport: PtpTransport,
    private val readChunk: Int = 16384,
    private val timeoutMs: Int = 15000,
) {
    private var transactionId = 0L
    private var pending = ByteArray(0)
    private var sessionOpen = false

    fun openSession() {
        val r = transact(OP_OPEN_SESSION, listOf(1L))
        if (!r.ok && r.code != RC_SESSION_ALREADY_OPEN) throw PtpException("Camera refused the connection (${codeName(r.code)}).")
        sessionOpen = true
    }

    fun closeSession() {
        if (!sessionOpen) return
        sessionOpen = false
        try { transact(OP_CLOSE_SESSION, emptyList()) } catch (_: Exception) { }
    }

    fun getDeviceInfo(): DeviceInfo {
        val r = transact(OP_GET_DEVICE_INFO, emptyList())
        if (!r.ok) throw PtpException("Camera did not describe itself (${codeName(r.code)}).")
        return parseDeviceInfo(r.data)
    }

    /** Operation whose data phase (if any) comes from the camera. */
    fun transact(op: Int, params: List<Long>): PtpResponse = run(op, params, null)

    /** Operation with a data phase sent to the camera. */
    fun transactOut(op: Int, params: List<Long>, data: ByteArray): PtpResponse = run(op, params, data)

    private fun run(op: Int, params: List<Long>, out: ByteArray?): PtpResponse {
        transactionId++
        val tid = transactionId
        send(container(TYPE_COMMAND, op, tid, packParams(params)))
        if (out != null) send(container(TYPE_DATA, op, tid, out))

        var data = ByteArray(0)
        while (true) {
            val (type, code, payload) = readContainer()
            when (type) {
                TYPE_DATA -> data += payload
                TYPE_RESPONSE -> {
                    val ps = ArrayList<Long>()
                    var i = 0
                    while (i + 4 <= payload.size) { ps += le32(payload, i); i += 4 }
                    return PtpResponse(code, ps, data)
                }
                TYPE_EVENT -> { /* interleaved async event; ignore */ }
                else -> throw PtpException("Unexpected reply from camera (type $type).")
            }
        }
    }

    private fun send(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val chunk = if (sent == 0) bytes else bytes.copyOfRange(sent, bytes.size)
            val n = transport.write(chunk, timeoutMs)
            if (n <= 0) throw PtpException("Couldn't send to the camera. Check the cable and that the camera is on.")
            sent += n
        }
    }

    private fun fill(n: Int): ByteArray {
        val buf = ByteArray(readChunk)
        var empty = 0
        while (pending.size < n) {
            val r = transport.read(buf, timeoutMs)
            if (r < 0) throw PtpException("The camera stopped responding. Check the cable, and turn the camera's Wi-Fi off.")
            if (r == 0) {
                if (++empty > 8) throw PtpException("The camera stopped responding.")
                continue
            }
            empty = 0
            pending += buf.copyOfRange(0, r)
        }
        return pending
    }

    private fun readContainer(): Triple<Int, Int, ByteArray> {
        fill(HEADER)
        val length = le32(pending, 0).toInt()
        if (length < HEADER || length > 64 * 1024 * 1024) throw PtpException("Garbled reply from the camera.")
        val type = le16(pending, 4)
        val code = le16(pending, 6)
        fill(length)
        val payload = pending.copyOfRange(HEADER, length)
        pending = pending.copyOfRange(length, pending.size)
        return Triple(type, code, payload)
    }

    companion object {
        fun container(type: Int, code: Int, tid: Long, payload: ByteArray): ByteArray {
            val len = HEADER + payload.size
            val out = ByteArray(len)
            putLe32(out, 0, len.toLong())
            putLe16(out, 4, type)
            putLe16(out, 6, code)
            putLe32(out, 8, tid)
            payload.copyInto(out, HEADER)
            return out
        }

        fun packParams(params: List<Long>): ByteArray {
            val out = ByteArray(params.size * 4)
            params.forEachIndexed { i, p -> putLe32(out, i * 4, p) }
            return out
        }

        fun parseDeviceInfo(d: ByteArray): DeviceInfo {
            val r = Reader(d)
            r.u16() // standard version
            val vendorExt = r.u32()
            r.u16() // vendor ext version
            r.str() // vendor ext desc
            r.u16() // functional mode
            val ops = r.arr16().toSet()
            r.arr16() // events
            val props = r.arr16().toSet()
            r.arr16() // capture formats
            r.arr16() // image formats
            val manufacturer = r.str()
            val model = r.str()
            val version = r.str()
            val serial = r.str()
            return DeviceInfo(vendorExt, ops, props, manufacturer, model, version, serial)
        }

        fun codeName(code: Int): String = when (code) {
            0x2001 -> "OK"
            0x2002 -> "general error"
            0x2003 -> "session not open"
            0x2005 -> "not supported"
            0x2006 -> "parameter not supported"
            0x200A -> "property not supported"
            0x2019 -> "camera busy"
            0x201D -> "invalid parameter"
            else -> "code 0x%04X".format(code)
        }
    }

    /** Sequential little-endian reader for PTP datasets. Short data reads as zero/empty. */
    class Reader(private val d: ByteArray) {
        var pos = 0
        fun u8(): Int = if (pos < d.size) d[pos++].toInt() and 0xff else { pos++; 0 }
        fun u16(): Int { val v = if (pos + 2 <= d.size) le16(d, pos) else 0; pos += 2; return v }
        fun u32(): Long { val v = if (pos + 4 <= d.size) le32(d, pos) else 0; pos += 4; return v }
        fun arr16(): List<Int> {
            val n = u32()
            if (n < 0 || n > 4096) return emptyList()
            return List(n.toInt()) { u16() }
        }
        fun str(): String {
            val n = u8()
            if (n == 0) return ""
            val sb = StringBuilder()
            for (i in 0 until n) {
                val c = u16()
                if (c != 0) sb.append(c.toChar())
            }
            return sb.toString().trim()
        }
    }
}

internal fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
internal fun le32(b: ByteArray, p: Int): Long =
    (b[p].toLong() and 0xff) or ((b[p + 1].toLong() and 0xff) shl 8) or
        ((b[p + 2].toLong() and 0xff) shl 16) or ((b[p + 3].toLong() and 0xff) shl 24)
internal fun putLe16(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte() }
internal fun putLe32(b: ByteArray, p: Int, v: Long) {
    b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte(); b[p + 3] = (v shr 24).toByte()
}
