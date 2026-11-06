package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 56 T1: the sweep's chunk ladder stops at the RAM tier's chunk ceiling. */
class StreamSweepEngineChunkLadderTest {

    @Test
    fun `the low-RAM tier ladder stops at 16 MB`() {
        assertEquals(listOf(8, 16), StreamSweepEngine.sweepChunkLadderMb(tierMaxChunkMb = 16))
    }

    @Test
    fun `the high tier ladder reaches 32 MB`() {
        assertEquals(listOf(8, 16, 32), StreamSweepEngine.sweepChunkLadderMb(tierMaxChunkMb = 32))
    }
}
