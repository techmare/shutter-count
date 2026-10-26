package com.techmare.shuttercount.core

/** A result read from a photo file. */
data class PhotoReading(
    val brand: Brand,
    val make: String?,
    val model: String?,
    val displayName: String,
    /** Main shutter count, or null when the file does not carry one. */
    val shutterCount: Long?,
    val ratedLife: Int?,
    /** True when [ratedLife] is the maker's published figure, false when it's an estimate. */
    val ratedOfficial: Boolean = false,
    val serial: String?,
    val dateTaken: String?,
    val software: String?,
    /** Extra values worth showing (label to value). */
    val details: List<Pair<String, String>>,
    val warnings: List<String>,
    /** Why there is no count, when [shutterCount] is null. */
    val problem: String?,
)

/**
 * Reads the shutter count stored inside Nikon and Sony photos (JPEG, NEF, ARW).
 * Pure Kotlin with no Android dependencies so it can be unit tested on the JVM.
 */
object PhotoShutterReader {

    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_SOFTWARE = 0x0131
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_MAKERNOTE = 0x927C
    private const val TAG_DATE_ORIGINAL = 0x9003
    private const val TAG_BODY_SERIAL = 0xA431

    fun read(data: ByteArray): PhotoReading {
        if (isCr3(data)) return readCr3(data)
        val tiffStart = findTiffStart(data)
            ?: return failure("This file has no camera data (EXIF). Use the original JPEG or RAW file straight from the camera.")

        val little = when {
            data[tiffStart] == 'I'.code.toByte() && data[tiffStart + 1] == 'I'.code.toByte() -> true
            data[tiffStart] == 'M'.code.toByte() && data[tiffStart + 1] == 'M'.code.toByte() -> false
            else -> return failure("Unrecognised photo format.")
        }
        val tiff = Tiff(data, tiffStart, little)
        val ifd0Off = tiff.u32(tiffStart + 4) ?: return failure("Corrupt photo data.")
        val ifd0 = tiff.readIfd((tiffStart + ifd0Off).toInt())
            ?: return failure("Corrupt photo data.")

        val make = ifd0.find(TAG_MAKE)?.let(tiff::string)
        val model = ifd0.find(TAG_MODEL)?.let(tiff::string)
        val software = ifd0.find(TAG_SOFTWARE)?.let(tiff::string)

        val exifIfd = ifd0.find(TAG_EXIF_IFD)?.let(tiff::int)
            ?.let { tiff.readIfd((tiffStart + it).toInt()) }

        val dateTaken = exifIfd?.find(TAG_DATE_ORIGINAL)?.let(tiff::string)
        val bodySerial = exifIfd?.find(TAG_BODY_SERIAL)?.let(tiff::string)
        val makerNote = exifIfd?.find(TAG_MAKERNOTE)

        val brand = CameraModels.brandOf(make)
        val ctx = Ctx(tiff, brand, make, model, software, dateTaken, bodySerial)

        val warnings = mutableListOf<String>()
        if (software != null && Regex("photoshop|lightroom|snapseed|gimp|capture one|picsart|whatsapp|instagram", RegexOption.IGNORE_CASE).containsMatchIn(software)) {
            warnings += "This photo was edited with $software. Edited photos may have missing or altered data; use an untouched file from the camera."
        }

        return when (brand) {
            Brand.NIKON -> readNikon(ctx, makerNote, warnings)
            Brand.SONY -> readSony(ctx, makerNote, warnings)
            Brand.CANON -> readCanon(ctx, makerNote, warnings)
            Brand.OTHER -> ctx.result(
                null, warnings = warnings,
                problem = if (make == null) "This photo has no camera make. It may come from a phone, an editor or a messaging app."
                else "$make cameras are not supported. This app reads Nikon, Sony and recent Canon R photos, and older Canons over USB.",
            )
        }
    }

    // ---------------------------------------------------------------- Canon

    /**
     * Canon R bodies keep a counter in the CameraInfo block (maker note tag 0x000D).
     * Offsets from ExifTool (Canon.pm, CameraInfoR6 / CameraInfoR6m2). The value
     * counts mechanical and electronic shutter shots together.
     */
    private fun canonOffset(model: String?): Int? = CameraModels.canonPhotoOffset(model)

    private const val CANON_UNSUPPORTED =
        "This Canon doesn't store a readable shutter count in photos. Photo reading works for the EOS R50, R100, R8, R6, R6 Mark II and R5. Older Canons (DIGIC 5 and before) can be read over USB."

