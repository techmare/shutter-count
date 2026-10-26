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
    /** True when standard methods failed but the experimental route is available. */
    val canTryAdvanced: Boolean,
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

    fun read(usbProductName: String?, allowAdvanced: Boolean): CanonReading {
        session.openSession()
        try {
            val info = session.getDeviceInfo()
            val model = info.model.ifBlank { usbProductName ?: "Canon camera" }
            val serial = info.serialNumber.ifBlank { null }
            val firmware = info.deviceVersion.ifBlank { null }
            val rated = CameraModels.ratedLife(Brand.CANON, model)

            fun result(
                counters: List<CanonCounter>, method: CanonMethod?, confirmed: Boolean = true,
                notes: List<String> = emptyList(), problem: String? = null, canTryAdvanced: Boolean = false,
            ) = CanonReading(model, serial, firmware, counters, method, confirmed, rated, notes, problem, canTryAdvanced)

            if (!info.manufacturer.contains("Canon", ignoreCase = true) && info.vendorExtensionId != 11L) {
                return result(emptyList(), null,
                    problem = "This is a ${info.manufacturer.ifBlank { "non-Canon" }} camera. Over USB this app reads Canon only — for Nikon and Sony, use the Photo tab.")
            }

            // 1. Standard property
            readProperty()?.let { return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.PROPERTY) }

            // 2. EOS event stream
            if (info.operations.containsAll(listOf(OP_EOS_SET_REMOTE_MODE, OP_EOS_SET_EVENT_MODE, OP_EOS_GET_EVENT))) {
                readViaEvents()?.let { return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.EVENT) }
            }

            // 3. Experimental MPU route
            val hasFactory = info.operations.contains(OP_FACTORY_SEND) && info.operations.contains(OP_FACTORY_RECV)
            if (!hasFactory) {
                return result(emptyList(), null,
                    problem = "This Canon model doesn't share its shutter count over USB with any method this app knows yet.")
            }
            if (!allowAdvanced) {
                return result(emptyList(), null,
                    problem = "This is a newer Canon (DIGIC 6 or later). The standard counter isn't available, but the count can be read from the shutter unit's memory with an experimental method.",
                    canTryAdvanced = true)
            }

            val confirmed = CONFIRMED_FACTORY_MODELS.any { model.contains(it, ignoreCase = true) }
            val counters = readFactory()
            if (counters.isEmpty()) {
                return result(emptyList(), CanonMethod.FACTORY, confirmed,
                    problem = "The camera didn't answer the experimental read. Nothing was changed on the camera.")
            }
            val notes = mutableListOf<String>()
            if (!confirmed) {
                notes += "This method is confirmed on the EOS 5D Mark IV only. To check it on this camera: take exactly 5 photos, read again, and the shutter count should go up by exactly 5."
            }
            if (counters.first().value >= 65_000) {
                notes += "Values above ~65,000 may not decode correctly with this method yet. Compare with a service-centre reading if it matters."
            }
            return result(counters, CanonMethod.FACTORY, confirmed, notes)
        } finally {
            session.closeSession()
        }
    }

    private fun readProperty(): Long? = try {
        val r = session.transact(OP_GET_DEVICE_PROP_VALUE, listOf(PROP_SHUTTER_COUNTER.toLong()))
        if (r.ok && r.data.size >= 4) le32(r.data, 0).takeIf { it in 1..20_000_000 } else null
    } catch (e: PtpException) { null }

    private fun readViaEvents(): Long? = try {
        val remote = session.transact(OP_EOS_SET_REMOTE_MODE, listOf(1L))
        val event = session.transact(OP_EOS_SET_EVENT_MODE, listOf(1L))
        var found: Long? = null
        if (remote.ok && event.ok) {
            for (attempt in 0 until 3) {
                val r = session.transact(OP_EOS_GET_EVENT, emptyList())
                if (!r.ok) break
                found = findCounterInEvents(r.data)
                if (found != null || r.data.size <= 8) break
            }
        }
        found?.takeIf { it in 1..20_000_000 }
    } catch (e: PtpException) { null }

    private fun readFactory(): List<CanonCounter> {
        val out = ArrayList<CanonCounter>()
        if (!sendFactory(FactoryCommand("MonOpen"), expectReply = false)) return out
        try {
            for ((label, address) in MPU_COUNTERS) {
                if (!sendFactory(FactoryCommand.monRead(address, 10), expectReply = true)) continue
                val r = session.transact(OP_FACTORY_RECV, listOf(0L, 0L, 1L))
                if (!r.ok || r.data.size < 3) continue
                out += CanonCounter(label, decodeMpuCounter(r.data), r.data.joinToString(" ") { "%02x".format(it) })
            }
        } finally {
            try { sendFactory(FactoryCommand("MonClose"), expectReply = false) } catch (_: PtpException) { }
        }
        return out
    }

    private fun sendFactory(cmd: FactoryCommand, expectReply: Boolean): Boolean {
        val r = session.transactOut(OP_FACTORY_SEND, listOf(0L, if (expectReply) 1L else 0L), cmd.marshal())
        return r.ok
    }

    companion object {
        const val PROP_SHUTTER_COUNTER = 0xD1AC
        const val OP_EOS_SET_REMOTE_MODE = 0x9114
        const val OP_EOS_SET_EVENT_MODE = 0x9115
        const val OP_EOS_GET_EVENT = 0x9116
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
