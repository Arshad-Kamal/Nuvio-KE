package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-1: a converted DV7 start never tunnels; everything else keeps the user's setting. */
class DvConversionTunnelingGuardTest {

    @Test
    fun `a converted dv7 track is kept out of tunnel mode`() {
        assertTrue(dvConversionBlocksTunneling("dvhe.08.06", codecStringRewrites = 1))
        assertTrue(dvConversionBlocksTunneling("dvh1.08.06", codecStringRewrites = 3))
    }

    @Test
    fun `native profile 8 and unconverted tracks keep tunnelling`() {
        // A native DV8.1 file: no rewrite happened this playback.
        assertFalse(dvConversionBlocksTunneling("dvhe.08.06", codecStringRewrites = 0))
        // Conversion disarmed (the 4001 retry) leaves DV7 as is.
        assertFalse(dvConversionBlocksTunneling("dvhe.07.06", codecStringRewrites = 0))
        // Non-DV video in a playback that converted something.
        assertFalse(dvConversionBlocksTunneling("hvc1.2.4.L153.B0", codecStringRewrites = 1))
        assertFalse(dvConversionBlocksTunneling(null, codecStringRewrites = 1))
    }
}
