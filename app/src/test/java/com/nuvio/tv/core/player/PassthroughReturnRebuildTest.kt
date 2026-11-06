package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T14 (upstream-sync Register A2 P6): rebuild only when the player was built while the receiver
 * was away (pinned capabilities could not pass the track) and the receiver now takes it.
 */
class PassthroughReturnRebuildTest {

    private fun decide(
        isBitstreamTrack: Boolean = true,
        pinnedPasses: Boolean? = false,
        livePasses: Boolean = true,
        sinkBitstreaming: Boolean = false,
        policyDenies: Boolean = false,
        pcmByChoice: Boolean = false,
        rebuildsSoFar: Int = 0
    ) = shouldRebuildForReturnedPassthrough(
        isBitstreamTrack, pinnedPasses, livePasses, sinkBitstreaming, policyDenies, pcmByChoice, rebuildsSoFar
    )

    @Test
    fun `built while the AVR slept, now it takes the track - rebuild`() {
        assertTrue(decide())
    }

    @Test
    fun `pinned capabilities already passed it - PCM is not about the receiver`() {
        assertFalse(decide(pinnedPasses = true))
    }

    @Test
    fun `no pinned capabilities (live receiver or Bluetooth build) - never`() {
        assertFalse(decide(pinnedPasses = null))
    }

    @Test
    fun `receiver still does not take it - wait for the next add`() {
        assertFalse(decide(livePasses = false))
    }

    @Test
    fun `already bitstreaming, not a bitstream track, denied, or PCM by choice - never`() {
        assertFalse(decide(sinkBitstreaming = true))
        assertFalse(decide(isBitstreamTrack = false))
        assertFalse(decide(policyDenies = true))
        assertFalse(decide(pcmByChoice = true))
    }

    @Test
    fun `at most three rebuilds per stream`() {
        assertTrue(decide(rebuildsSoFar = 2))
        assertFalse(decide(rebuildsSoFar = 3))
    }
}
