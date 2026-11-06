package com.nuvio.tv.core.player

import android.content.Context
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Centralizes Nuvio ExoPlayer performance enhancements behind a single toggle.
 *
 * When [enabled] is `true`, the helper applies (see the constants for the figures):
 * - Native-allocated [DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE] allocator segments with a
 *   [targetBufferSizeMb] byte target (the RAM tier's safe native limit unless the user's
 *   buffer settings override it)
 * - [minBufferMs]..[maxBufferMs] buffer durations and a [backBufferMs] back buffer
 *   (defaults [DEFAULT_NUVIO_MIN_BUFFER_MS], [DEFAULT_NUVIO_MAX_BUFFER_MS],
 *   [DEFAULT_NUVIO_BACK_BUFFER_MS])
 * - A [DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE] initial bandwidth estimate
 * - In-buffer seek detection to suppress transient buffering UI
 * - [NuvioEngineConfig] native/zero-copy mode
 * - At the player runtime's call sites: track-selection rebuilds only until the first frame
 *   (not after every seek), and allocator footprint lines in the Debug > buffer logs output
 *
 * When [enabled] is `false`, [buildLoadControl] still returns a tuned fallback (not the
 * stock builder defaults) and the bandwidth meter uses the platform estimate.
 *
 * Regardless of the toggle, [applyNetworkOptimizations] installs [sharedConnectionPool]
 * and the protocol list chosen by [enableHttp2].
 */
@androidx.media3.common.util.UnstableApi
object NuvioExoPlayerPerformanceHelper {

    /** Whether Nuvio performance enhancements are active. Set from [PlayerSettingsDataStore]. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            val newValue = value && supported
            field = newValue
            applyEngineConfig(newValue)
        }

    // S1g root fix (26 Jul capture, POOL_ID prewarm=44918017 vs
    // datasource=158407223): this was a @Volatile var REPLACED whenever the
    // computed pool size changed, so lazily-built derived clients captured
    // different pool instances depending on construction order -- the prewarm
    // warmed a pool the probe never read. A ConnectionPool's size is a
    // MAX-IDLE cap, not a concurrency limit, so one fixed-size singleton is
    // strictly safe at any connection count; making it a val enforces the
    // sharing invariant at the type level.
    val sharedConnectionPool: okhttp3.ConnectionPool = okhttp3.ConnectionPool(
        NUVIO_SHARED_POOL_MAX_IDLE,
        3,
        java.util.concurrent.TimeUnit.MINUTES
    )

    // ─── Constants ────────────────────────────────────────────────────────────
    const val DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE = 64 * 1024        // 64 KB
    const val DEFAULT_NUVIO_MIN_BUFFER_MS = 40_000
    const val DEFAULT_NUVIO_MAX_BUFFER_MS = 120_000
    const val DEFAULT_NUVIO_BACK_BUFFER_MS = 1_500
    const val DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE = 50_000_000L     // 50 Mbps
    const val DEFAULT_NUVIO_CONNECTION_POOL_SIZE = 8

    /**
     * Max IDLE connections retained by [sharedConnectionPool].
     *
     * Regression fix. Before the S1g root fix the pool was rebuilt at
     * parallelConnectionCount * 2 (12 at six connections); making it a fixed
     * singleton pinned it at DEFAULT_NUVIO_CONNECTION_POOL_SIZE = 8, BELOW the
     * concurrent chunk demand. A ConnectionPool's size caps idle retention
     * rather than concurrency, so nothing blocked -- but every connection past
     * the eighth was evicted as soon as it went idle and the next burst
     * reopened it cold. Both captures show that: 4 of ~12 concurrent chunk
     * opens cold mid-playback in nt3, 3 in nt2.
     *
     * Set well above any reachable parallel setting. An idle pooled connection
     * is a socket and a few KB, and the three-minute idle timeout still reaps
     * them.
     */
    const val NUVIO_SHARED_POOL_MAX_IDLE = 32

    // ─── Customization Variables ──────────────────────────────────────────────
    @Volatile
    var minBufferMs: Int = DEFAULT_NUVIO_MIN_BUFFER_MS

    @Volatile
    var maxBufferMs: Int = DEFAULT_NUVIO_MAX_BUFFER_MS

