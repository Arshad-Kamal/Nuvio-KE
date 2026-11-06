package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Register A2 P10: V_MS/VFW/FOURCC MPEG-4 ASP FourCCs map to video/mp4v-es. */
class MatroskaFourCcTest {

    /** The BITMAPINFOHEADER compression field as read (little-endian unsigned int). */
    private fun fourCc(code: String): Long =
        code.toByteArray(Charsets.US_ASCII).foldIndexed(0L) { i, acc, b ->
            acc or ((b.toLong() and 0xFF) shl (8 * i))
        }

    @Test
    fun `MPEG-4 ASP FourCCs are recognised`() {
        listOf("DIVX", "divx", "DX50", "XVID", "xvid", "FMP4", "MP4V").forEach {
            assertTrue(it, MatroskaExtractor.Track.isMpeg4AspFourCc(fourCc(it)))
        }
    }

    @Test
    fun `other FourCCs are not MPEG-4 ASP`() {
        listOf("H263", "WVC1", "MJPG", "DIV3").forEach {
            assertFalse(it, MatroskaExtractor.Track.isMpeg4AspFourCc(fourCc(it)))
        }
    }
}
