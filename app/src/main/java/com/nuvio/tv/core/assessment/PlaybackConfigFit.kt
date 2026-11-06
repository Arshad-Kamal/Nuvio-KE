package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings

/**
 * The buffer / network / display values an AUTOMATIC producer decides (first-launch formula
 * + Shield overlay, network-test apply, per-film rule; Spec 33 § Whole-config fit). Absolute
 * values, never "leave as is": every producer passes its result through [fitPlaybackConfig]
 * last, and the gate needs the whole config to check it.
 */
data class PlaybackConfig(
    val performanceMode: Boolean,
    val targetBufferMb: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int,
    val retainBackBufferFromKeyframe: Boolean,
    val parallel: Boolean,
    val connections: Int,
    val chunkKb: Int,
    val http2: Boolean,
    val frameRateMatching: FrameRateMatchingMode
) {
    /** Chunk in whole MB, rounded up ([MemoryBudget.chunkMbOf]). */
    val chunkMb: Int get() = MemoryBudget.chunkMbOf(chunkKb)

    /** Parallel pipe's native footprint on top of the target ([MemoryBudget.parallelOverheadMb]). */
    val parallelOverheadMb: Int
        get() = if (parallel) MemoryBudget.parallelOverheadMb(connections, chunkMb) else 0
}

/** The stored values as a [PlaybackConfig]; the masters and the managed flag are not part of it. */
fun PlayerSettings.toPlaybackConfig(): PlaybackConfig = PlaybackConfig(
    performanceMode = nuvioPerformanceModeEnabled,
    targetBufferMb = bufferSettings.targetBufferSizeMb,
    minBufferMs = bufferSettings.minBufferMs,
    maxBufferMs = bufferSettings.maxBufferMs,
    bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
    backBufferMs = bufferSettings.backBufferDurationMs,
    retainBackBufferFromKeyframe = bufferSettings.retainBackBufferFromKeyframe,
    parallel = useParallelConnections,
    connections = parallelConnectionCount,
    chunkKb = parallelChunkSizeKb,
    http2 = enableHttp2,
    frameRateMatching = frameRateMatchingMode
)

/** A copy of these settings carrying [config]'s values (nothing is written); every other field is kept. */
fun PlayerSettings.withPlaybackConfig(config: PlaybackConfig): PlayerSettings = copy(
    nuvioPerformanceModeEnabled = config.performanceMode,
    bufferSettings = bufferSettings.copy(
        targetBufferSizeMb = config.targetBufferMb,
        minBufferMs = config.minBufferMs,
        maxBufferMs = config.maxBufferMs,
        bufferForPlaybackMs = config.bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs = config.bufferForPlaybackAfterRebufferMs,
        backBufferDurationMs = config.backBufferMs,
        retainBackBufferFromKeyframe = config.retainBackBufferFromKeyframe
    ),
    useParallelConnections = config.parallel,
    parallelConnectionCount = config.connections,
    parallelChunkSizeKb = config.chunkKb,
    enableHttp2 = config.http2,
    frameRateMatchingMode = config.frameRateMatching
)

/** What this device can hold and do, from the gathered device facts (Spec 33 D2). */
data class DeviceLimits(
    /** RAM tier's safe native MB (NuvioExoPlayerPerformanceHelper RAM_TIERS). */
    val safeMb: Int,
    /** Hard chunk ceiling for the RAM tier ([MemoryBudget.tierMaxChunkMb]). */
    val tierMaxChunkMb: Int,
    /** Performance mode needs API 26+ (the setter forces it off below). */
    val perfModeSupported: Boolean,
    /** >= 2 refresh rates at the current resolution; null = display not inspectable. */
    val afrSupported: Boolean?
)

/**
 * Seconds of the film the forward buffer is never cut below while the gate still has network
 * to give up: the 2 GB Shield's fitted 150 MB holds ~19 s of the measured 63 Mbps reference
 * remux (ShieldPreset.secondsOfReference4k(150)), the depth that device plays on today. The
 * card counts decimal MB (19 s); the gate counts MiB as MemoryBudget.backBufferMb does
 * (150 MiB = 19.97 s), so the floor is the same 150 MB step either way.
 */
const val FIT_MIN_BUFFER_S = 19

/**
 * The one gate every automatic config passes through last. Pure and idempotent; it never
 * raises a value already inside the device's range (connections below 2 are lifted to 2),
 * and never touches manual settings (Settings keeps its own sliders). Expects min buffer
 * <= max buffer on input, as every producer builds it.
 *
 * Memory: target + parallel overhead must fit [DeviceLimits.safeMb]. Back-buffer bytes are NOT
 * added: Media3 1.8.0 DefaultLoadControl stops loading once allocator.getTotalBytesAllocated()
 * reaches the byte target, and retained back-buffer samples sit in that allocator, so they eat
 * the forward depth inside the target instead. The DV7 conversion budget is not added either:
 * the runtime only tightens a MANAGED budget for it (PlayerRuntimeControllerInitialization
 * keepZeroForDv7), and automatic configs run unmanaged.
 *
 * Give-up order when it does not fit: (1) chunk down to [MemoryBudget.MIN_CHUNK_MB]; (2) back
 * buffer off; (3) target down, not below [FIT_MIN_BUFFER_S] of the film (capped at the depth the
 * tier can hold beside a 2 x 8 MB pipe); (4) fewer connections, then parallel off; last resort,
 * target below that floor. Parallel off and the last resort only run below a ~57 MB safe limit
 * (no RAM rung is under 150), and a safe limit under MIN_BUFFER_MB cannot be met at all.
 * Throughput is kept ahead of depth
 * until the floor: a short buffer only rides out remux peaks if the refill beats the bitrate.
 *
 * With a film bitrate, the back buffer also goes when it leaves less than the floor forward,
 * and max buffer seconds are capped to what the forward bytes hold (seconds above that are
 * inert: Media3 stops at whichever limit binds first).
 */
