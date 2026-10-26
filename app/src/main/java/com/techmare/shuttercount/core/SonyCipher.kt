package com.techmare.shuttercount.core

/**
 * Sony enciphers several maker-note blocks (0x9050, 0x94xx) with a simple
 * substitution: c = b^3 mod 249 for byte values 2..247. Values 0, 1 and
 * 248..255 are left as they are. Same scheme ExifTool documents.
 */
object SonyCipher {
    private val decTable = IntArray(256) { it }.also { t ->
        for (b in 2..247) t[(b * b * b) % 249] = b
    }
    private val encTable = IntArray(256) { it }.also { t ->
        for (b in 2..247) t[b] = (b * b * b) % 249
    }

    fun decipher(src: ByteArray): ByteArray =
        ByteArray(src.size) { decTable[src[it].toInt() and 0xff].toByte() }

    fun encipher(src: ByteArray): ByteArray =
        ByteArray(src.size) { encTable[src[it].toInt() and 0xff].toByte() }
}
