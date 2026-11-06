package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.BufferSettings

/**
 * Shared memory budget constants and helpers for buffer + parallel connection settings.
 * One home for the maths behind the playback settings UI, the player runtime
 * (PlayerMediaSourceFactory, ParallelRangeDataSource), the Device Assessment and the
 * stream speed test / sweep.
 */
@UnstableApi
object MemoryBudget {
    // Heap-tiered budget ratios. Low-heap devices (Fire TV class) need more
    // headroom for codec/surface/UI; high-heap devices can dedicate more to buffering.
    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    // The buffer allocator is on the Java heap; on a low heap reserve a slice for the UI/decoder/caches
    // and give the rest to the buffer (a flat % of max heap overcommits and starves them).
    private const val LOW_HEAP_RESERVE_MB = 210L

    // Extra chunk buffers alive beyond one per connection (see bufferCount).
    // nt7: matches ParallelRangeDataSource reality — the session holds up to
    // connections + 2 chunks on low-RAM devices (connections + 4 on high-RAM;
    // the extra headroom is deliberately not advertised so the enforcement
    // maths stays conservative). Was 1, which under-stated the ceiling.
    private const val BUFFER_OVERHEAD = 2

    // nt37: nt36 clamps ParallelRangeDataSource's live prefetch window to at most
    // 4*connections chunks (sized from a device-RAM budget). The DISPLAYED memory
    // estimate uses this upper bound so the "estimated usage" warning over-estimates
    // rather than under-estimates - the safe direction for a guard, and the fix for
    // the pre-nt37 undercount that let real usage exceed the "safe" figure shown.
    // nt-exact: these clamp multiples are the SINGLE SOURCE OF TRUTH for the deep-path
    // window, shared by the runtime depth (PlayerMediaSourceFactory delegates to
    // prefetchDepthChunks below) and the displayed estimate, so the two can never
    // drift. bufferCount and the enforcement maths (maxChunkMb / maxBufferMb / enforce)
    // are deliberately left on the conservative connections+2 accounting so the slider
    // maxima are unchanged.
    private const val PREFETCH_DEPTH_LOWER_MULTIPLE = 2
    private const val PREFETCH_DEPTH_UPPER_MULTIPLE = 4

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 32
    // Low-RAM devices (1-2 GB class): the session's protected set (playhead
    // window + tail moov chunks + pinned side chunks) puts the worst-case
    // retained floor at roughly connections + 10 chunks on a scatter-heavy
    // file, so chunk size is hard-capped at 16 MB on that tier — above it
    // the floor alone exceeds what those devices can hold. Applies in
    // performance mode too; the cap is a tier property, not a budget one.
    private const val LOW_RAM_MAX_CHUNK_MB = 16
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    const val MAX_BUFFER_MB = 1024 * 4
    private const val DEFAULT_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        DEFAULT_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    /**
     * True when the app heap is below the high-heap threshold. Sizes the heap-fraction
     * budgets below (budgetMb, conversionBudgetMb) and the DV7 keep-zero budget override;
     * device-memory bounds use [isLowRamTier].
     */
    val isLowHeapTier: Boolean = maxHeapMb < HIGH_HEAP_THRESHOLD_MB

    /**
     * Spec 56 A4: the device is on a <= 2 GB physical-RAM rung (1 / 1.5 / 2 GB, the
     * NuvioExoPlayerPerformanceHelper RAM table). Not the heap: with largeHeap the 2 GB
     * Shield gets a 512 MB heap and read as high-RAM. Unreadable RAM falls back to
     * [isLowHeapTier]. Drives the session chunk headroom, [tierMaxChunkMb] and the sweep
     * cell budget.
     */
    val isLowRamTier: Boolean =
        NuvioExoPlayerPerformanceHelper.isLowRamTier(NuvioExoPlayerPerformanceHelper.getRamFromMemInfo())
            ?: isLowHeapTier

    // Pre-cap ratio budget; conversionBudgetMb derives from this so DV7 headroom isn't cut by the cap.
    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowHeapTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()

    val budgetMb: Int =
        if (isLowHeapTier)
            rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(MIN_BUFFER_MB)
        else rawBudgetMb

    // DV7 conversion headroom: a third of the raw budget on a low heap, half on a high one; never above budget.
    val conversionBudgetMb: Int =
        (if (isLowHeapTier) rawBudgetMb / 3 else rawBudgetMb / 2)
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    /** Number of chunk-sized buffers alive concurrently */
    fun bufferCount(connectionCount: Int): Int =
        connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    /** A stored chunk size in whole MB, rounded up, as every overhead site counts it. */
    fun chunkMbOf(chunkKb: Int): Int = (chunkKb + 1023) / 1024

