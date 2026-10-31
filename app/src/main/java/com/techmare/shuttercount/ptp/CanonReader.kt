package com.techmare.shuttercount.ptp

import com.techmare.shuttercount.core.Brand
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.core.PhotoShutterReader

data class CanonCounter(val label: String, val value: Long, val raw: String? = null)

enum class CanonMethod(val label: String) {
    PROPERTY("Standard counter (PTP property)"),
    EVENT("Standard counter (EOS event)"),
    FACTORY("Shutter unit memory (experimental)"),
    PHOTO("Newest photo on the card"),
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
    val ratedOfficial: Boolean = false,
    /** For [CanonMethod.PHOTO]: which file was read and when it was taken. */
    val photoFile: String? = null,
    val photoDate: String? = null,
    /** The counter includes electronic-shutter shots (Canon R bodies read from a photo). */
    val includesElectronic: Boolean = false,
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
    private var listedFactory = false

    fun read(usbProductName: String?, allowAdvanced: Boolean): CanonReading {
        log.clear()
        session.openSession()
        try {
            val info = session.getDeviceInfo()
            val model = info.model.ifBlank { usbProductName ?: "Canon camera" }
            // DeviceInfo's serial on Canon is a long hex ID (31 or 32 characters), not the body serial.
            // The real one comes from the EOS event stream (property D1AF); without it, show nothing.
            var serial: String? = info.serialNumber.trim().takeIf { it.isNotEmpty() && !CameraModels.isDeviceId(it) }
            val firmware = info.deviceVersion.ifBlank { null }
            val rated = CameraModels.ratedLife(Brand.CANON, model)
            log += "Camera: ${info.manufacturer} / $model / firmware ${firmware ?: "?"} / vendor ext ${info.vendorExtensionId}"
            log += "Operations (${info.operations.size}): " + info.operations.sorted().joinToString(" ") { "%04X".format(it) }
            log += "Properties (${info.properties.size}): " + info.properties.sorted().joinToString(" ") { "%04X".format(it) }

            fun result(
                counters: List<CanonCounter>, method: CanonMethod?, confirmed: Boolean = true,
                notes: List<String> = emptyList(), problem: String? = null, canTryAdvanced: Boolean = false,
            ) = CanonReading(
                model, serial, firmware, counters, method, confirmed, rated, notes, problem, canTryAdvanced,
                ratedOfficial = CameraModels.rating(Brand.CANON, model)?.official ?: false,
                diagnostics = log.joinToString("\n"),
            )

            // Nikon, Sony and Canon R bodies keep the count in their photos: read the newest one
            // over USB with standard transfer commands instead of any vendor command.
            val isCanon = info.manufacturer.contains("Canon", ignoreCase = true) || info.vendorExtensionId == 11L
            if (!isCanon || CameraModels.canonPhotoOffset(model) != null) {
                return readNewestPhoto(info, model, serial, firmware)
            }

            // Canon R bodies we can't decode yet: don't send them the older bodies' status commands
            // (an EOS RP stopped answering after them). Their count is in their photos instead.
            if (CameraModels.isPhotoCountCanon(model, firmware)) {
                return result(emptyList(), null,
                    problem = "This Canon keeps its shutter count inside its JPG photos, not where older Canons do. " +
                        "Reading the ${model.removePrefix("Canon ").trim()} isn't supported yet: support is added by comparing two of its JPG photos taken a few shots apart.")
            }

            // 1. Standard property
            val propCount = readProperty()

            // 2. EOS event stream: also carries the body serial number
            var eventCount: Long? = null
            if (info.operations.containsAll(listOf(OP_EOS_SET_REMOTE_MODE, OP_EOS_SET_EVENT_MODE, OP_EOS_GET_EVENT))) {
                val ev = readViaEvents(info.operations.contains(OP_EOS_REQUEST_PROP))
                eventCount = ev.first
                ev.second?.let { serial = it }
            } else {
                log += "EOS event stream: not offered by this camera"
            }
            propCount?.let { return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.PROPERTY) }
            eventCount?.let { return result(listOf(CanonCounter("Shutter actuations", it)), CanonMethod.EVENT) }

            // 3. Experimental MPU route. Some bodies answer it without listing the opcodes.
            val listed = info.operations.contains(OP_FACTORY_SEND) && info.operations.contains(OP_FACTORY_RECV)
            listedFactory = listed
            log += "Shutter-unit channel 9052/9053: " + if (listed) "listed" else "not listed"
            // Never send these commands to a camera that doesn't list them: an EOS R50 that
            // didn't list them showed Err 70 when they were sent anyway.
            if (!listed) {
                return result(emptyList(), null,
                    problem = if (CameraModels.canonPhotoOffset(model) != null)
                        "This Canon can't be read safely over USB, but its count is saved in every photo. Take a new photo, copy the original JPEG or CR3 to this phone, and pick it in the Photo tab."
                    else
                        "This Canon doesn't offer a way to read its shutter count over USB that this app can use safely. " +
                            "For this model, ask a Canon service centre or use a tool that officially supports it.")
            }
            if (!allowAdvanced) {
                return result(emptyList(), null,
                    problem = "This is a newer Canon (DIGIC 6 or later). The standard counter isn't available, but the count can be read from the shutter unit's memory with an experimental method.",
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

    private fun readNewestPhoto(info: DeviceInfo, usbModel: String, usbSerial: String?, firmware: String?): CanonReading {
        val pull = PhotoPull(session, info.operations, log)
        fun fail(problem: String) = CanonReading(
            usbModel, usbSerial, firmware, emptyList(), CanonMethod.PHOTO, true, null, emptyList(), problem, false,
            diagnostics = log.joinToString("\n"),
        )
        var got = try { pull.latest() } catch (e: PtpException) { log += "Photo transfer: ${e.message}"; null }
            ?: return fail("No photo found on the camera's card. Take a photo, then read again.")
        var reading = PhotoShutterReader.read(got.bytes)
        if (reading.shutterCount == null && got.partial) {
            // the camera data wasn't in the first part of the file: fetch all of it
            try { pull.fullOf(got) } catch (e: PtpException) { null }?.let { got = it; reading = PhotoShutterReader.read(it.bytes) }
        }
        val count = reading.shutterCount
        log += "Photo read: ${reading.displayName}, count ${count ?: "none"}${reading.problem?.let { ", $it" } ?: ""}"
        val date = got.captureDate?.let { d ->
            Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})").find(d)?.destructured?.let { (y, mo, dd, h, mi) -> "$dd/$mo/$y $h:$mi" } ?: d
        } ?: reading.dateTaken
        return CanonReading(
            model = if (reading.make != null) reading.displayName else usbModel,
            serial = reading.serial ?: usbSerial,
            firmware = firmware,
            counters = count?.let { listOf(CanonCounter("Shutter actuations", it)) } ?: emptyList(),
            method = CanonMethod.PHOTO,
            confirmed = true,
            ratedLife = reading.ratedLife,
            notes = reading.warnings,
            problem = if (count == null) reading.problem ?: "No shutter count found in the newest photo." else null,
            canTryAdvanced = false,
            ratedOfficial = reading.ratedOfficial,
            photoFile = got.fileName,
            photoDate = date,
            includesElectronic = reading.brand == Brand.CANON || reading.details.any { it.second.contains("includes electronic", true) },
            diagnostics = log.joinToString("\n"),
        )
    }

    private fun readProperty(): Long? = try {
        val r = session.transact(OP_GET_DEVICE_PROP_VALUE, listOf(PROP_SHUTTER_COUNTER.toLong()))
        log += "GetDevicePropValue D1AC: ${hex(r.code)}, ${r.data.size} bytes"
        if (r.ok && r.data.size >= 4) le32(r.data, 0).takeIf { it in 1..20_000_000 } else null
    } catch (e: PtpException) { log += "GetDevicePropValue D1AC: ${e.message}"; null }

    /** Returns (shutter count, body serial number) from Canon's EOS event stream. */
    private fun readViaEvents(canRequest: Boolean): Pair<Long?, String?> = try {
        val remote = session.transact(OP_EOS_SET_REMOTE_MODE, listOf(1L))
        val event = session.transact(OP_EOS_SET_EVENT_MODE, listOf(1L))
        log += "SetRemoteMode: ${hex(remote.code)}, SetEventMode: ${hex(event.code)}"
        var count: Long? = null
        var serial: String? = null
        if (remote.ok && event.ok) {
            if (canRequest) {
                for (prop in listOf(PROP_SHUTTER_COUNTER, PROP_SERIAL)) {
                    val rq = session.transact(OP_EOS_REQUEST_PROP, listOf(prop.toLong()))
                    log += "RequestDevicePropValue ${hex(prop)}: ${hex(rq.code)}"
                }
            }
            for (attempt in 0 until 4) {
                val r = session.transact(OP_EOS_GET_EVENT, emptyList())
                if (!r.ok) { log += "GetEvent: ${hex(r.code)}"; break }
                count = count ?: findPropInEvents(r.data, PROP_SHUTTER_COUNTER)?.takeIf { it.size >= 4 }?.let { le32(it, 0) }
                serial = serial ?: findPropInEvents(r.data, PROP_SERIAL)?.let(::asciiString)
                log += "GetEvent #${attempt + 1}: ${r.data.size} bytes, counter ${count ?: "not present"}, serial ${if (serial != null) "found" else "not present"}"
                if ((count != null && serial != null) || r.data.size <= 8) break
            }
        }
        // leave the camera as we found it
        try { session.transact(OP_EOS_SET_EVENT_MODE, listOf(0L)); session.transact(OP_EOS_SET_REMOTE_MODE, listOf(0L)) } catch (_: PtpException) { }
        count?.takeIf { it in 1..20_000_000 } to serial
    } catch (e: PtpException) { log += "EOS events: ${e.message}"; null to null }

    private fun readFactory(): List<CanonCounter> {
        val out = ArrayList<CanonCounter>()
        check(listedFactory) { "Shutter-unit channel not listed by this camera" }
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
        const val PROP_SERIAL = 0xD1AF
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
        fun findCounterInEvents(d: ByteArray): Long? =
            findPropInEvents(d, PROP_SHUTTER_COUNTER)?.takeIf { it.size >= 4 }?.let { le32(it, 0) }

        /** Value bytes of a property-changed record in a Canon EOS GetEvent dataset. */
        fun findPropInEvents(d: ByteArray, wanted: Int): ByteArray? {
            var p = 0
            while (p + 8 <= d.size) {
                val size = le32(d, p)
                val type = le32(d, p + 4)
                if (size < 8 || type == 0L || p + size > d.size) break
                if (type == EVT_PROP_VALUE_CHANGED && size >= 12) {
                    val prop = le32(d, p + 8)
                    if (prop == wanted.toLong()) return d.copyOfRange(p + 12, (p + size).toInt())
                }
                p += size.toInt()
            }
            return null
        }

        /** Printable serial from a NUL-terminated ASCII value, or null. */
        fun asciiString(b: ByteArray): String? {
            val end = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
            val s = String(b, 0, end, Charsets.US_ASCII).trim()
            return s.takeIf { it.length in 4..32 && it.all { c -> c.isLetterOrDigit() } }
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
