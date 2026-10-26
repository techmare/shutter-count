package com.techmare.shuttercount

import com.techmare.shuttercount.core.Brand
import com.techmare.shuttercount.core.CameraModels
import com.techmare.shuttercount.core.PhotoShutterReader
import com.techmare.shuttercount.core.SonyCipher
import com.techmare.shuttercount.ptp.CanonReader
import com.techmare.shuttercount.ptp.FactoryCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReaderTests {

    /** Builds a minimal Sony TIFF with an enciphered 0x9050 block holding [count] at [offset]. */
    private fun sonyTiff(model: String, offset: Int, count: Long, header: Boolean = true): ByteArray {
        val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(tag: Int, type: Int, n: Int, v: Int) {
            bb.putShort(tag.toShort()); bb.putShort(type.toShort()); bb.putInt(n); bb.putInt(v)
        }
        bb.put("II".toByteArray()); bb.putShort(42); bb.putInt(8)
        bb.position(8); bb.putShort(3)
        entry(0x10F, 2, 5, 200); entry(0x110, 2, model.length + 1, 220); entry(0x8769, 4, 1, 300); bb.putInt(0)
        bb.position(200); bb.put("SONY\u0000".toByteArray())
        bb.position(220); bb.put((model + "\u0000").toByteArray())
        val mnLen = (if (header) 12 else 0) + 18
        bb.position(300); bb.putShort(1); entry(0x927C, 7, mnLen, 400); bb.putInt(0)
        bb.position(400); if (header) bb.put("SONY DSC \u0000\u0000\u0000".toByteArray())
        bb.putShort(1); entry(0x9050, 7, 944, 1000); bb.putInt(0)
        val plain = ByteArray(944)
        for (i in 0 until 3) plain[offset + i] = ((count shr (8 * i)) and 0xff).toByte()
        plain[offset + 3] = 0x55 // high byte is masked off
        bb.position(1000); bb.put(SonyCipher.encipher(plain))
        return bb.array()
    }

    private fun canonTiff(model: String, offset: Int, count: Long): ByteArray {
        val bb = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(tag: Int, type: Int, n: Int, v: Int) {
            bb.putShort(tag.toShort()); bb.putShort(type.toShort()); bb.putInt(n); bb.putInt(v)
        }
        bb.put("II".toByteArray()); bb.putShort(42); bb.putInt(8)
        bb.position(8); bb.putShort(3)
        entry(0x10F, 2, 6, 200); entry(0x110, 2, model.length + 1, 220); entry(0x8769, 4, 1, 300); bb.putInt(0)
        bb.position(200); bb.put("Canon\u0000".toByteArray())
        bb.position(220); bb.put((model + "\u0000").toByteArray())
        bb.position(300); bb.putShort(1); entry(0x927C, 7, 30, 400); bb.putInt(0)
        bb.position(400); bb.putShort(2); entry(0x000C, 4, 1, 123456789); entry(0x000D, 7, offset + 64, 1000); bb.putInt(0)
        bb.position(1000 + offset); bb.putInt(count.toInt())
        return bb.array()
    }

    @Test fun canonRPhotos() {
        assertEquals(12345L, PhotoShutterReader.read(canonTiff("Canon EOS R50", 0x0d29, 12345)).shutterCount)
        assertEquals(777L, PhotoShutterReader.read(canonTiff("Canon EOS R8", 0x0d29, 777)).shutterCount)
        assertEquals(45678L, PhotoShutterReader.read(canonTiff("Canon EOS R6", 0x0af1, 45678)).shutterCount)
        assertNull(PhotoShutterReader.read(canonTiff("Canon EOS 5D Mark IV", 0x0d29, 5)).shutterCount)
    }

    @Test fun cipherRoundTrip() {
        val all = ByteArray(256) { it.toByte() }
        assertTrue(SonyCipher.decipher(SonyCipher.encipher(all)).contentEquals(all))
        assertEquals(0x5e, SonyCipher.encipher(byteArrayOf(7))[0].toInt() and 0xff)
    }

    @Test fun sonyModels() {
        val cases = listOf(
            Triple("ILCE-7M3", 0x3a, 23456L), Triple("ILCE-7M4", 0x3a, 777L), Triple("ILCE-6400", 0x3a, 9999L),
            Triple("ILCE-6700", 0x0a, 4321L), Triple("ILCE-6000", 0x4c, 87654L), Triple("SLT-A99V", 0x32, 5000L),
        )
        for ((model, off, count) in cases) for (header in listOf(true, false)) {
            assertEquals(model, count, PhotoShutterReader.read(sonyTiff(model, off, count, header)).shutterCount)
        }
    }

    @Test fun sonyGlobalShutterHasNoCount() {
        assertNull(PhotoShutterReader.read(sonyTiff("ILCE-9M3", 0x3a, 10)).shutterCount)
    }

    @Test fun garbageIsHandled() {
        assertNull(PhotoShutterReader.read(ByteArray(0)).shutterCount)
        assertNull(PhotoShutterReader.read(ByteArray(5000) { (it * 31).toByte() }).shutterCount)
    }

    @Test fun ratedLife() {
        assertEquals(150_000, CameraModels.ratedLife(Brand.NIKON, "NIKON D750"))
        assertEquals(200_000, CameraModels.ratedLife(Brand.NIKON, "NIKON Z 6_2"))
        assertEquals(150_000, CameraModels.ratedLife(Brand.CANON, "Canon EOS 5D Mark IV"))
        assertEquals(500_000, CameraModels.ratedLife(Brand.SONY, "ILCE-7RM4"))
        assertEquals("Nikon Z6 II", CameraModels.displayName(Brand.NIKON, "NIKON Z 6_2"))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Payloads captured from a working tool on an EOS 5D Mark IV (via openshuttercounter). */
    @Test fun canonPayloadsMatchCapture() {
        assertEquals(
            "4d6f6e4f70656e00" + "00000000" + "0".repeat(48),
            hex(FactoryCommand("MonOpen").marshal()),
        )
        assertEquals(
            "4d6f6e52656164416e644765744461746100" + "03000000" +
                "0200000002000000000000000000000000000000" +
                "020000000b100000000000000000000000000000" +
                "020000000a00000000000000000000000000000000000000",
            hex(FactoryCommand.monRead(0x100B, 10).marshal()),
        )
        assertTrue(runCatching { FactoryCommand("MonWrite").marshal() }.isFailure)
    }

    @Test fun canonCounterDecode() {
        fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals(26354L, CanonReader.decodeMpuCounter(h("0066000000000000f200")))
        assertEquals(26374L, CanonReader.decodeMpuCounter(h("00670000000000000006")))
    }
}