    /**
     * Largest 25 MB step target that fits [safeLimitMb] above [overheadMb] —
     * the "recommend to safe" clamp. Two sites are bound to one contract:
     * DeviceAssessmentApplier.apply's pre-commit re-check (enforcement)
     * and ShieldPreset.effectiveTargetMb (card preview and the first-launch
     * Shield overlay), so what the card advertises is what apply writes. The
     * assessment engine, stream sweep, perf-mode preset, settings slider,
     * first-launch formula (DeviceSetupFormula) and the fit gate
     * (fitPlaybackConfig) reuse the same formula with their own overhead
     * terms and limits.
     */
    fun maxSafeTargetMb(safeLimitMb: Int, overheadMb: Int): Int =
        (((safeLimitMb - overheadMb) / BUFFER_STEP_MB) * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)

    /**
     * Estimated back-buffer footprint in MB. Back-buffer bytes are time-based,
     * so the figure needs the stream bitrate: an unknown bitrate yields 0
     * (ungrounded — callers should say the estimate needs a last-played
     * stream). Retain-from-keyframe roughly doubles the retained span on
     * long-GOP content (samples back to the previous keyframe ride along);
     * the x2 is [inferred], field-tunable.
     */
    fun backBufferMb(backBufferMs: Int, retainFromKeyframe: Boolean, bitrateMbps: Double?): Int {
        if (backBufferMs <= 0) return 0
        val bitrate = bitrateMbps?.takeIf { it > 0 } ?: return 0
        val mb = bitrate * (backBufferMs / 1000.0) / 8.0 * 1_000_000 / (1024.0 * 1024.0)
        return (mb * (if (retainFromKeyframe) 2.0 else 1.0)).toInt().coerceAtLeast(1)
    }

    /**
     * Deep-path prefetch depth — the SINGLE SOURCE OF TRUTH shared by the runtime
     * (PlayerMediaSourceFactory.computePrefetchDepthChunks delegates here) and the
     * displayed memory estimate. Mirrors the nt36 runtime maths exactly: spend the safe
     * native budget minus the SampleQueue reserve on chunks, divide by chunk size, clamp
     * to [2*connections, 4*connections].
     */
    fun prefetchDepthChunks(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
    ): Int {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        val chunkBudgetMb = (safeNativeLimitMb - reserveBufferMb.coerceAtLeast(0))
            .coerceAtLeast(chunkMb * PREFETCH_DEPTH_LOWER_MULTIPLE)
        val byBudget = chunkBudgetMb / chunkMb
        return byBudget.coerceIn(
            connections * PREFETCH_DEPTH_LOWER_MULTIPLE,
            connections * PREFETCH_DEPTH_UPPER_MULTIPLE
        )
    }

    // ── Assessment-sweep cell budgeting (crash-hardening leg 2, 19 Jul 2026 incident) ──
    //
    // A sweep cell's true memory ceiling is the tester's prefetch WINDOW, not
    // connections x chunk: ParallelRangeDataSource may hold up to its prefetch
    // depth in live chunks (plus the memory-tiered session-cap headroom). The
    // tester's old unconditional connections*4 window gave the 3 conn / 64 MB
    // crash cell a permitted ceiling near 1 GB on the 250 MB-budget S905X5M.
    //
    // SWEEP_BUDGET_FRACTION reserves headroom for the app's own native use
    // while a sweep runs; SWEEP_CELL_MAX_CONCURRENT_MB is the absolute cap on
    // the nominal concurrent-download footprint (connections x chunk) on ANY
    // device — 3 x 64 MB never runs, on the 4 GB tier included. Both are
    // [inferred] initial values, field-tunable like the trade constants.
    //
    // Spec 56 T1: the low-RAM tier spends half its safe budget, not three
    // quarters. On the 2 GB Shield (safe 250) 0.75 let a 16 MB cell run
    // depth 11 (176 MB in flight, + session headroom) on top of a 270 MB idle
    // app, and its first play after the test was killed by lmkd; 0.5 gives
    // depth 7 (112 MB). No low-tier concurrent cap: the sweep ladder stops at
    // tierMaxChunkMb (16) there, so connections x chunk is at most 4 x 16 = 64,
    // and a cell's in-flight memory is its depth, not its connection count.
    private const val SWEEP_BUDGET_FRACTION = 0.75
    private const val SWEEP_BUDGET_FRACTION_LOW_RAM = 0.5
    const val SWEEP_CELL_MAX_CONCURRENT_MB = 128

