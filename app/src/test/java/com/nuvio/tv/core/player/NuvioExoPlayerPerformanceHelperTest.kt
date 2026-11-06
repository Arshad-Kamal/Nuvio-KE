package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.core.assessment.ShieldPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NuvioExoPlayerPerformanceHelperTest {

    private val gb = 1024L * 1024L * 1024L

    @Before
    fun setUp() {
        NuvioExoPlayerPerformanceHelper.clearCache()
    }

    @Test
    fun `test default fallback values when RAM is zero or unknown`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns 0L

        assertEquals("Unknown", helperSpy.getFriendlyRamLabel(context))
        assertEquals(250, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(325, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1GB physical RAM (reports ~900MB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (0.9 * gb).toLong()

        assertEquals("1 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(150, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(180, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1_5 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1.5GB physical RAM (reports ~1.3GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.3 * gb).toLong()

        assertEquals("1.5 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(200, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(250, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 2 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 2GB physical RAM (reports ~1.7GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.7 * gb).toLong()

        assertEquals("2 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(250, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(325, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 3 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 3GB physical RAM (reports ~2.6GB). Re-tiered 2026-08-28 (analysis
        // F3): 500/650 -> 350/450 — the v1340 stall event froze at 413 MB
        // native, inside the old 500 MB band.
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        assertEquals("3 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(350, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(450, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `3_2 GB boundary belongs to the 4 GB rung`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 3.2 GiB is fractional (3435973836.8 bytes), so no Long can sit ON
        // the threshold: one byte above it must classify as the 4 GB rung.
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns ((3.2 * gb).toLong() + 1)

        assertEquals("4 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1000, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(1200, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `3 GB tier keeps the Shield preset inside the safe band`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        val safeLimitMb = helperSpy.getSafeNativeMemoryLimitMb(context)
        val warningLimitMb = helperSpy.getWarningNativeMemoryLimitMb(context)
        // The real preset's footprint on this tier (200 MB target + 4c/16
        // overhead = 296 MB, unclamped at the 350 MB safe limit; equality with
        // the plan is pinned in ShieldPresetTest) — not a local restatement.
        // effectiveEstimatedNativeMb is clamped into the safe band by
        // construction, so the SAFE check alone cannot fail: pin that this
        // tier fits the full pinned target without the clamp.
        assertEquals(ShieldPreset.TARGET_BUFFER_MB, ShieldPreset.effectiveTargetMb(safeLimitMb))
        assertEquals(
            MemoryUsageStatus.SAFE,
            MemoryBudget.getUsageStatus(
                ShieldPreset.effectiveEstimatedNativeMb(safeLimitMb), safeLimitMb, warningLimitMb
            )
        )
    }

    @Test
    fun `3 GB tier flags the measured v1340 stall point as warning`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        val safeLimitMb = helperSpy.getSafeNativeMemoryLimitMb(context)
        val warningLimitMb = helperSpy.getWarningNativeMemoryLimitMb(context)
        // The v1340 stall event froze/crashed at 413 MB native (analysis F3);
        // that must never read SAFE on this tier again.
        assertEquals(
            MemoryUsageStatus.WARNING,
            MemoryBudget.getUsageStatus(413, safeLimitMb, warningLimitMb)
        )
    }

    @Test
    fun `3 GB safe limit stays under the engine lowRamTier predicate`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        // DeviceAssessmentEngine derives lowRamTier from safeLimitMb <= 500;
        // the back-buffer rows and their apply-plan writes key on it. A future
        // re-tier must not silently cross that line without re-checking the
        // engine's rows.
        assertTrue(
            "safe limit crossed the engine's lowRamTier boundary (500)",
            helperSpy.getSafeNativeMemoryLimitMb(context) <= 500
        )
    }

    @Test
    fun `test 4 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 4GB physical RAM (reports ~3.6GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (3.6 * gb).toLong()

        assertEquals("4 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1000, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(1200, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 6 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 6GB physical RAM (reports ~5.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (5.4 * gb).toLong()

        assertEquals("6 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1600, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(2000, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 8 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 8GB physical RAM (reports ~7.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (7.4 * gb).toLong()

        assertEquals("8 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(2500, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 12 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 12GB physical RAM (reports ~11.0GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (11.0 * gb).toLong()

        assertEquals("12 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(2500, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 16 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 16GB physical RAM (reports ~14.8GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (14.8 * gb).toLong()

        assertEquals("16 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2000, helperSpy.getSafeNativeMemoryLimitMb(context))
        assertEquals(2500, helperSpy.getWarningNativeMemoryLimitMb(context))
    }

    @Test
    fun `low-RAM tier is the 1, 1_5 and 2 GB rungs by physical RAM`() {
        val h = NuvioExoPlayerPerformanceHelper
        assertEquals(true, h.isLowRamTier((0.9 * gb).toLong()))
        assertEquals(true, h.isLowRamTier((1.3 * gb).toLong()))
        // The 2 GB Shield: MemTotal 1,987,624 kB.
        assertEquals(true, h.isLowRamTier(1_987_624L * 1024L))
        assertEquals(false, h.isLowRamTier((2.4 * gb).toLong()))
        assertEquals(false, h.isLowRamTier((2.8 * gb).toLong()))
        assertEquals(false, h.isLowRamTier(16 * gb))
    }

    @Test
    fun `low-RAM tier is unknown when physical RAM is unreadable`() {
        assertNull(NuvioExoPlayerPerformanceHelper.isLowRamTier(0L))
        assertNull(NuvioExoPlayerPerformanceHelper.isLowRamTier(-1L))
    }

    @Test
    fun `benchmark getDevicePhysicalRamBytes caching speedup`() {
        val context = mockk<Context>()
        val activityManager = mockk<android.app.ActivityManager>()
        
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            val memInfo = firstArg<android.app.ActivityManager.MemoryInfo>()
            memInfo.totalMem = 4 * gb
        }

        val field = NuvioExoPlayerPerformanceHelper::class.java.getDeclaredField("cachedDevicePhysicalRamBytes")
        field.isAccessible = true
        
        val iterations = 50_000
        
        // 1. Uncached Benchmark (force bypass cache)
        val startTimeUncached = System.nanoTime()
        for (i in 0 until iterations) {
            field.set(NuvioExoPlayerPerformanceHelper, 0L) // Reset cache
            NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context)
        }
        val durationUncached = System.nanoTime() - startTimeUncached

        // 2. Cached Benchmark (cache active)
        field.set(NuvioExoPlayerPerformanceHelper, 0L)
        NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context) // Populate cache
        
        val startTimeCached = System.nanoTime()
        for (i in 0 until iterations) {
            NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(context)
        }
        val durationCached = System.nanoTime() - startTimeCached

        val speedup = durationUncached.toDouble() / durationCached.toDouble()
        
        println("=== BENCHMARK RESULTS ===")
        println("Iterations: $iterations")
        println("Uncached Total Time: ${durationUncached / 1_000_000.0} ms")
        println("Cached Total Time: ${durationCached / 1_000_000.0} ms")
        println("Speedup Factor: ${"%.2f".format(speedup)}x faster")
        println("=========================")
    }
}
