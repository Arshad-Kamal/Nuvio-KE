package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `choose` rows ported from ysosrs f37b5c231 (`percentResumeDue` was not picked); `shouldPrimeFromZero` is Spec 57 D2, `readsSavedResumeOnSwitch` D4. */
class PlayerStartPositionTest {

    @Test
    fun `a position carried over by a rebuild is the start, not a seek after the buffer filled`() {
        assertEquals(600_000L, PlayerStartPosition.choose(600_000L) { 0L })
    }

    @Test
    fun `the carried over position wins, then the saved resume`() {
        assertEquals(600_000L, PlayerStartPosition.choose(600_000L) { 9L })
        assertEquals(9L, PlayerStartPosition.choose(null) { 9L })
        assertEquals(9L, PlayerStartPosition.choose(0L) { 9L })
    }

    @Test
    fun `the saved resume is not read when a position was carried over`() {
        var read = false
        PlayerStartPosition.choose(600_000L) { read = true; 9L }
        assertEquals(false, read)
    }

    private fun prime(
        container: String? = MimeTypes.VIDEO_MP4,
        sample: String? = MimeTypes.VIDEO_DOLBY_VISION,
        initData: List<ByteArray> = listOf(dvcC),
        startMs: Long = 207_202L,
        tunneled: Boolean = false,
    ) = PlayerStartPosition.shouldPrimeFromZero(
        PlayerStartPosition.needsWarmDecoder(container, sample, initData, tunneled),
        startMs,
    )

    @Test
    fun `a DV or HEVC MP4 with no out-of-band parameter sets resumed past 0 primes from 0`() {
        assertTrue(prime())
        assertTrue(prime(sample = MimeTypes.VIDEO_H265))
    }

    @Test
    fun `a start at 0 never primes, but the track still needs a warm decoder (an error retry rebuilds)`() {
        assertFalse(prime(startMs = 0L))
        assertTrue(PlayerStartPosition.needsWarmDecoder(MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H265, emptyList(), false))
    }

    @Test
    fun `parameter sets in the sample entry let a cold decoder start mid-file`() {
        assertFalse(prime(initData = listOf(hvcCParameterSets, dvcC)))
        assertFalse(prime(sample = MimeTypes.VIDEO_H265, initData = listOf(hvcCParameterSets)))
    }

    @Test
    fun `an HEVC track with an empty hvcC primes`() {
        assertTrue(prime(sample = MimeTypes.VIDEO_H265, initData = emptyList()))
    }

    @Test
    fun `other containers and codecs keep the direct start`() {
        assertFalse(prime(container = MimeTypes.VIDEO_MATROSKA))
        assertFalse(prime(container = null))
        assertFalse(prime(sample = MimeTypes.VIDEO_H264))
        assertFalse(prime(sample = null))
    }

    @Test
    fun `tunneled playback is left out (no first-frame signal ends the prime)`() {
        assertFalse(prime(tunneled = true))
    }

    @Test
    fun `a switch reads the saved resume only when nothing live seeded it and no start-from-beginning is pending`() {
        assertTrue(PlayerStartPosition.readsSavedResumeOnSwitch(seededFromLivePosition = false, startFromBeginningPending = false))
        assertFalse(PlayerStartPosition.readsSavedResumeOnSwitch(seededFromLivePosition = true, startFromBeginningPending = false))
        assertFalse(PlayerStartPosition.readsSavedResumeOnSwitch(seededFromLivePosition = false, startFromBeginningPending = true))
        assertFalse(PlayerStartPosition.readsSavedResumeOnSwitch(seededFromLivePosition = true, startFromBeginningPending = true))
    }

    private companion object {
        /** A dvcC body as Media3 appends it: version 1.0, profile 5 level 6, rpu/bl flags, zero padding. */
        val dvcC = byteArrayOf(1, 0, 0x0a, 0x35.toByte()) + ByteArray(20)

        /** hvcC parameter sets as HevcConfig writes them: start code, then a VPS NAL header (type 32). */
        val hvcCParameterSets = byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0c, 0x01)
    }
}