    /**
     * Prefetch depth (in chunks) a sweep cell may run with on this device, or
     * null when the cell must NOT be scheduled at all. Null when either the
     * nominal concurrent footprint (connections x chunk) exceeds
     * [SWEEP_CELL_MAX_CONCURRENT_MB], or the budget-derived depth falls below
     * ParallelRangeDataSource's own floor of connections + 1 — a window that
     * small cannot be honoured, so the cell cannot be memory-bounded.
     * Single source of truth: the sweep's ladder gate and the tester's window
     * both use this value, so the gate can never drift from the allocation.
     */
    fun sweepCellPrefetchDepth(
        connections: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        lowRamTier: Boolean = isLowRamTier,
    ): Int? {
        val chunkMb = chunkSizeMb.coerceAtLeast(1)
        if (connections * chunkMb > SWEEP_CELL_MAX_CONCURRENT_MB) return null
        val fraction = if (lowRamTier) SWEEP_BUDGET_FRACTION_LOW_RAM else SWEEP_BUDGET_FRACTION
        val budgetMb = (safeNativeLimitMb * fraction).toInt()
        val depth = (budgetMb / chunkMb)
            .coerceAtMost(connections * PREFETCH_DEPTH_UPPER_MULTIPLE)
        return depth.takeIf { it >= connections + 1 }
    }

    /**
     * Parallel overhead for the DISPLAYED memory-usage estimate only. nt-exact revision:
     * instead of always assuming the 4*connections upper clamp (which over-stated usage
     * whenever the device budget clamps the window lower, and made the per-step deltas
     * look inflated — field report: +1 connection at 16 MB chunks jumped the estimate by
     * 64 MB, and +8 MB of chunk at 2 connections jumped it by 80 MB), this computes the
     * ACTUAL depth the runtime will use via [prefetchDepthChunks], plus the session's
     * live-chunk headroom (depth + 2 on low-RAM, depth + 4 on high-RAM — mirrors
     * ParallelRangeDataSource.sessionChunkCap; the old flat +2 under-counted the
     * high-RAM ceiling by two chunks at the clamp). When the deep path is inactive
     * (performance mode off, or MP4 session mode) the runtime window is connections + 1
     * and the estimate says so. Unlike [parallelOverheadMb] (which underpins the slider
     * maxima and must stay conservative), this is display-only.
     */
    fun displayParallelOverheadMb(
        connectionCount: Int,
        chunkSizeMb: Int,
        safeNativeLimitMb: Int,
        reserveBufferMb: Int,
        deepPathActive: Boolean,
    ): Int {
        val depth = if (deepPathActive) {
            prefetchDepthChunks(connectionCount, chunkSizeMb, safeNativeLimitMb, reserveBufferMb)
        } else {
            connectionCount + 1
        }
        return (depth + sessionChunkHeadroom) * chunkSizeMb
    }

    /** Total usage for the DISPLAYED estimate: buffer plus the exact parallel overhead. */
    fun displayTotalUsageMb(
        bufferMb: Int,
        connectionCount: Int,
        chunkSizeMb: Int,
        parallelEnabled: Boolean,
        safeNativeLimitMb: Int,
        deepPathActive: Boolean,
    ): Int =
        bufferMb + if (parallelEnabled) {
            displayParallelOverheadMb(connectionCount, chunkSizeMb, safeNativeLimitMb, bufferMb, deepPathActive)
        } else {
            0
        }

    /**
     * Live chunks a ParallelRangeDataSource session may hold beyond its prefetch depth (nt7
     * LRU headroom): 2 on the low-RAM tier, 4 above. Shared by the session cap and the
     * displayed estimate.
     */
    val sessionChunkHeadroom: Int = if (isLowRamTier) 2 else 4

    /**
     * A chunk size capped at the RAM tier's ceiling. The one home for that cap: the settings
     * read (so a 32 MB value saved before Spec 56 S2 reads as 16 on <= 2 GB, for the player,
     * the network test and every display), the first-launch formula and the fit gate.
     */
    fun tierCappedChunkKb(chunkKb: Int, tierMaxMb: Int = tierMaxChunkMb): Int =
        chunkKb.coerceAtMost(tierMaxMb * 1024)

    /** Hard chunk-size ceiling for this RAM tier; binds everywhere, including performance mode and the sweep. */
    val tierMaxChunkMb: Int = if (isLowRamTier) LOW_RAM_MAX_CHUNK_MB else MAX_CHUNK_MB

    /** Max chunk size that fits budget given current buffer size */
    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, tierMaxChunkMb)

    /** Max buffer size that fits budget given current parallel overhead */
    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    /**
     * Enforce budget: reduce chunk first, then buffer as last resort.
     * Returns (adjustedBufferMb, adjustedChunkMb).
     */
    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val newChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * newChunkMb <= budgetMb) return bufferMb to newChunkMb

        // Even min chunk doesn't fit, also reduce buffer
        val newBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return newBufferMb to MIN_CHUNK_MB
    }

    /**
     * Determines the memory usage status based on total usage, safe limit, and warning limit.
     */
    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus {
        return when {
            totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
            totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}

