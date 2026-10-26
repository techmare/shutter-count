package com.techmare.shuttercount.core

/**
 * Minimal, bounds-checked TIFF/EXIF reader. Every read returns null instead of
 * throwing when the data is short or corrupt, so a damaged photo gives a clean
 * "could not read" message rather than a crash.
 */
internal class Tiff(
    val buf: ByteArray,
    /** Absolute position that IFD value offsets are relative to. */
    val base: Int,
    var littleEndian: Boolean,
) {
    fun u8(pos: Int): Int? = if (pos in buf.indices) buf[pos].toInt() and 0xff else null

    fun u16(pos: Int): Int? {
        if (pos < 0 || pos + 2 > buf.size) return null
        val a = buf[pos].toInt() and 0xff
        val b = buf[pos + 1].toInt() and 0xff
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    fun u32(pos: Int): Long? {
        if (pos < 0 || pos + 4 > buf.size) return null
        val b0 = (buf[pos].toLong() and 0xff)
        val b1 = (buf[pos + 1].toLong() and 0xff)
        val b2 = (buf[pos + 2].toLong() and 0xff)
        val b3 = (buf[pos + 3].toLong() and 0xff)
        return if (littleEndian) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Reads the IFD at absolute position [pos]. Returns null if it does not look like an IFD. */
    fun readIfd(pos: Int): List<IfdEntry>? {
        val count = u16(pos) ?: return null
        if (count == 0 || count > 1000) return null
        if (pos + 2 + count * 12 > buf.size) return null
        val out = ArrayList<IfdEntry>(count)
        for (i in 0 until count) {
            val e = pos + 2 + i * 12
            val tag = u16(e) ?: return null
            val type = u16(e + 2) ?: return null
            val n = u32(e + 4) ?: return null
            val size = typeSize(type) * n
            val valuePos: Long = if (size <= 4) (e + 8).toLong() else {
                val off = u32(e + 8) ?: return null
                base + off
            }
            out += IfdEntry(tag, type, n, valuePos, size)
        }
        return out
    }

    /** True if the IFD at [pos] parses with sane entry types in the current byte order. */
    fun looksLikeIfd(pos: Int): Boolean {
        val entries = readIfd(pos) ?: return false
        return entries.isNotEmpty() && entries.all { it.type in 1..13 }
    }

    fun bytes(e: IfdEntry): ByteArray? {
        if (e.size <= 0 || e.size > Int.MAX_VALUE) return null
        val start = e.valuePos
        val end = start + e.size
        if (start < 0 || end > buf.size) return null
        return buf.copyOfRange(start.toInt(), end.toInt())
    }

    fun string(e: IfdEntry): String? {
        val b = bytes(e) ?: return null
        val nul = b.indexOf(0.toByte()).let { if (it < 0) b.size else it }
        return String(b, 0, nul, Charsets.ISO_8859_1).trim().ifEmpty { null }
    }

    /** First integer value of a SHORT or LONG entry. */
    fun int(e: IfdEntry): Long? = when (e.type) {
        3, 8 -> u16(e.valuePos.toInt())?.toLong()
        4, 9, 13 -> u32(e.valuePos.toInt())
        1, 7 -> u8(e.valuePos.toInt())?.toLong()
        else -> null
    }

    companion object {
        fun typeSize(type: Int): Long = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 1
        }
    }
}

internal data class IfdEntry(
    val tag: Int,
    val type: Int,
    val count: Long,
    /** Absolute position of the value bytes in the buffer. */
    val valuePos: Long,
    val size: Long,
)

internal fun List<IfdEntry>.find(tag: Int): IfdEntry? = firstOrNull { it.tag == tag }