fun fitPlaybackConfig(config: PlaybackConfig, limits: DeviceLimits, filmMbps: Double?): PlaybackConfig {
    val bitrate = filmMbps?.takeIf { it > 0 }
    var c = config.copy(
        performanceMode = config.performanceMode && limits.perfModeSupported,
        connections = config.connections.coerceIn(MemoryBudget.MIN_CONNECTIONS, MemoryBudget.MAX_CONNECTIONS),
        chunkKb = MemoryBudget.tierCappedChunkKb(config.chunkKb, limits.tierMaxChunkMb),
        frameRateMatching = if (limits.afrSupported == false) FrameRateMatchingMode.OFF else config.frameRateMatching
    )
    fun fits() = c.targetBufferMb + c.parallelOverheadMb <= limits.safeMb
    val floorMb = fitFloorMb(limits, bitrate)

    // (1) Chunk down to the largest size that fits above the current target.
    if (!fits() && c.parallel) {
        val roomMb = (limits.safeMb - c.targetBufferMb) / MemoryBudget.bufferCount(c.connections)
        c = c.copy(chunkKb = minOf(c.chunkKb, roomMb.coerceAtLeast(MemoryBudget.MIN_CHUNK_MB) * 1024))
    }
    // (2) Back buffer off before any forward depth is cut (or when it already eats the floor).
    val forwardMb = c.targetBufferMb - MemoryBudget.backBufferMb(c.backBufferMs, c.retainBackBufferFromKeyframe, bitrate)
    if (!fits() || forwardMb < floorMb) {
        c = c.copy(backBufferMs = 0, retainBackBufferFromKeyframe = false)
    }
    // (3) Target down, not below the floor.
    if (!fits()) {
        val fitted = MemoryBudget.maxSafeTargetMb(limits.safeMb, c.parallelOverheadMb)
        c = c.copy(targetBufferMb = minOf(c.targetBufferMb, maxOf(floorMb, fitted)))
    }
    // (4) Fewer connections, then a single connection.
    while (!fits() && c.parallel) {
        c = if (c.connections > MemoryBudget.MIN_CONNECTIONS) c.copy(connections = c.connections - 1)
        else c.copy(parallel = false)
    }
    // Last resort: under the floor (only a tier whose safe limit cannot hold it lands here).
    if (!fits()) {
        c = c.copy(targetBufferMb = minOf(c.targetBufferMb, MemoryBudget.maxSafeTargetMb(limits.safeMb, 0)))
    }
    // Max buffer seconds the forward bytes can actually hold; never below min (Media3 asserts it).
    if (bitrate != null) {
        val forward = c.targetBufferMb - MemoryBudget.backBufferMb(c.backBufferMs, c.retainBackBufferFromKeyframe, bitrate)
        val holdsMs = (secondsHeld(forward, bitrate) * 1000).toInt()
        c = c.copy(maxBufferMs = minOf(c.maxBufferMs, maxOf(holdsMs, c.minBufferMs)))
    }
    return c
}

/**
 * The forward depth [fitPlaybackConfig] keeps while it still has network to give up:
 * [FIT_MIN_BUFFER_S] of the film, or [MemoryBudget.MIN_BUFFER_MB] without a bitrate. A floor
 * the device cannot hold beside even the leanest parallel pipe (2 x 8 MB) would trade away
 * connections for depth it never reaches, so it caps at that reachable depth.
 */
internal fun fitFloorMb(limits: DeviceLimits, filmMbps: Double?): Int {
    val bitrate = filmMbps?.takeIf { it > 0 } ?: return MemoryBudget.MIN_BUFFER_MB
    val leanestPipeMb = MemoryBudget.parallelOverheadMb(MemoryBudget.MIN_CONNECTIONS, MemoryBudget.MIN_CHUNK_MB)
    return minOf(stepUp(mbFor(FIT_MIN_BUFFER_S, bitrate)), MemoryBudget.maxSafeTargetMb(limits.safeMb, leanestPipeMb))
}

// MB here = MiB, as MemoryBudget.backBufferMb counts it; bitrates are decimal Mbit/s.
private const val BYTES_PER_MB = 1024.0 * 1024.0

private fun mbFor(seconds: Int, mbps: Double): Double = mbps * 1_000_000 / 8 * seconds / BYTES_PER_MB

private fun secondsHeld(mb: Int, mbps: Double): Double = mb * BYTES_PER_MB * 8 / (mbps * 1_000_000)

/** Rounds up to the next [MemoryBudget.BUFFER_STEP_MB] step, floored at [MemoryBudget.MIN_BUFFER_MB]. */
private fun stepUp(mb: Double): Int {
    val step = MemoryBudget.BUFFER_STEP_MB
    return (Math.ceil(mb / step).toInt() * step).coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
}
