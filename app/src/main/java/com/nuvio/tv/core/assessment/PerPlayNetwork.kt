package com.nuvio.tv.core.assessment

import android.content.Context
import android.os.Build
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.core.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.isPublicServerHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Smallest file the network rule and its test run on (Spec 33: "web link over ~100 MB"). */
const val NETWORK_RULE_MIN_BYTES = 100L * 1000 * 1000

/**
 * A stream the per-service network rule covers (Spec 33, decided 2026-10-18): an http(s) link
 * to a public host (debrid or direct addon link; TorrServer on 127.0.0.1 and LAN servers are
 * not), not a torrent, of at least [NETWORK_RULE_MIN_BYTES]. The scheme is checked first:
 * isPublicServerHost answers true for a URL it cannot parse.
 */
fun qualifiesForNetworkRule(url: String, sizeBytes: Long?, isTorrent: Boolean): Boolean {
    if (isTorrent || url.toHttpUrlOrNull() == null) return false
    return isPublicServerHost(url) && (sizeBytes ?: 0L) >= NETWORK_RULE_MIN_BYTES
}

/**
 * The settings shape the per-play override works in: buffer + network masters on, budget
 * unmanaged. Settings › This TV says when Automatic network is on but inert outside it.
 */
fun inAutomaticShape(settings: PlayerSettings): Boolean =
    settings.bufferEngineEnabled && settings.parallelNetworkEnabled && !settings.bufferBudgetManaged

/**
 * Connections a play on [settings] asks for (Spec 33 D7's notice and pause chip): the parallel
 * count when the network master and parallel connections are both on, else 1, as
 * applyMediaSourceFactorySettings hands them to the factory. The factory opens them on
 * progressive files only; an HLS / DASH stream reads with one, so the count overstates there.
 */
fun connectionsInUse(settings: PlayerSettings): Int =
    if (settings.parallelNetworkEnabled && settings.useParallelConnections) {
        settings.parallelConnectionCount.coerceAtLeast(1)
    } else {
        1
    }

/** [settings] are what this play runs on; [choice] is the picked table row, null = the saved settings. */
data class PerPlayNetwork(val settings: PlayerSettings, val choice: NetworkChoice?)

/**
 * The per-play network override (Spec 33 D4): with the rule on and a table for the stream's
 * service, [choose] picks the row for this film and the result is a COPY of [saved]; nothing
 * is written, so Settings keeps showing the user's values. [saved] comes back unchanged when
 * the stream does not qualify, no table or film bitrate exists, or the saved settings are not
 * in the automatic shape (buffer + network masters on, budget unmanaged: a master off or a
 * managed budget is the user's own choice, and a managed budget would ignore the fitted
 * target anyway).
 *
 * [controllerHttp2] is the HTTP/2 value this controller's parallel chunk client was built with,
 * null until it is built (PlayerMediaSourceFactory.chunkClientHttp2): that client is built once
 * per controller, so a later stream in the same controller keeps its protocol.
 */
fun perPlayNetwork(
    saved: PlayerSettings,
    tables: ServiceNetworkTables,
    url: String,
    sizeBytes: Long?,
    runtimeMinutes: Int?,
    isTorrent: Boolean,
    limits: DeviceLimits,
    controllerHttp2: Boolean?
): PerPlayNetwork {
    val unchanged = PerPlayNetwork(saved, null)
    if (!tables.automatic || !qualifiesForNetworkRule(url, sizeBytes, isTorrent)) return unchanged
    if (!inAutomaticShape(saved)) return unchanged
    val table = tables.tableFor(url) ?: return unchanged
    val choice = choose(table, filmMbps(sizeBytes, runtimeMinutes), saved.toPlaybackConfig(), limits)
        ?: return unchanged
    val config = choice.config.copy(http2 = controllerHttp2 ?: choice.config.http2)
    return PerPlayNetwork(saved.withPlaybackConfig(config), choice.copy(config = config))
}

/**
 * [DeviceLimits] for a play, read without an Activity: the display is not inspected
 * (afrSupported null), so the fit gate leaves frame-rate matching as saved.
 */
fun playbackDeviceLimits(context: Context): DeviceLimits = DeviceLimits(
    safeMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context),
    tierMaxChunkMb = MemoryBudget.tierMaxChunkMb,
    perfModeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
    afrSupported = null
)
