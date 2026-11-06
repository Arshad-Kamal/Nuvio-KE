package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which auto-restored addon subtitles attach immediately versus park
 * until the next pause (autoSelectAddonSubtitleDeferringReload).
 */
class AddonSubtitleSeamlessAttachTest {

    private fun seamless(
        usingMpv: Boolean = false,
        preAttached: Boolean = false,
        sidecarAttachable: Boolean = false,
        playing: Boolean = true
    ) = isSeamlessAddonSubtitleAttach(usingMpv, preAttached, sidecarAttachable, playing)

    @Test
    fun `sidecar-capable subtitle attaches while playing`() {
        // Regression (L1-55): SRT/VTT take the no-reload sidecar path, so they
        // must not be parked until the next pause.
        assertTrue(seamless(sidecarAttachable = true))
    }

    @Test
    fun `subtitle needing a media reload parks while playing on Exo`() {
        assertFalse(seamless())
    }

    @Test
    fun `reload case attaches immediately when not playing`() {
        assertTrue(seamless(playing = false))
    }

    @Test
    fun `mpv and pre-attached subtitles attach while playing`() {
        assertTrue(seamless(usingMpv = true))
        assertTrue(seamless(preAttached = true))
    }
}
