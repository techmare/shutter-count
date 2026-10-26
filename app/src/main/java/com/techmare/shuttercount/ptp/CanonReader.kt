package com.techmare.shuttercount.ptp

import com.techmare.shuttercount.core.Brand
import com.techmare.shuttercount.core.CameraModels

data class CanonCounter(val label: String, val value: Long, val raw: String? = null)

enum class CanonMethod(val label: String) {
    PROPERTY("Standard counter (PTP property)"),
    EVENT("Standard counter (EOS event)"),
    FACTORY("Shutter unit memory (experimental)"),
}

data class CanonReading(
    val model: String,
    val serial: String?,
    val firmware: String?,
    val counters: List<CanonCounter>,
    val method: CanonMethod?,
    /** False when the value comes from a method not yet confirmed on this model. */
    val confirmed: Boolean,
    val ratedLife: Int?,
    val notes: List<String>,
    val problem: String?,
    /** True when standard methods failed but the experimental route can still be tried. */
    val canTryAdvanced: Boolean,
    /** What was tried and how the camera answered. */
    val diagnostics: String = "",
) {
    val shutterCount: Long? get() = counters.firstOrNull()?.value
}

/**
 * Reads Canon EOS shutter counters over USB.
 *
 * 1. Standard property 0xD1AC (DIGIC 5 and older bodies).
 * 2. The same property as reported through Canon's EOS event stream.
 * 3. Optional, only when [allowAdvanced]: the read-only MPU monitor channel
 *    (opcodes 0x9052 / 0x9053) used by DIGIC 6+ bodies. Framing follows the
 *    openshuttercounter project (MIT), which confirmed it on an EOS 5D Mark IV.
 *    Only MonOpen / MonReadAndGetData / MonClose are ever sent; write commands
 *    are refused in [FactoryCommand.marshal].
 */
class CanonReader(private val session: PtpSession) {

    /** Step-by-step log of what was tried and how the camera answered, for the diagnostic report. */
    private val log = ArrayList<String>()

    fun read(usbProductName: String?, allowAdvanced: Boolean): CanonReading {
        log.clear()
        session.openSession()
        try {
            val info = session.getDeviceInfo()
            val model = info.model.ifBlank { usbProductName ?: "Canon camera" }
            val serial = info.serialNumber.ifBlank { null }
            val firmware = info.deviceVersion.ifBlank { null }
            val rated = CameraModels.ratedLife(Brand.CANON, model)
            log += "Camera: ${info.manufacturer} / $model / firmware ${firmware ?: "?"} / vendor ext ${info.vendorExtensionId}"
            log += "Operations (${info.operations.size}): " + info.operations.sorted().joinToString(" ") { "%04X".format(it) }
            log += "Properties (${info.properties.size}): " + info.properties.sorted().joinToString(" ") { "%04X".format(it) }

            fun result(
                counters: List<CanonCounter>, method: CanonMethod?, confirmed: Boolean = true,
                notes: List<String> = emptyList(), problem: String? = null, canTryAdvanced: Boolean = false,
            ) = CanonReading(model, serial, firmware, counters, method, confirmed, rated, notes, problem, canTryAdvanced, log.joinToString("\n"))

            if (!info.manufacturer.contains("Canon", ignoreCase = true) && info.vendorExtensionId != 11L) {
                return result(emptyList(), null,
                    problem = "This is a ${info.manufacturer.ifBlank { "non-Canon" }} camera. Over USB this app reads Canon only. For Nikon and Sony, use the Photo tab.")
            }

            // 1. Standard property
            readProperty()?.let { return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.PROPERTY) }

            // 2. EOS event stream (also asks the camera to report the counter property)
            if (info.operations.containsAll(listOf(OP_EOS_SET_REMOTE_MODE, OP_EOS_SET_EVENT_MODE, OP_EOS_GET_EVENT))) {
                readViaEvents(info.operations.contains(OP_EOS_REQUEST_PROP))?.let {
                    return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.EVENT)
                }
            } else {
                log += "EOS event stream: not offered by this camera"
            }

