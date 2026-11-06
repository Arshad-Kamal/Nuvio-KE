package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Synthetic-NAL tests for the MKV (length-delimited) strip paths:
 * [HevcDvRpuStripper.stripRpuLengthDelimited] and
 * [HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited].
 */
@UnstableApi
class HevcNalStrippersTest {

    /** Builds a single HEVC NAL: 2-byte header + zero-filled payload. */
    private fun nal(type: Int, layerId: Int, totalSize: Int): ByteArray {
        require(totalSize >= 2)
        val out = ByteArray(totalSize)
        out[0] = (((type and 0x3F) shl 1) or ((layerId shr 5) and 0x01)).toByte()
        out[1] = (((layerId and 0x1F) shl 3) or 0x01).toByte()
        return out
    }

    /** Prefix SEI NAL (type 39) holding one message, followed by the RBSP stop bit. */
    private fun seiNal(payloadType: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0x4E, 0x01, payloadType.toByte(), payload.size.toByte()) + payload +
            byteArrayOf(0x80.toByte())

    // ITU-T T.35 HDR10+ signature (country 0xB5, provider 0x003C, oriented code 0x0001) + body.
    private val hdr10PlusPayload = byteArrayOf(
        0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x40
    )

    /** Length-delimits NALs with 4-byte big-endian size fields. */
    private fun sampleOf(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write((n.size ushr 24) and 0xFF)
            out.write((n.size ushr 16) and 0xFF)
            out.write((n.size ushr 8) and 0xFF)
            out.write(n.size and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
    }

    /** Walks a length-delimited sample and returns the NAL type sequence. */
    private fun nalTypesOf(sample: ByteArray, length: Int): List<Int> {
        val types = mutableListOf<Int>()
        var pos = 0
        while (pos + 4 <= length) {
            var len = 0
            for (i in 0 until 4) len = (len shl 8) or (sample[pos + i].toInt() and 0xFF)
            pos += 4
            check(len > 0 && pos + len <= length) { "malformed output at $pos (len=$len)" }
            types += (sample[pos].toInt() ushr 1) and 0x3F
            pos += len
        }
        check(pos == length) { "trailing bytes after last NAL" }
        return types
    }

    @Test
    fun `DV strip drops RPU, unspec63 EL wrappers and layer-id EL NALs`() {
        val sample = sampleOf(
            nal(35, 0, 3),   // AUD
            nal(1, 0, 120),  // base-layer slice
            nal(63, 0, 40),  // EL wrapper
            nal(62, 0, 60),  // RPU
            nal(1, 1, 50)    // layer-1 EL slice
        )
        val out = ExposedByteArrayOutputStream(64)
        assertTrue(HevcDvRpuStripper.stripRpuLengthDelimited(sample, sample.size, 4, out))
        assertEquals(listOf(35, 1), nalTypesOf(out.backingArray(), out.size()))
    }

    @Test
    fun `HDR10+ strip returns null without an HDR10+ SEI`() {
        val sample = sampleOf(
            nal(35, 0, 3),
            seiNal(137, ByteArray(24) { 0x11 }), // mastering display, not HDR10+
            nal(1, 0, 200)
        )
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }

    @Test
    fun `HDR10+ strip drops an HDR10+-only SEI NAL`() {
        val sample = sampleOf(nal(35, 0, 3), seiNal(4, hdr10PlusPayload), nal(1, 0, 200))
        val result = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4)
        assertNotNull(result)
        assertEquals(listOf(35, 1), nalTypesOf(result!!, result.size))
    }

    @Test
    fun `HDR10+ strip keeps the original on a malformed length field`() {
        val valid = sampleOf(nal(35, 0, 3), seiNal(4, hdr10PlusPayload))
        // Trailing NAL whose length field claims far more bytes than remain.
        val sample = valid + byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x02, 0x01)
        assertNull(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4))
    }
}
