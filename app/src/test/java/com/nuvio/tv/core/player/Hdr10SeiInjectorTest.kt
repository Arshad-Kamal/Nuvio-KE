package com.nuvio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build-then-parse round trips for the HDR10 static-metadata SEI authoring and
 * the length-delimited injection (formerly a startup self-test that only logged).
 */
class Hdr10SeiInjectorTest {

    private data class ParsedSei(val type: Int, val payload: ByteArray)

    private fun parseSeiNal(nal: ByteArray): ParsedSei {
        assertTrue("NAL too short", nal.size >= 4)
        assertEquals("prefix-SEI NAL type", 39, (nal[0].toInt() ushr 1) and 0x3F)
        val rbsp = unescapeRbsp(nal, 2)
        val type = rbsp[0].toInt() and 0xFF
        val size = rbsp[1].toInt() and 0xFF
        assertTrue("payload overruns RBSP", 2 + size < rbsp.size)
        assertEquals("rbsp_trailing_bits", 0x80, rbsp[2 + size].toInt() and 0xFF)
        return ParsedSei(type, rbsp.copyOfRange(2, 2 + size))
    }

    // Drops each emulation-prevention 0x03 that follows two zero bytes.
    private fun unescapeRbsp(nal: ByteArray, from: Int): ByteArray {
        val out = ArrayList<Byte>(nal.size)
        var zeros = 0
        var i = from
        while (i < nal.size) {
            val v = nal[i].toInt() and 0xFF
            if (zeros >= 2 && v == 0x03) {
                zeros = 0
                i++
                continue
            }
            out.add(nal[i])
            zeros = if (v == 0) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    private fun u16(a: ByteArray, off: Int): Int =
        ((a[off].toInt() and 0xFF) shl 8) or (a[off + 1].toInt() and 0xFF)

    private fun u32(a: ByteArray, off: Int): Long =
        ((a[off].toLong() and 0xFF) shl 24) or ((a[off + 1].toLong() and 0xFF) shl 16) or
            ((a[off + 2].toLong() and 0xFF) shl 8) or (a[off + 3].toLong() and 0xFF)

    private fun lengthDelimited(vararg nals: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (n in nals) {
            out += byteArrayOf(
                (n.size ushr 24).toByte(), (n.size ushr 16).toByte(),
                (n.size ushr 8).toByte(), n.size.toByte()
            ) + n
        }
        return out
    }

    private fun nalTypes(sample: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var pos = 0
        while (pos + 4 <= sample.size) {
            val size = u32(sample, pos).toInt()
            types += (sample[pos + 4].toInt() ushr 1) and 0x3F
            pos += 4 + size
        }
        return types
    }

    private val sps = byteArrayOf((33 shl 1).toByte(), 0x01, 0x11, 0x22)
    private val slice = byteArrayOf((1 shl 1).toByte(), 0x01, 0x33, 0x44, 0x55)

    @Test
    fun mdcv_roundTrip_bt2020PrimariesAndPqLuminance() {
        // ~0.005 and ~1000 nits from representative 12-bit PQ codes.
        val parsed = parseSeiNal(Hdr10SeiInjector.buildMdcvSeiNal(sourceMinPq = 62, sourceMaxPq = 3079))

        assertEquals(137, parsed.type)
        assertEquals(24, parsed.payload.size)
        assertEquals(8500, u16(parsed.payload, 0))    // G.x
        assertEquals(16450, u16(parsed.payload, 14))  // white point y (D65)
        assertTrue(u32(parsed.payload, 16) in 9_000_000L..11_000_000L) // max, 0.0001 cd/m^2
    }

    @Test
    fun mdcv_zeroRuns_areEmulationEscaped() {
        // sourceMinPq = 0 makes min luminance 00 00 00 00, which must be escaped on the wire.
        val nal = Hdr10SeiInjector.buildMdcvSeiNal(sourceMinPq = 0, sourceMaxPq = 3079)
        val hasEscape = (0 until nal.size - 2).any {
            nal[it].toInt() == 0 && nal[it + 1].toInt() == 0 && nal[it + 2].toInt() == 3
        }
        assertTrue("expected an emulation-prevention byte", hasEscape)

        val parsed = parseSeiNal(nal)
        assertEquals(24, parsed.payload.size)
        assertArrayEquals(ByteArray(4), parsed.payload.copyOfRange(20, 24))
    }

    @Test
    fun clli_roundTrip_andUnknownSuppressed() {
        val clli = Hdr10SeiInjector.buildClliSeiNal(617, 496)
        assertNotNull(clli)
        val parsed = parseSeiNal(clli!!)

        assertEquals(144, parsed.type)
        assertEquals(617, u16(parsed.payload, 0))
        assertEquals(496, u16(parsed.payload, 2))
        assertNull(Hdr10SeiInjector.buildClliSeiNal(0, 0))
    }

    @Test
    fun injectLengthDelimited_insertsBeforeFirstVcl_andIsDetected() {
        val sample = lengthDelimited(sps, slice)
        val mdcv = Hdr10SeiInjector.buildMdcvSeiNal(62, 3079)
        assertFalse(Hdr10SeiInjector.hasHdr10StaticSei(sample, sample.size, 4))

        val injected = Hdr10SeiInjector.injectLengthDelimited(sample, sample.size, 4, listOf(mdcv))

        assertEquals(listOf(33, 39, 1), nalTypes(injected))
        assertTrue(Hdr10SeiInjector.hasHdr10StaticSei(injected, injected.size, 4))
    }

    @Test
    fun hasHdr10StaticSei_detectsClliAlone() {
        // CLLI alone also marks a viewable DV5 base layer (the MKV strip decision).
        val sample = lengthDelimited(sps, slice)
        val clli = Hdr10SeiInjector.buildClliSeiNal(617, 496)!!

        val injected = Hdr10SeiInjector.injectLengthDelimited(sample, sample.size, 4, listOf(clli))

        assertTrue(Hdr10SeiInjector.hasHdr10StaticSei(injected, injected.size, 4))
    }

    @Test
    fun hasHdr10StaticSei_stopsOnOverflowingLengthField() {
        // A length near Int.MAX_VALUE must end the walk, not wrap the bounds check.
        val bad = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte()) + sps
        assertFalse(Hdr10SeiInjector.hasHdr10StaticSei(bad, bad.size, 4))
    }
}