            // 3. Experimental MPU route. Some bodies answer it without listing the opcodes.
            val listed = info.operations.contains(OP_FACTORY_SEND) && info.operations.contains(OP_FACTORY_RECV)
            log += "Shutter-unit channel 9052/9053: " + if (listed) "listed" else "not listed"
            if (!allowAdvanced) {
                return result(emptyList(), null,
                    problem = if (listed)
                        "This is a newer Canon (DIGIC 6 or later). The standard counter isn't available, but the count can be read from the shutter unit's memory with an experimental method."
                    else
                        "The standard counter isn't available on this Canon. The experimental method isn't listed by this camera, but some newer Canons answer it anyway. It only reads, so it's safe to try.",
                    canTryAdvanced = true)
            }

            val confirmed = listed && CONFIRMED_FACTORY_MODELS.any { model.contains(it, ignoreCase = true) }
            val counters = readFactory()
            if (counters.isEmpty()) {
                return result(emptyList(), CanonMethod.FACTORY, false,
                    problem = "The camera didn't answer the experimental read either, so this model can't be read yet. Nothing was changed on the camera. Share the diagnostic report so support for it can be added.")
            }
            val notes = mutableListOf<String>()
            if (!confirmed) {
                notes += "This method is only confirmed on the EOS 5D Mark IV. Check it on this camera: take exactly 5 photos, read again, and the shutter count should go up by exactly 5."
            }
            if (counters.first().value >= 65_000) {
                notes += "Values above about 65,000 may not decode correctly with this method yet."
            }
            return result(counters, CanonMethod.FACTORY, confirmed, notes)
        } finally {
            session.closeSession()
        }
    }

    private fun hex(code: Int) = "%04X".format(code)

    private fun readProperty(): Long? = try {
        val r = session.transact(OP_GET_DEVICE_PROP_VALUE, listOf(PROP_SHUTTER_COUNTER.toLong()))
        log += "GetDevicePropValue D1AC: ${hex(r.code)}, ${r.data.size} bytes"
        if (r.ok && r.data.size >= 4) le32(r.data, 0).takeIf { it in 1..20_000_000 } else null
    } catch (e: PtpException) { log += "GetDevicePropValue D1AC: ${e.message}"; null }

    private fun readViaEvents(canRequest: Boolean): Long? = try {
        val remote = session.transact(OP_EOS_SET_REMOTE_MODE, listOf(1L))
        val event = session.transact(OP_EOS_SET_EVENT_MODE, listOf(1L))
        log += "SetRemoteMode: ${hex(remote.code)}, SetEventMode: ${hex(event.code)}"
        var found: Long? = null
        if (remote.ok && event.ok) {
            if (canRequest) {
                val rq = session.transact(OP_EOS_REQUEST_PROP, listOf(PROP_SHUTTER_COUNTER.toLong()))
                log += "RequestDevicePropValue D1AC: ${hex(rq.code)}"
            }
            for (attempt in 0 until 4) {
                val r = session.transact(OP_EOS_GET_EVENT, emptyList())
                if (!r.ok) { log += "GetEvent: ${hex(r.code)}"; break }
                found = findCounterInEvents(r.data)
                log += "GetEvent #${attempt + 1}: ${r.data.size} bytes, counter ${found ?: "not present"}"
                if (found != null || r.data.size <= 8) break
            }
        }
        // leave the camera as we found it
        try { session.transact(OP_EOS_SET_EVENT_MODE, listOf(0L)); session.transact(OP_EOS_SET_REMOTE_MODE, listOf(0L)) } catch (_: PtpException) { }
        found?.takeIf { it in 1..20_000_000 }
    } catch (e: PtpException) { log += "EOS events: ${e.message}"; null }

    private fun readFactory(): List<CanonCounter> {
        val out = ArrayList<CanonCounter>()
        if (!sendFactory(FactoryCommand("MonOpen"), expectReply = false)) return out
        try {
            for ((label, address) in MPU_COUNTERS) {
                if (!sendFactory(FactoryCommand.monRead(address, 10), expectReply = true)) continue
                val r = session.transact(OP_FACTORY_RECV, listOf(0L, 0L, 1L))
                val raw = r.data.joinToString(" ") { "%02x".format(it) }
                log += "MonRead ${"%04X".format(address)}: ${hex(r.code)} [$raw]"
                if (!r.ok || r.data.size < 3) continue
                out += CanonCounter(label, decodeMpuCounter(r.data), raw)
            }
        } finally {
            try { sendFactory(FactoryCommand("MonClose"), expectReply = false) } catch (_: PtpException) { }
        }
        return out
    }

    private fun sendFactory(cmd: FactoryCommand, expectReply: Boolean): Boolean {
        val r = try {
            session.transactOut(OP_FACTORY_SEND, listOf(0L, if (expectReply) 1L else 0L), cmd.marshal())
        } catch (e: PtpException) { log += "${cmd.name}: ${e.message}"; return false }
        if (!r.ok || cmd.name != "MonReadAndGetData") log += "${cmd.name}: ${hex(r.code)}"
        return r.ok
    }

    companion object {
        const val PROP_SHUTTER_COUNTER = 0xD1AC
        const val OP_EOS_SET_REMOTE_MODE = 0x9114
        const val OP_EOS_SET_EVENT_MODE = 0x9115
        const val OP_EOS_GET_EVENT = 0x9116
        const val OP_EOS_REQUEST_PROP = 0x9127
        const val OP_FACTORY_SEND = 0x9052
        const val OP_FACTORY_RECV = 0x9053
        private const val EVT_PROP_VALUE_CHANGED = 0xC189L

        val CONFIRMED_FACTORY_MODELS = listOf("5D Mark IV")

        val MPU_COUNTERS = listOf(
            "Shutter actuations" to 0x1001L,
            "Mirror actuations" to 0x100BL,
            "Total counter" to 0x1015L,
        )

        /** Scans a Canon EOS GetEvent dataset for property 0xD1AC. */
        fun findCounterInEvents(d: ByteArray): Long? {
            var p = 0
            while (p + 8 <= d.size) {
                val size = le32(d, p)
                val type = le32(d, p + 4)
                if (size < 8 || type == 0L || p + size > d.size) break
                if (type == EVT_PROP_VALUE_CHANGED && size >= 16) {
                    val prop = le32(d, p + 8)
                    if (prop == PROP_SHUTTER_COUNTER.toLong()) return le32(d, p + 12)
                }
                p += size.toInt()
            }
            return null
        }

        /**
         * MPU replies hold a high byte at offset 1 and the low byte as the last
         * non-zero byte after it (empirical rule from openshuttercounter).
         */
        fun decodeMpuCounter(reply: ByteArray): Long {
            val high = (reply[1].toLong() and 0xff)
            var low = 0L
            for (i in 2 until reply.size) {
                val b = reply[i].toLong() and 0xff
                if (b != 0L) low = b
            }
            return (high shl 8) or low
        }
    }
}

/** A read-only command on Canon's MPU monitor channel. */
class FactoryCommand(val name: String, val args: List<Long> = emptyList()) {

    fun marshal(): ByteArray {
        require(name !in REFUSED) { "Refusing to send $name: it writes to the camera." }
        require(name.isNotEmpty() && name.all { it.code in 0x20..0x7e }) { "Bad command name" }
        require(args.size <= 3) { "Too many arguments" }
        val trailer = if (args.isEmpty()) 24 else 4
        val out = ByteArray(name.length + 1 + 4 + 20 * args.size + trailer)
        var p = 0
        for (c in name) out[p++] = c.code.toByte()
        out[p++] = 0
        putLe32(out, p, args.size.toLong()); p += 4
        for (a in args) {
            putLe32(out, p, 2L)      // type tag: integer
            putLe32(out, p + 4, a)   // value
            p += 20                  // + 12 zero bytes
        }
        return out
    }

    companion object {
        val REFUSED = setOf("FA_SetProperty", "FA_SetCalendar", "MonWrite", "MonCall")
        fun monRead(address: Long, length: Long) = FactoryCommand("MonReadAndGetData", listOf(2L, address, length))
    }
}