    private fun readCanon(ctx: Ctx, mn: IfdEntry?, warnings: MutableList<String>): PhotoReading {
        val offset = canonOffset(ctx.model) ?: return ctx.result(null, warnings = warnings, problem = CANON_UNSUPPORTED)
        if (mn == null) return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        val t = Tiff(ctx.tiff.buf, ctx.tiff.base, ctx.tiff.littleEndian)
        val pos = mn.valuePos.toInt()
        if (!t.looksLikeIfd(pos)) t.littleEndian = !t.littleEndian
        val ifd = t.readIfd(pos) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        return canonResult(ctx, t, ifd, offset, warnings)
    }

    private fun canonResult(ctx: Ctx, t: Tiff, ifd: List<IfdEntry>, offset: Int, warnings: MutableList<String>): PhotoReading {
        val info = ifd.find(0x000D)?.let(t::bytes)
        if (info == null || info.size < offset + 4) {
            return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        }
        val count = Tiff(info, 0, t.littleEndian).u32(offset)
        if (count == null || count <= 0 || count > 5_000_000) {
            return ctx.result(null, warnings = warnings,
                problem = "Couldn't find a valid shutter count in this Canon photo. Try a JPEG or CR3 taken just now.")
        }
        val serial = ifd.find(0x000C)?.let(t::int)?.takeIf { it > 0 }?.toString() ?: ctx.bodySerial
        return ctx.result(
            count, serial = serial, warnings = warnings,
            details = listOf("Note" to "On this Canon the count includes electronic-shutter shots, so wear may look higher than the mechanical shutter's real use."),
        )
    }

    private fun isCr3(d: ByteArray) = d.size > 12 && startsWith(d, 4, "ftyp") && startsWith(d, 8, "crx ")

    /** CR3: metadata lives in CMT boxes (CMT1 = IFD0, CMT2 = EXIF, CMT3 = Canon maker note), each a little TIFF. */
    private fun readCr3(d: ByteArray): PhotoReading {
        fun box(type: String): Int? {
            val limit = minOf(d.size - 8, 4 * 1024 * 1024)
            var i = 4
            while (i < limit) {
                if (d[i] == type[0].code.toByte() && startsWith(d, i, type)) return i + 4
                i++
            }
            return null
        }
        fun tiffAt(start: Int): Pair<Tiff, List<IfdEntry>>? {
            if (start + 8 > d.size) return null
            val little = d[start] == 'I'.code.toByte()
            val t = Tiff(d, start, little)
            val off = t.u32(start + 4) ?: return null
            val ifd = t.readIfd((start + off).toInt()) ?: return null
            return t to ifd
        }
        val (t1, ifd0) = box("CMT1")?.let(::tiffAt) ?: return failure("Couldn't read this CR3 file.")
        val make = ifd0.find(TAG_MAKE)?.let(t1::string)
        val model = ifd0.find(TAG_MODEL)?.let(t1::string)
        val software = ifd0.find(TAG_SOFTWARE)?.let(t1::string)
        val exif = box("CMT2")?.let(::tiffAt)
        val dateTaken = exif?.let { (t, ifd) -> ifd.find(TAG_DATE_ORIGINAL)?.let(t::string) }
        val bodySerial = exif?.let { (t, ifd) -> ifd.find(TAG_BODY_SERIAL)?.let(t::string) }
        val ctx = Ctx(t1, Brand.CANON, make, model, software, dateTaken, bodySerial)
        val warnings = mutableListOf<String>()
        val offset = canonOffset(model) ?: return ctx.result(null, warnings = warnings, problem = CANON_UNSUPPORTED)
        val (t3, mn) = box("CMT3")?.let(::tiffAt) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        return canonResult(ctx, t3, mn, offset, warnings)
    }

    // ---------------------------------------------------------------- Nikon

    private fun readNikon(ctx: Ctx, mn: IfdEntry?, warnings: MutableList<String>): PhotoReading {
        if (mn == null) return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        val buf = ctx.tiff.buf
        val start = mn.valuePos.toInt()
        if (start < 0 || start + 18 > buf.size) return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)

        val ifd: List<IfdEntry>
        val t: Tiff
        if (startsWith(buf, start, "Nikon\u0000\u0002")) {
            // Type 3: own TIFF header 10 bytes in; offsets relative to it.
            val hdr = start + 10
            val little = buf[hdr] == 'I'.code.toByte()
            t = Tiff(buf, hdr, little)
            val off = t.u32(hdr + 4) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
            ifd = t.readIfd((hdr + off).toInt()) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        } else if (startsWith(buf, start, "Nikon\u0000\u0001")) {
            return ctx.result(null, warnings = warnings, problem = "This older Nikon model does not record a shutter count.")
        } else {
            // Headerless (D1 era): IFD right here, offsets relative to the EXIF TIFF header.
            t = Tiff(buf, ctx.tiff.base, ctx.tiff.littleEndian)
            if (!t.looksLikeIfd(start)) t.littleEndian = !t.littleEndian
            ifd = t.readIfd(start) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        }

