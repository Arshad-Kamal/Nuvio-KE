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
    fun sweepCellDepthSpendsHalfTheSafeBudgetOnTheLowRamTier() {
        // Spec 56 T1, 2 GB rung (safe 250): 125 MB / 16 MB = 7 at any connection count;
        // 0.75 gave 187 / 16 = 11, clamped to 4 x connections (8 at 2 connections).
        assertEquals(7, MemoryBudget.sweepCellPrefetchDepth(2, 16, 250, lowRamTier = true))
        assertEquals(7, MemoryBudget.sweepCellPrefetchDepth(4, 16, 250, lowRamTier = true))
        assertEquals(8, MemoryBudget.sweepCellPrefetchDepth(2, 16, 250, lowRamTier = false))
        assertEquals(11, MemoryBudget.sweepCellPrefetchDepth(4, 16, 250, lowRamTier = false))
        // 8 MB: 125 / 8 = 15, under the 4 x 4 clamp; at 2 connections the clamp (8) binds.
        assertEquals(15, MemoryBudget.sweepCellPrefetchDepth(4, 8, 250, lowRamTier = true))
        assertEquals(8, MemoryBudget.sweepCellPrefetchDepth(2, 8, 250, lowRamTier = true))
    }

    @Test
    fun sweepCellIsRefusedWhenTheLowRamBudgetCannotHoldItsFloor() {
        // 1 GB rung (safe 150): 75 / 16 = 4 < 4 + 1.
        assertNull(MemoryBudget.sweepCellPrefetchDepth(4, 16, 150, lowRamTier = true))
        assertEquals(4, MemoryBudget.sweepCellPrefetchDepth(2, 16, 150, lowRamTier = true))
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
}
