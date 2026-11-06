package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Format-capability G2: start engine for Blu-ray transport streams and its session bookkeeping. */
class PlayerStartupEngineTest {

    private val exo = InternalPlayerEngine.EXOPLAYER
    private val mpv = InternalPlayerEngine.MVP_PLAYER
    private val auto = InternalPlayerEngine.AUTO

    @Test
    fun `manual override wins, blu-ray starts on mpv, otherwise the setting`() {
        assertEquals(exo, startupEngineBeforeAuto(override = exo, setting = exo, isBluRayTransportStream = true))
        assertEquals(mpv, startupEngineBeforeAuto(override = null, setting = exo, isBluRayTransportStream = true))
        assertEquals(mpv, startupEngineBeforeAuto(override = null, setting = auto, isBluRayTransportStream = true))
        assertEquals(exo, startupEngineBeforeAuto(override = null, setting = exo, isBluRayTransportStream = false))
        assertEquals(auto, startupEngineBeforeAuto(override = null, setting = auto, isBluRayTransportStream = false))
    }

    @Test
    fun `the started engine is kept for the settings observer, an override is not`() {
        // Whatever chose it (the setting, AUTO, the Blu-ray mpv start), the running engine stays
        // through settings writes: an engine changed in Settings over the paused film (Spec 33 D7)
        // applies from the next start, not under the live player.
        assertEquals(mpv, sessionEngineToKeep(override = null, effective = mpv))
        assertEquals(exo, sessionEngineToKeep(override = null, effective = exo))
        // A manual override is tracked separately.
        assertNull(sessionEngineToKeep(override = exo, effective = exo))
    }
}
