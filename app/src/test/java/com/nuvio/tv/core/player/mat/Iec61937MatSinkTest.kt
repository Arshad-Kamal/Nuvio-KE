package com.nuvio.tv.core.player.mat

import org.junit.Assert.assertEquals
import org.junit.Test

class Iec61937MatSinkTest {

    @Test
    fun `head below 2^31 frames is returned unchanged`() {
        assertEquals(0L, Iec61937MatSink.unsignedHead(0))
        assertEquals(192_000L, Iec61937MatSink.unsignedHead(192_000))
        assertEquals(2_147_483_647L, Iec61937MatSink.unsignedHead(Int.MAX_VALUE))
    }

    @Test
    fun `head past 2^31 frames stays positive instead of reading negative`() {
        // ~3.1 h at 192 kHz: AudioTrack's unsigned counter arrives as a negative Int.
        assertEquals(2_147_483_648L, Iec61937MatSink.unsignedHead(Int.MIN_VALUE))
        assertEquals(4_294_967_295L, Iec61937MatSink.unsignedHead(-1))
    }
}
