package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.core.player.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the hand-built Shield preset (docs/design/mockups/shield-preset.webp;
 * docs/project/device-assessment-analysis-2026-08-28.md): the approved values,
 * the memory math shown on the card, and the untouched device-verified fields.
 */
class ShieldPresetTest {

    @Test
    fun `plan carries the approved Shield values`() {
        val plan = ShieldPreset.plan
        assertEquals(true, plan.nuvioPerformanceModeEnabled)
        assertEquals(true, plan.bufferEngineEnabled)
        assertEquals(true, plan.parallelNetworkEnabled)
        // Managed budget ON would make the runtime ignore the stored target
        // (updateSettings substitutes the RAM-tier safe limit).
        assertEquals(false, plan.bufferBudgetManaged)
        assertEquals(true, plan.allowLargeTargetBuffer)
        assertEquals(200, plan.targetBufferSizeMb)
        assertEquals(120_000, plan.maxBufferMs)
        assertEquals(true, plan.useParallelConnections)
        assertEquals(4, plan.parallelConnectionCount)
        assertEquals(16_384, plan.parallelChunkSizeKb)
        assertEquals(false, plan.enableHttp2)
        assertEquals(FrameRateMatchingMode.START_STOP, plan.frameRateMatchingMode)
        assertEquals(true, plan.resolutionMatchingEnabled)
        assertEquals(0, plan.backBufferDurationMs)
        assertEquals(false, plan.retainBackBufferFromKeyframe)
        assertEquals(true, plan.autoSwitchInternalPlayerOnError)
    }

    @Test
    fun `device-verified surfaces stay untouched`() {
        val plan = ShieldPreset.plan
        assertNull(plan.dv7HandlingMode)
        assertNull(plan.forceOpticalPassthrough)
        assertNull(plan.deniedCodecHandling)
        assertNull(plan.allowAc3Passthrough)
        assertNull(plan.allowEac3Passthrough)
        assertNull(plan.allowTrueHdPassthrough)
        assertNull(plan.allowDtsPassthrough)
        assertNull(plan.allowDtsHdPassthrough)
        assertNull(plan.matPassthroughEnabled)
    }

    @Test
    fun `memory math matches the card and stays inside the 3 GB tier limits`() {
        val plan = ShieldPreset.plan
        val overheadMb = MemoryBudget.parallelOverheadMb(
            plan.parallelConnectionCount ?: 0,
            ((plan.parallelChunkSizeKb ?: 0) + 1023) / 1024
        )
        val totalMb = (plan.targetBufferSizeMb ?: 0) + overheadMb
        // Unclamped on a tier whose safe limit fits the pinned target (350 MB).
        assertEquals(ShieldPreset.effectiveEstimatedNativeMb(350), totalMb)
        assertEquals(296, totalMb)
        // Deliberately conservative budget: since the F3 re-tiering
        // (2026-08-28) it sits under even the <3.2 GB tier's 350 MB safe
        // limit, with headroom to the 450 MB warning limit.
        assertTrue("native use must stay inside the conservative budget", totalMb <= 325)
        // With the back buffer pinned off, nothing else erodes the headroom.
        assertEquals(0, plan.backBufferDurationMs ?: -1)
    }

    @Test
    fun `effective figures preview the applier's safe-limit clamp`() {
        // Nominal tier (≥3.2 GB class): the pinned 200/296 lands unclamped.
        assertEquals(200, ShieldPreset.effectiveTargetMb(350))
        assertEquals(296, ShieldPreset.effectiveEstimatedNativeMb(350))
        assertEquals(200, ShieldPreset.effectiveTargetMb(500))
        // Measured reference device (2026-08-29 meminfo): the 2017 Shield
        // reports 1.9 GiB totalMem → the <2.3 GB rung, safe 250. The
        // applier's pre-commit re-check clamps to the largest 25 MB step
        // fitting above the 96 MB parallel overhead.
        assertEquals(150, ShieldPreset.effectiveTargetMb(250))
        assertEquals(246, ShieldPreset.effectiveEstimatedNativeMb(250))
        // The clamp guarantees the estimate never exceeds the safe limit.
        for (safe in listOf(150, 200, 250, 350, 1000, 1600, 2000)) {
            assertTrue(
                "effective estimate must stay inside safe limit $safe",
                ShieldPreset.effectiveEstimatedNativeMb(safe) <= safe
            )
        }
    }

    @Test
    fun `card seconds figure derives from the reference remux bitrate`() {
        assertEquals(25, ShieldPreset.secondsOfReference4k(200))
        assertEquals(19, ShieldPreset.secondsOfReference4k(150))
    }

    @Test
    fun `profile trio matches the engine stall-resistant profile and fits the max buffer`() {
        val profile = ShieldPreset.profile
        assertEquals(ProfileKind.STALL_RESISTANT, profile.kind)
        assertEquals(5_000, profile.initialBufferMs)
        assertEquals(8_000, profile.rebufferMs)
        assertEquals(30_000, profile.minBufferMs)
        assertTrue(
            "min buffer must fit inside the pinned max buffer without capping",
            profile.minBufferMs <= ShieldPreset.MAX_BUFFER_MS
        )
    }

    @Test
    fun `expected write count is touched fields plus the profile trio`() {
        assertEquals(ShieldPreset.plan.touchedCount + 3, ShieldPreset.expectedWriteCount(350))
        assertEquals(19, ShieldPreset.expectedWriteCount(350))
        // The applier's clamp step adds one write on the 2 GB reference Shield.
        assertEquals(20, ShieldPreset.expectedWriteCount(250))
    }
}