        val serial = ifd.find(0x001D)?.let(t::string) ?: ctx.bodySerial
        val countEntry = ifd.find(0x00A7)
        val count = countEntry?.let(t::int)
        if (count == null || count == 4294965247L) {
            return ctx.result(null, serial = serial, warnings = warnings,
                problem = "This Nikon photo does not contain a shutter count (some older or compact models don't record it).")
        }

        val details = mutableListOf<Pair<String, String>>()
        val upperModel = ctx.model?.uppercase().orEmpty()
        if (upperModel.contains(" Z")) {
            details += "Note" to "On Nikon Z cameras this count includes electronic-shutter shots."
        }
        return ctx.result(count, serial = serial, details = details, warnings = warnings)
    }

    // ----------------------------------------------------------------- Sony

    private enum class SonyTable { A, B, C, D }

    private val sonyA = Regex("^(DSC-|Stellar|ILCE-(1|6100|6300|6400|6500|6600|6700|7C|7M3|7M4|7M5|7RM2|7RM3A?|7RM4A?|7RM5|7RM6|7SM2|7SM3|9|9M2)|ILCA-99M2|ILME-(FX2|FX3)|ZV-)")
    private val sonyB = Regex("^(ILCE-(6100A?|6300|6400A?|6500|6600|7C|7M3|7RM2|7RM3A?|7RM4A?|7SM2|9|9M2)|ILCA-99M2|ZV-E10)\\b")
    private val sonyC = Regex("^(ILCE-(1\\b|7M4|7RM5|7SM3)|ILME-FX3)")
    private val sonyD = Regex("^(ILCE-(6700|7CM2|7CR)|ILME-FX2|ZV-(E1|E10M2))\\b")
    private val sonyDMech = Regex("^(ILCE-(1M2|7M5|7RM6))")

    private fun sonyTable(model: String, raw: ByteArray): SonyTable? = when {
        !sonyA.containsMatchIn(model) -> SonyTable.A
        sonyB.containsMatchIn(model) -> SonyTable.B
        sonyC.containsMatchIn(model) -> SonyTable.C
        sonyD.containsMatchIn(model) -> SonyTable.D
        sonyDMech.containsMatchIn(model) && raw.size >= 5 && (0 until 5).all { raw[it] == 0.toByte() } -> SonyTable.D
        else -> null
    }

    private fun readSony(ctx: Ctx, mn: IfdEntry?, warnings: MutableList<String>): PhotoReading {
        val model = ctx.model.orEmpty()
        if (model.startsWith("DSC-") || model.startsWith("ZV-1")) {
            return ctx.result(null, warnings = warnings,
                problem = "Sony compact cameras (RX100, ZV-1 and similar) don't record a shutter count in photos.")
        }
        if (mn == null) return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)
        val buf = ctx.tiff.buf
        val start = mn.valuePos.toInt()
        if (start < 0 || start + 14 > buf.size) return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)

        val ifdPos = if (startsWith(buf, start, "SONY DSC ") || startsWith(buf, start, "SONY CAM ") ||
            startsWith(buf, start, "SONY MOBILE")) start + 12 else start
        val t = Tiff(buf, ctx.tiff.base, ctx.tiff.littleEndian)
        if (!t.looksLikeIfd(ifdPos)) t.littleEndian = !t.littleEndian
        val ifd = t.readIfd(ifdPos) ?: return ctx.result(null, warnings = warnings, problem = MISSING_MAKERNOTE)

        val e9050 = ifd.find(0x9050)
        val raw = e9050?.let(t::bytes)
            ?: return ctx.result(null, warnings = warnings,
                problem = "This Sony photo has no shutter-count block. The model may not record it, or the file was resized by an app (use the original file).")

        val table = sonyTable(model, raw)
            ?: return ctx.result(null, warnings = warnings,
                problem = "The ${CameraModels.displayName(Brand.SONY, model)} does not store a readable shutter count in photos (or uses the electronic shutter only).")

        val d = SonyCipher.decipher(raw)
        fun u32(off: Int): Long? = if (off + 4 <= d.size)
            ((d[off].toLong() and 0xff) or ((d[off + 1].toLong() and 0xff) shl 8) or
                ((d[off + 2].toLong() and 0xff) shl 16) or ((d[off + 3].toLong() and 0xff) shl 24)) else null
        fun masked(off: Int): Long? = u32(off)?.and(0x00ffffff)
        fun byte(off: Int): Int? = if (off < d.size) d[off].toInt() and 0xff else null

        val details = mutableListOf<Pair<String, String>>()
        var count: Long? = null

        when (table) {
            SonyTable.A -> {
                val flashFired = ((byte(0x31) ?: 0) and 1) == 1
                val sc = masked(0x32)
                val sc2 = if (!flashFired && Regex("^(ILCE-(7(R|S|M2)?|[56]000|5100|QX1))\\b").containsMatchIn(model)) masked(0x4c) else null
                val sc3 = when {
                    Regex("^(ILCE-(5100|QX1)|ILCA-(68|77M2))").containsMatchIn(model) -> u32(0x1a0)
                    Regex("^(SLT-A(58|99V?)|HV|NEX-(3N|5R|5T|6|VG900|VG30E)|ILCE-([35]000|3500))\\b").containsMatchIn(model) -> u32(0x1aa)
                    Regex("^(SLT-A(37|57|65|77)V?|Lunar|NEX-(F3|5N|7|VG20E))").containsMatchIn(model) -> u32(0x1bd)
                    else -> null
                }?.takeIf { it > 0 }
                count = when {
                    sc2 != null && sc2 > 0 -> sc2
                    sc3 != null && sc != null && sc3 > sc -> sc3
                    else -> sc
                }
                if (sc2 == null && flashFired && Regex("^(ILCE-(7(R|S|M2)?|[56]000|5100|QX1))\\b").containsMatchIn(model)) {
                    warnings += "The flash fired in this photo. On this model the most accurate counter is skipped when the flash fires; take a new photo without flash for the best reading."
                }
                if (count != null && count < 65536 && sc2 == null && sc3 == null && model.startsWith("ILCE-7")) {
                    warnings += "On this model the count can roll over after 65,536. If the camera looks heavily used, add 65,536."
                }
            }
            SonyTable.B, SonyTable.C -> count = masked(0x3a)
            SonyTable.D -> {
                count = masked(0x0a)
                details += "Note" to "Counts mechanical-shutter shots only (electronic/silent shots are not included)."
            }
        }

        if (count == null || count == 0L) {
            return ctx.result(null, warnings = warnings,
                problem = "Couldn't find a shutter count in this Sony photo. If you used the silent/electronic shutter, take a new photo with the mechanical shutter.")
        }
        return ctx.result(count, details = details, warnings = warnings)
    }

    // --------------------------------------------------------------- helpers

    private const val MISSING_MAKERNOTE =
        "This photo is missing the camera's private data (maker notes). That usually means it was resized, edited or sent through an app like WhatsApp. Use the original file from the memory card."

    private class Ctx(
        val tiff: Tiff,
        val brand: Brand,
        val make: String?,
        val model: String?,
        val software: String?,
        val dateTaken: String?,
        val bodySerial: String?,
    ) {
        fun result(
            count: Long?,
            serial: String? = bodySerial,
            details: List<Pair<String, String>> = emptyList(),
            warnings: List<String> = emptyList(),
            problem: String? = null,
        ) = PhotoReading(
            brand = brand,
            make = make,
            model = model,
            displayName = CameraModels.displayName(brand, model),
            shutterCount = count,
            ratedLife = CameraModels.rating(brand, model)?.value,
            ratedOfficial = CameraModels.rating(brand, model)?.official ?: false,
            serial = serial,
            dateTaken = dateTaken,
            software = software,
            details = details,
            warnings = warnings,
            problem = problem,
        )
    }

    private fun failure(message: String) = PhotoReading(
        brand = Brand.OTHER, make = null, model = null, displayName = "Unknown camera", shutterCount = null,
        ratedLife = null, serial = null, dateTaken = null, software = null,
        details = emptyList(), warnings = emptyList(), problem = message,
    )

    private fun startsWith(buf: ByteArray, pos: Int, s: String): Boolean {
        if (pos < 0 || pos + s.length > buf.size) return false
        for (i in s.indices) if (buf[pos + i] != s[i].code.toByte()) return false
        return true
    }

    /** Finds the TIFF header: start of file for NEF/ARW/TIFF, or inside APP1 for JPEG. */
    internal fun findTiffStart(data: ByteArray): Int? {
        if (data.size < 8) return null
        val b0 = data[0].toInt() and 0xff
        val b1 = data[1].toInt() and 0xff
        if ((b0 == 0x49 && b1 == 0x49) || (b0 == 0x4D && b1 == 0x4D)) return 0
        if (b0 != 0xFF || b1 != 0xD8) return null
        var p = 2
        while (p + 4 <= data.size) {
            if ((data[p].toInt() and 0xff) != 0xFF) { p++; continue }
            val marker = data[p + 1].toInt() and 0xff
            if (marker == 0xFF) { p++; continue }
            if (marker == 0xD9 || marker == 0xDA) return null
            if (marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            val len = ((data[p + 2].toInt() and 0xff) shl 8) or (data[p + 3].toInt() and 0xff)
            if (len < 2) return null
            if (marker == 0xE1 && startsWith(data, p + 4, "Exif\u0000\u0000") && p + 10 + 8 <= data.size) {
                return p + 10
            }
            p += 2 + len
        }
        return null
    }
}