    @Volatile
    var bufferForPlaybackMs: Int = 3_000

    @Volatile
    var bufferForPlaybackAfterRebufferMs: Int = 3_000

    @Volatile
    var backBufferMs: Int = DEFAULT_NUVIO_BACK_BUFFER_MS

    /**
     * Whether samples older than the back-buffer window — back to the previous
     * keyframe — stay retained. Was hardcoded true at every buildLoadControl
     * call site while the stored setting sat unread; now wired through
     * [updateSettings] (custom buffers honor the setting, stock keeps true).
     */
    @Volatile
    var retainBackBufferFromKeyframe: Boolean = true

    @Volatile
    var targetBufferSizeMb: Int = 250

    @Volatile
    var connectionPoolSize: Int = DEFAULT_NUVIO_CONNECTION_POOL_SIZE

    @Volatile
    var enableHttp2: Boolean = false

    /**
     * Updates the performance helper with customized settings from PlayerSettings.
     */
    fun updateSettings(settings: PlayerSettings, context: Context) {
        val customBuffers = settings.bufferEngineEnabled
        val bufferSettings = settings.bufferSettings
        enableHttp2 = settings.enableHttp2
        
        minBufferMs = if (customBuffers) bufferSettings.minBufferMs else DEFAULT_NUVIO_MIN_BUFFER_MS
        maxBufferMs = if (customBuffers) bufferSettings.maxBufferMs else DEFAULT_NUVIO_MAX_BUFFER_MS
        bufferForPlaybackMs = if (customBuffers) bufferSettings.bufferForPlaybackMs else 3_000
        bufferForPlaybackAfterRebufferMs = if (customBuffers) bufferSettings.bufferForPlaybackAfterRebufferMs else 3_000
        backBufferMs = if (customBuffers) bufferSettings.backBufferDurationMs else DEFAULT_NUVIO_BACK_BUFFER_MS
        retainBackBufferFromKeyframe =
            if (customBuffers) bufferSettings.retainBackBufferFromKeyframe else true

        val safeLimitMb = getSafeNativeMemoryLimitMb(context)
        targetBufferSizeMb = if (customBuffers && !settings.bufferBudgetManaged) {
            val storedSize = bufferSettings.targetBufferSizeMb
            if (!settings.allowLargeTargetBuffer && storedSize > safeLimitMb) {
                safeLimitMb
            } else {
                storedSize
            }
        } else {
            safeLimitMb
        }

        val customNetwork = settings.parallelNetworkEnabled
        connectionPoolSize = if (customNetwork && settings.useParallelConnections) {
            settings.parallelConnectionCount * 2
        } else {
            DEFAULT_NUVIO_CONNECTION_POOL_SIZE
        }
        // S1g root fix: the pool is never replaced. The old swap here (resize
        // on the first updateSettings, i.e. every player init) is what made
        // pool capture ordering-dependent; the derived count above is kept
        // only as visible state. Idle connections above the working set cost
        // nothing -- the singleton's fixed cap covers every supported setting.
    }

    private const val SEEK_BACKWARD_TOLERANCE_MS = 2_000L
    const val SEEK_SUPPRESS_TIMEOUT_MS = 800L

    // ─── LoadControl ──────────────────────────────────────────────────────────

