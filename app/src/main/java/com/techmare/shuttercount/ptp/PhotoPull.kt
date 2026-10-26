package com.techmare.shuttercount.ptp

/**
 * Fetches the newest photo from a connected camera's card using standard,
 * read-only PTP operations (the same ones every photo-transfer app uses):
 * GetStorageIDs, GetObjectHandles, GetObjectInfo, GetPartialObject / GetObject.
 */
class PhotoPull(private val session: PtpSession, private val ops: Set<Int>, private val log: MutableList<String>) {

    data class Pulled(val fileName: String, val captureDate: String?, val bytes: ByteArray, val partial: Boolean)

    internal data class Info(val handle: Long, val format: Int, val size: Long, val name: String, val captured: String)

    /** Newest image on the camera, or null when there's none. Reads only the start of the file when possible. */
    fun latest(): Pulled? {
        val newest = newestImage() ?: return null
        val canPartial = ops.contains(OP_GET_PARTIAL_OBJECT)
        val bytes = if (canPartial) {
            val r = session.transact(OP_GET_PARTIAL_OBJECT, listOf(newest.handle, 0L, HEADER_BYTES.toLong()))
            log += "GetPartialObject ${newest.name}: ${hex(r.code)}, ${r.data.size} bytes"
            if (r.ok && r.data.isNotEmpty()) r.data else null
        } else null
        if (bytes != null) return Pulled(newest.name, newest.captured.ifBlank { null }, bytes, bytes.size < newest.size)
        return full(newest)
    }

    /** Whole file, for when the camera data isn't in the first part. */
    fun fullOf(p: Pulled): Pulled? = newestCache?.takeIf { it.name == p.fileName }?.let(::full)

    private var newestCache: Info? = null

    private fun full(i: Info): Pulled? {
        if (!ops.contains(OP_GET_OBJECT) || i.size > MAX_FULL) return null
        val r = session.transact(OP_GET_OBJECT, listOf(i.handle))
        log += "GetObject ${i.name}: ${hex(r.code)}, ${r.data.size} bytes"
        return if (r.ok && r.data.isNotEmpty()) Pulled(i.name, i.captured.ifBlank { null }, r.data, false) else null
    }

    private fun newestImage(): Info? {
        val storages = run {
            val r = session.transact(OP_GET_STORAGE_IDS, emptyList())
            log += "GetStorageIDs: ${hex(r.code)}"
            if (!r.ok) return null
            readArray32(r.data).filter { (it and 0xFFFF) != 0L } // skip storages without media
        }
        val handles = ArrayList<Long>()
        for (sid in storages) {
            var r = session.transact(OP_GET_OBJECT_HANDLES, listOf(sid, 0L, 0L))
            if (!r.ok || readArray32(r.data).isEmpty()) r = session.transact(OP_GET_OBJECT_HANDLES, listOf(sid, 0L, 0xFFFFFFFFL))
            val h = if (r.ok) readArray32(r.data) else emptyList()
            log += "GetObjectHandles storage ${"%08X".format(sid)}: ${hex(r.code)}, ${h.size} objects"
            handles += h
        }
        if (handles.isEmpty()) return null
        // Cameras hand out increasing handles, so the newest files are at the end.
        val infos = handles.sorted().takeLast(CANDIDATES).reversed().mapNotNull(::info)
            .filter { it.format != FORMAT_ASSOCIATION && IMAGE_EXT.containsMatchIn(it.name) }
        // Prefer the newest capture; for a RAW+JPEG pair prefer the JPEG (smaller, metadata up front).
        val best = infos.sortedWith(
            compareByDescending<Info> { it.captured }.thenByDescending { it.handle }
        ).let { sorted ->
            val top = sorted.firstOrNull() ?: return null
            sorted.firstOrNull { it.captured == top.captured && it.name.endsWith(".JPG", true) } ?: top
        }
        log += "Newest photo: ${best.name} (${best.captured.ifBlank { "no date" }}, ${best.size} bytes)"
        newestCache = best
        return best
    }

    /** ObjectInfo dataset, PIMA 15740 clause 5.5.2. */
    private fun info(handle: Long): Info? {
        val r = session.transact(OP_GET_OBJECT_INFO, listOf(handle))
        if (!r.ok) return null
        return parseObjectInfo(handle, r.data)
    }

    private fun readArray32(d: ByteArray): List<Long> {
        if (d.size < 4) return emptyList()
        val n = le32(d, 0).toInt()
        if (n < 0 || 4 + n * 4L > d.size) return emptyList()
        return List(n) { le32(d, 4 + it * 4) }
    }

    private fun hex(code: Int) = "%04X".format(code)

    companion object {
        internal fun parseObjectInfo(handle: Long, data: ByteArray): Info {
            val p = PtpSession.Reader(data)
            p.u32()                     // StorageID
            val format = p.u16()        // ObjectFormat
            p.u16()                     // ProtectionStatus
            val size = p.u32()          // ObjectCompressedSize
            p.u16()                     // ThumbFormat
            p.u32()                     // ThumbCompressedSize
            p.u32(); p.u32()            // ThumbPixWidth, ThumbPixHeight
            p.u32(); p.u32(); p.u32()   // ImagePixWidth, ImagePixHeight, ImageBitDepth
            p.u32()                     // ParentObject
            p.u16()                     // AssociationType
            p.u32()                     // AssociationDesc
            p.u32()                     // SequenceNumber
            val name = p.str()          // Filename
            val captured = p.str()      // CaptureDate "YYYYMMDDThhmmss"
            return Info(handle, format, size, name, captured)
        }

        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_GET_PARTIAL_OBJECT = 0x101B
        private const val FORMAT_ASSOCIATION = 0x3001
        private const val HEADER_BYTES = 512 * 1024
        private const val MAX_FULL = 80L * 1024 * 1024
        private const val CANDIDATES = 40
        private val IMAGE_EXT = Regex("\\.(JPG|JPEG|NEF|NRW|ARW|SRF|SR2|CR2|CR3|HIF|HEIF)$", RegexOption.IGNORE_CASE)
    }
}
