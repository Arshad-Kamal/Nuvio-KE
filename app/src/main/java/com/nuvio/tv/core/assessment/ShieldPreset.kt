package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.core.player.MemoryBudget

/**
 * Hand-curated one-press preset for the reference device: Shield Android TV
 * 2019 non-Pro (Tegra X1+, 2 GB, armeabi-v7a). Values are FIXED from the measured live
 * profile (2026-08-28: 63 Mbit/s 4K REMUX, 57.3 Mbit/s measured link,
 * stall-heavy, crash under native-memory pressure) rather than derived —
 * the assessment needs a last-played stream; this does not, which is its
 * whole point. Grounds, memory math and the audit behind each value:
 * docs/project/device-assessment-analysis-2026-08-28.md.
 *
 * Applied through [DeviceAssessmentApplier.apply] so the full
 * snapshot/revert machinery applies. Shares the ONE revert slot with the
 * assessment: applying either replaces the snapshot, so the last apply wins
 * and Revert undoes it. DV / audio-passthrough / denied-codec fields stay
 * null deliberately — those are device-verified live (TrueHD passthrough
 * green) and the assessment owns them.
 *
 * Memory model: 200 MB target + (4+2) x 16 MB parallel overhead = 296 MB
 * nominal, pinned to a 0 back buffer so nothing else erodes the headroom.
 * DEVICE REALITY (2026-08-29 meminfo): the reference Shield is the
 * 2 GB model — MemTotal 1,987,624 kB ≈ 1.9 GiB — so it reads the <2.3 GB
 * RAM rung (safe 250 / warning 325), NOT the <3.2 GB rung the preset was
 * first sized against. There the applier's pre-commit re-check clamps the
 * target to 150 MB (246 MB total, still SAFE); [effectiveTargetMb]
 * previews that clamp so the card advertises what apply writes. On
 * ≥3.2 GB-class devices the values land unclamped at 200/296, inside the
 * 350/450 tier.
 *
 * Spec 33 D2: first-launch setup reaches the same values as [overlay] on
 * top of DeviceSetupFormula; the Settings card keeps [plan] until D5.
 */
object ShieldPreset {

    const val TARGET_BUFFER_MB = 200
    const val MAX_BUFFER_MS = 120_000
    const val PARALLEL_CONNECTIONS = 4
    const val PARALLEL_CHUNK_KB = 16_384

    /** (4+2) x 16 MB = 96 MB — the parallel pipe rides on top of the target buffer. */
    val ESTIMATED_PARALLEL_OVERHEAD_MB: Int =
        MemoryBudget.parallelOverheadMb(PARALLEL_CONNECTIONS, PARALLEL_CHUNK_KB / 1024)

    /** Bitrate of the measured reference REMUX the card's seconds figure quotes. */
    const val REFERENCE_REMUX_MBPS = 63

    /**
     * Seconds of the reference REMUX that `mb` buffers — the card's
     * "≈N s of 4K" suffix. 200 MB → ≈25 s; a clamped 150 MB → ≈19 s.
     */
    fun secondsOfReference4k(mb: Int): Int = mb * 8 / REFERENCE_REMUX_MBPS

    /**
     * The target buffer apply actually leaves in place on a device whose
     * RAM-tier safe limit is [safeLimitMb]: the pinned 200 MB unless that
     * limit cannot fit it above the parallel overhead, in which case it
     * clamps to the largest 25 MB step that fits — the SAME clamp helper
     * ([MemoryBudget.maxSafeTargetMb]) the applier's pre-commit re-check
     * (DeviceAssessmentApplier.apply) enforces, so the card advertises
     * what apply writes.
     */
    fun effectiveTargetMb(safeLimitMb: Int): Int =
        minOf(
            TARGET_BUFFER_MB,
            MemoryBudget.maxSafeTargetMb(safeLimitMb, ESTIMATED_PARALLEL_OVERHEAD_MB)
        )

    /** Estimated native footprint at [effectiveTargetMb]: target + parallel overhead. */
    fun effectiveEstimatedNativeMb(safeLimitMb: Int): Int =
        effectiveTargetMb(safeLimitMb) + ESTIMATED_PARALLEL_OVERHEAD_MB

    /**
     * Written count on a device whose RAM-tier safe limit is [safeLimitMb]:
     * touchedCount + the profile trio, plus the applier's clamp step when
     * this tier can't fit the pinned target above the parallel overhead
     * (the clamp fires iff [effectiveTargetMb] drops below the pinned
     * target — apply always writes the plan's 200 first, then clamps).
     */
    fun expectedWriteCount(safeLimitMb: Int): Int =
        plan.touchedCount + 3 +
            if (effectiveTargetMb(safeLimitMb) < TARGET_BUFFER_MB) 1 else 0

    val plan = AssessmentApplyPlan(
        nuvioPerformanceModeEnabled = true,
        bufferEngineEnabled = true,
        parallelNetworkEnabled = true,
        // Managed budget makes the runtime IGNORE the stored target and use
        // the RAM-tier safe limit (updateSettings) — off so 200 MB means 200.
        bufferBudgetManaged = false,
        allowLargeTargetBuffer = true,
        targetBufferSizeMb = TARGET_BUFFER_MB,
        maxBufferMs = MAX_BUFFER_MS,
        useParallelConnections = true,
        parallelConnectionCount = PARALLEL_CONNECTIONS,
        parallelChunkSizeKb = PARALLEL_CHUNK_KB,
        // HTTP/1.1 by default (Spec 36 S6, Arshad 2026-10-19); a service's measured h2 verdict
        // (ServiceNetworkTable.http2) still overrides it per play.
        enableHttp2 = false,
        frameRateMatchingMode = FrameRateMatchingMode.START_STOP,
        resolutionMatchingEnabled = true,
        backBufferDurationMs = 0,
        retainBackBufferFromKeyframe = false,
        autoSwitchInternalPlayerOnError = true
    )

    /**
     * Stall-resistant trio, pinned. title/subtitle stay empty: the profile is
     * consumed by the applier as buffer numbers only, never rendered.
     */
    val profile = IntentProfileOption(
        kind = ProfileKind.STALL_RESISTANT,
        title = "",
        subtitle = "",
        initialBufferMs = 5_000,
        rebufferMs = 8_000,
        minBufferMs = 30_000
    )

    /**
     * The preset's buffer / network / display values on top of a formula [config]
     * (Spec 33 D2). The target is [effectiveTargetMb], the value apply settles on, so
     * the result already fits: the no-film fit gate passes it through unchanged on a
     * display that can switch rates (or cannot be inspected); a single-rate display
     * turns AFR off, and a film bitrate can cap max buffer seconds.
     * resolutionMatchingEnabled and autoSwitchInternalPlayerOnError are not config
     * values: DeviceSetupFormula copies them from [plan].
     */
    fun overlay(config: PlaybackConfig, limits: DeviceLimits): PlaybackConfig = config.copy(
        targetBufferMb = effectiveTargetMb(limits.safeMb),
        minBufferMs = profile.minBufferMs,
        maxBufferMs = MAX_BUFFER_MS,
        bufferForPlaybackMs = profile.initialBufferMs,
        bufferForPlaybackAfterRebufferMs = profile.rebufferMs,
        backBufferMs = 0,
        retainBackBufferFromKeyframe = false,
        parallel = true,
        connections = PARALLEL_CONNECTIONS,
        chunkKb = PARALLEL_CHUNK_KB,
        http2 = false,
        frameRateMatching = FrameRateMatchingMode.START_STOP
    )
}