    /**
     * Total physical memory from /proc/meminfo's MemTotal (the figure ActivityManager's
     * totalMem reports); 0 when unreadable. Context-free, so MemoryBudget can tier on it.
     */
    internal fun getRamFromMemInfo(): Long {
        return try {
            val file = java.io.File("/proc/meminfo")
            if (file.exists()) {
                file.useLines { lines ->
                    val firstLine = lines.firstOrNull() ?: ""
                    val match = java.util.regex.Pattern.compile("\\d+").matcher(firstLine)
                    if (match.find()) {
                        match.group().toLong() * 1024L
                    } else {
                        0L
                    }
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    @Volatile
    private var cachedDevicePhysicalRamBytes: Long = 0L

    /**
     * Clears the cached RAM size. Useful for testing.
     */
    fun clearCache() {
        cachedDevicePhysicalRamBytes = 0L
    }

    /**
     * Gets the total physical memory of the device in bytes.
     */
    fun getDevicePhysicalRamBytes(context: Context): Long {
        if (cachedDevicePhysicalRamBytes > 0L) {
            return cachedDevicePhysicalRamBytes
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) {
                cachedDevicePhysicalRamBytes = memoryInfo.totalMem
                return memoryInfo.totalMem
            }
        }
        val ram = getRamFromMemInfo()
        if (ram > 0L) {
            cachedDevicePhysicalRamBytes = ram
        }
        return ram
    }

    /**
     * Gets the total physical memory of the device in GB.
     */
    fun getDevicePhysicalRamGb(context: Context): Double {
        val totalBytes = getDevicePhysicalRamBytes(context)
        return totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * One physical-RAM tier: devices with less than [upperGb] GiB of total memory (and at
     * least the previous tier's bound) get this marketed [label] and native buffer limits.
     */
    private class RamTier(val upperGb: Double, val label: String, val safeMb: Int, val warnMb: Int)

    // The single RAM-tier table behind getFriendlyRamLabel / getSafeNativeMemoryLimitMb /
    // getWarningNativeMemoryLimitMb. Label bounds are mid-points adjusted for up to 20%
    // hardware reservations. warnMb is ~1.3x safeMb on every tier.
    //
    // 3 GB rung: conservative (analysis F3, 2026-08-28). The v1340 stall event froze the
    // Shield at 413 MB native - inside the old 500 MB band - because the 3 GB also carries
    // Android, 4K HEVC decode surfaces and the launcher. safe 350 sits under that measured
    // failure point with margin; the engine's lowRamTier predicate (safeLimitMb <= 500)
    // stays true. warn 450 keeps the 413 MB stall point reading WARNING, not SAFE. A
    // warning limit BELOW safe (F3's first-draft ~280) would collapse getUsageStatus: SAFE
    // shrinks to the warning value, WARNING becomes unreachable, and the ShieldPreset's
    // 296 MB budget would read DANGER.
    private val RAM_TIERS = listOf(
        RamTier(1.15, "1 GB", safeMb = 150, warnMb = 180),
        RamTier(1.45, "1.5 GB", safeMb = 200, warnMb = 250),
        RamTier(LOW_RAM_TIER_UPPER_GB, "2 GB", safeMb = 250, warnMb = 325),
        RamTier(3.2, "3 GB", safeMb = 350, warnMb = 450),
        RamTier(4.8, "4 GB", safeMb = 1000, warnMb = 1200),
        RamTier(6.8, "6 GB", safeMb = 1600, warnMb = 2000),
        RamTier(9.6, "8 GB", safeMb = 2000, warnMb = 2500),
        RamTier(13.8, "12 GB", safeMb = 2000, warnMb = 2500),
        RamTier(Double.POSITIVE_INFINITY, "16 GB", safeMb = 2000, warnMb = 2500)
    )

    /** [getFriendlyRamLabel] when physical RAM is unreadable (the welcome card leaves it out). */
    const val UNKNOWN_RAM_LABEL = "Unknown"

    /** Physical RAM unreadable: the label says so and the limits use safe defaults. */
    private val UNKNOWN_RAM_TIER = RamTier(0.0, UNKNOWN_RAM_LABEL, safeMb = 250, warnMb = 325)

    private fun ramTier(context: Context): RamTier = ramTierFor(getDevicePhysicalRamBytes(context))

    private fun ramTierFor(totalMem: Long): RamTier {
        if (totalMem <= 0L) return UNKNOWN_RAM_TIER
        val gb = 1024L * 1024L * 1024L
        return RAM_TIERS.first { totalMem < it.upperGb * gb }
    }

    /** Upper bound of the 2 GB rung: the rungs up to it form the low-RAM tier. */
    private const val LOW_RAM_TIER_UPPER_GB = 2.3

    /**
     * Spec 56 A4: whether [totalMemBytes] of physical RAM falls on the 1, 1.5 or 2 GB rung
     * (MemoryBudget.isLowRamTier). Null when the figure is unreadable (<= 0).
     */
    fun isLowRamTier(totalMemBytes: Long): Boolean? =
        if (totalMemBytes <= 0L) null else ramTierFor(totalMemBytes).upperGb <= LOW_RAM_TIER_UPPER_GB

    /** Gets a friendly, marketed description of the device physical memory. */
    fun getFriendlyRamLabel(context: Context): String = ramTier(context).label

    /** The safe ExoPlayer native target buffer size limit in MB for this RAM tier. */
    fun getSafeNativeMemoryLimitMb(context: Context): Int = ramTier(context).safeMb

    /** The warning native target buffer size limit in MB for this RAM tier. */
    fun getWarningNativeMemoryLimitMb(context: Context): Int = ramTier(context).warnMb

    /**
     * Builds a [DefaultLoadControl] tuned for Nuvio performance when enabled, or
     * a conservative-tuned fallback when disabled (100 MB byte target + relaxed
     * buffer durations — not the stock builder defaults).
     */
    fun buildLoadControl(): DefaultLoadControl {
        return if (enabled) {
            val targetBufferBytes = (targetBufferSizeMb.toLong() * 1024L * 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            DefaultLoadControl.Builder()
                // Spec 36/S2 (B7-4): initial allocation count 0, the pool fills on demand.
                .setAllocator(DefaultAllocator(true, DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE, 0, enabled))
                .setTargetBufferBytes(targetBufferBytes)
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs
                )
                .setBackBuffer(backBufferMs, retainBackBufferFromKeyframe)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()
        }
    }

    // ─── BandwidthMeter ───────────────────────────────────────────────────────

    /**
     * Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when
     * enabled, or the platform default when disabled.
     */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter {
        return if (enabled) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(DEFAULT_NUVIO_INITIAL_BITRATE_ESTIMATE)
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }
    }

    // ─── Seek / Scrubbing ─────────────────────────────────────────────────────

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window (forward into [Player.getBufferedPosition] or
     * backward into the retained back-buffer).
     *
     * Only meaningful when performance mode is enabled; returns `false` otherwise.
     *
     * Seek F5b: the back window is derived from the *configured* [backBufferMs]
     * (the same value handed to `setBackBuffer`), not a hardcoded 10 s - with
     * the [DEFAULT_NUVIO_BACK_BUFFER_MS] default back buffer the old constant
     * over-claimed retention by ~8.5 s. The tolerance absorbs keyframe-boundary trimming slack. Note the
     * sole caller currently gates on forward seeks, so this branch is latent
     * until backward in-buffer seeks are enabled.
     */
    fun isSeekInBuffer(player: ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - backBufferMs.toLong() - SEEK_BACKWARD_TOLERANCE_MS)
            .coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    // ─── Buffering UI ─────────────────────────────────────────────────────────

    /**
     * Determines whether transient buffering UI should be suppressed during a
     * seek operation. Returns `false` when performance mode is disabled so that
     * the stock buffering indicator always shows.
     *
     * @param suppressBufferingUiForSeek  Flag set when an in-buffer seek is active.
     * @param seekBufferingUiDeferred     Flag set during the 1 s grace window.
     * @param isBuffering                 Current [Player.STATE_BUFFERING] state.
     */
    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) ||
            (seekBufferingUiDeferred && isBuffering)
    }

    // ─── Networking ───────────────────────────────────────────────────────────

    /**
     * Applies the shared connection pool and the protocol list to the given
     * [builder]: h2 + h1.1 negotiation when [enableHttp2] is set, h1.1-only
     * otherwise. Protocol choice follows the setting, not performance mode
     * (the old "when performance mode is enabled / no-op otherwise" claim was
     * stale — every caller applies it unconditionally).
     */
    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val withPool = builder.connectionPool(sharedConnectionPool)
        return if (enableHttp2) {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        } else {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        }
    }

    // ─── Engine Config ───────────────────────────────────────────────────────

    /**
     * Applies [NuvioEngineConfig] based on the toggle state.
     * When enabled: native off-heap allocation + zero-copy ByteBuffer pipeline + 64 KB scratch.
     * When disabled: stock heap allocation + standard byte[] pipeline + 4 KB scratch.
     *
     * Must be called **before** building an ExoPlayer instance.
     */
    private fun applyEngineConfig(performanceModeEnabled: Boolean) {
        if (performanceModeEnabled) {
            NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
        } else {
            NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        }
    }
}
