@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.core.player.MemoryUsageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun testParallelOverheadMb() {
        // bufferCount(4) = 4 + 2 = 6
        // overhead = 6 * 32 = 192
        assertEquals(192, MemoryBudget.parallelOverheadMb(4, 32))
    }

    @Test
    fun testGetUsageStatusNativeAutoMode() {
        // When safeLimitMb = 1000, warningLimitMb = 1250
        // 1. totalUsageMb <= safeLimitMb should be SAFE
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, 1250))
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, 1250))
        
        // 2. totalUsageMb > safeLimitMb && totalUsageMb <= warningLimitMb should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, 1250))
        
        // 3. totalUsageMb > warningLimitMb should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, 1250))
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, 1250))
    }

    @Test
    fun testGetUsageStatusManualMode() {
        // When safeLimitMb = 1000, warningLimitMb = 1250
        // 1. totalUsageMb <= safeLimitMb should be SAFE
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, 1250))
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, 1250))
        
        // 2. totalUsageMb > safeLimitMb && totalUsageMb <= warningLimitMb should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, 1250))
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, 1250))
        
        // 3. totalUsageMb > warningLimitMb should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, 1250))
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, 1250))
    }

    @Test
    fun testMemoryBudgetEnforce() {
        // If we choose values well within budget, enforce should return them unchanged
        val withinBuffer = MemoryBudget.MIN_BUFFER_MB
        val withinChunk = MemoryBudget.MIN_CHUNK_MB
        val connections = 2
        val (adjBuf, adjChunk) = MemoryBudget.enforce(withinBuffer, withinChunk, connections)
        assertEquals(withinBuffer, adjBuf)
        assertEquals(withinChunk, adjChunk)
    }

    @Test
    fun sweepCellDepthOnTheLowRamTierIsThePlaybackWindow() {
        // Spec 147 D5/D6: a low-RAM cell measures the window playback uses (2 x connections), never
        // above Spec 56 T1's half-the-safe-budget depth (2 GB rung, safe 250: 125 MB / 16 MB = 7).
        assertEquals(4, MemoryBudget.sweepCellPrefetchDepth(2, 16, 250, lowRamTier = true))
        assertEquals(7, MemoryBudget.sweepCellPrefetchDepth(4, 16, 250, lowRamTier = true))
        assertEquals(8, MemoryBudget.sweepCellPrefetchDepth(2, 16, 250, lowRamTier = false))
        assertEquals(11, MemoryBudget.sweepCellPrefetchDepth(4, 16, 250, lowRamTier = false))
        assertEquals(8, MemoryBudget.sweepCellPrefetchDepth(4, 8, 250, lowRamTier = true))
        assertEquals(4, MemoryBudget.sweepCellPrefetchDepth(2, 8, 250, lowRamTier = true))
        for ((c, chunk) in listOf(2 to 8, 3 to 8, 4 to 8, 2 to 16, 3 to 16, 4 to 16)) {
            // The playback window, or the cell's budget where that is lower (only 4 x 16 here).
            assertEquals(
                minOf(MemoryBudget.prefetchDepthChunks(c, chunk, 250, 100, lowRamTier = true), 125 / chunk),
                MemoryBudget.sweepCellPrefetchDepth(c, chunk, 250, lowRamTier = true)
            )
        }
    }

    @Test
    fun sweepCellIsRefusedWhenTheLowRamBudgetCannotHoldItsFloor() {
        // 1 GB rung (safe 150): 75 / 16 = 4 < 4 + 1.
        assertNull(MemoryBudget.sweepCellPrefetchDepth(4, 16, 150, lowRamTier = true))
        assertEquals(4, MemoryBudget.sweepCellPrefetchDepth(2, 16, 150, lowRamTier = true))
        // 1.5 GB rung (safe 200): 100 / 16 = 6 cuts 4 x 16 below playback's 8 but still runs.
        assertEquals(6, MemoryBudget.sweepCellPrefetchDepth(4, 16, 200, lowRamTier = true))
        assertEquals(8, MemoryBudget.sweepCellPrefetchDepth(4, 8, 200, lowRamTier = true))
        // The absolute concurrent cap binds on any tier.
        assertNull(MemoryBudget.sweepCellPrefetchDepth(3, 64, 2000, lowRamTier = false))
    }

    @Test
    fun chunkIsCappedAtTheTierCeiling() {
        assertEquals(16 * 1024, MemoryBudget.tierCappedChunkKb(32 * 1024, tierMaxMb = 16))
        assertEquals(8 * 1024, MemoryBudget.tierCappedChunkKb(8 * 1024, tierMaxMb = 16))
        assertEquals(32 * 1024, MemoryBudget.tierCappedChunkKb(32 * 1024, tierMaxMb = 32))
    }

    @Test
    fun testBackBufferMbZeroCases() {
        // Zero duration is always zero; unknown bitrate is ungrounded (zero).
        assertEquals(0, MemoryBudget.backBufferMb(0, false, 63.0))
        assertEquals(0, MemoryBudget.backBufferMb(0, true, null))
        assertEquals(0, MemoryBudget.backBufferMb(5_000, false, null))
        assertEquals(0, MemoryBudget.backBufferMb(5_000, true, 0.0))
    }

    @Test
    fun testBackBufferMbDerivesFromBitrateAndSeconds() {
        // 63 Mbps x 5 s = 315 megabits = 37.55 MiB -> 37 (binary MB, matching
        // the targetBufferSizeMb accounting elsewhere in the model)
        assertEquals(37, MemoryBudget.backBufferMb(5_000, false, 63.0))
    }

    @Test
    fun testBackBufferMbRetainDoublesEstimate() {
        assertEquals(75, MemoryBudget.backBufferMb(5_000, true, 63.0))
    }

    @Test
    fun testBackBufferMbTinyEstimateFloorsAtOne() {
        assertEquals(1, MemoryBudget.backBufferMb(1_000, false, 0.001))
    }

    @Test
    fun `prefetch depth on the low-RAM tier is twice the connections on every row`() {
        // Spec 147 D6: never connections + 1, whatever the chunk size or the budget.
        assertEquals(6, MemoryBudget.prefetchDepthChunks(3, 8, 250, 100, lowRamTier = true)) // the Shield's played row
        assertEquals(8, MemoryBudget.prefetchDepthChunks(4, 16, 250, 100, lowRamTier = true)) // the Shield preset
        assertEquals(6, MemoryBudget.prefetchDepthChunks(3, 16, 250, 100, lowRamTier = true))
        assertEquals(8, MemoryBudget.prefetchDepthChunks(4, 8, 250, 100, lowRamTier = true))
        assertEquals(4, MemoryBudget.prefetchDepthChunks(2, 16, 150, 100, lowRamTier = true))
    }

    @Test
    fun `the displayed estimate follows the low-RAM window`() {
        // Depth plus the tier's own session headroom (2 low, 4 above): the Shield's 3 x 8 MB row shows (6 + 2) x 8.
        assertEquals(64, MemoryBudget.displayParallelOverheadMb(3, 8, 250, 100, deepPathActive = true, lowRamTier = true))
        assertEquals(128, MemoryBudget.displayParallelOverheadMb(3, 8, 250, 100, deepPathActive = true, lowRamTier = false))
        assertEquals(48, MemoryBudget.displayParallelOverheadMb(3, 8, 250, 100, deepPathActive = false, lowRamTier = true))
        // The preset's 4 x 16 MB: (8 + 2) x 16.
        assertEquals(160, MemoryBudget.displayParallelOverheadMb(4, 16, 250, 100, deepPathActive = true, lowRamTier = true))
    }

    @Test
    fun `prefetch depth above the low-RAM tier keeps the budget clamp`() {
        // (250 - 150) / 8 = 12, inside [6, 12].
        assertEquals(12, MemoryBudget.prefetchDepthChunks(3, 8, 250, 150, lowRamTier = false))
        // Budget below the floor clamps up to 2 x connections.
        assertEquals(8, MemoryBudget.prefetchDepthChunks(4, 16, 250, 240, lowRamTier = false))
        // Budget above the ceiling clamps down to 4 x connections.
        assertEquals(16, MemoryBudget.prefetchDepthChunks(4, 8, 1000, 100, lowRamTier = false))
    }

    @Test
    fun `target buffer tier cap binds positive values and keeps unset`() {
        // Spec 147 D4: 100 MB on the low-RAM tier; 0 means unset and stays 0.
        assertEquals(100, MemoryBudget.tierCappedTargetMb(150, tierMaxMb = 100))
        assertEquals(75, MemoryBudget.tierCappedTargetMb(75, tierMaxMb = 100))
        assertEquals(0, MemoryBudget.tierCappedTargetMb(0, tierMaxMb = 100))
        assertEquals(400, MemoryBudget.tierCappedTargetMb(400, tierMaxMb = Int.MAX_VALUE))
        // The tier decides the ceiling: 100 on <= 2 GB, none above.
        assertEquals(100, MemoryBudget.tierMaxTargetMbFor(lowRamTier = true))
        assertEquals(Int.MAX_VALUE, MemoryBudget.tierMaxTargetMbFor(lowRamTier = false))
    }

    @Test
    fun `max safe target respects the tier cap`() {
        // The Shield preset's case: (250 - 96) -> 150 in 25 MB steps, then the 2 GB tier's 100.
        assertEquals(150, MemoryBudget.maxSafeTargetMb(250, 96, tierMaxMb = Int.MAX_VALUE))
        assertEquals(100, MemoryBudget.maxSafeTargetMb(250, 96, tierMaxMb = 100))
        // Below the cap the safe-limit maths decides.
        assertEquals(50, MemoryBudget.maxSafeTargetMb(150, 96, tierMaxMb = 100))
    }
}
