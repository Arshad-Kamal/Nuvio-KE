package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.player.MemoryBudget
import com.nuvio.tv.data.local.localStoreJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The link a network test ran on; a switch between Wi-Fi and Ethernet re-tests (Spec 33). */
enum class NetworkTransport { WIFI, ETHERNET, OTHER }

/**
 * One measured configuration: [connections] = 1 is the single-connection baseline (parallel
 * off, [chunkMb] 0), else a parallel pass of [connections] x [chunkMb].
 */
@Serializable
data class NetworkRow(val connections: Int, val chunkMb: Int, val mbps: Double) {
    val parallel: Boolean get() = connections > 1
}

/**
 * One streaming service's sweep (Spec 33: one table per service, since services run separate
 * CDNs and per-connection throttles). [http2] is the sweep's h2 / h1.1 A/B verdict, null when it
 * did not run; HTTP/2 stays per service, not per film (the chunk client is built once per player).
 */
@Serializable
data class ServiceTable(
    val transport: NetworkTransport = NetworkTransport.OTHER,
    val testedAtMs: Long = 0L,
    val http2: Boolean? = null,
    val rows: List<NetworkRow> = emptyList()
) {
    /**
     * Tested on another link than [current] (a Wi-Fi / Ethernet switch, or tested on an unknown
     * link and now on a known one): the test re-runs, and the stream list limit (Spec 51 P2) counts
     * the table as none meanwhile. [current] OTHER (no network, or neither link) keeps every table.
     */
    fun testedOnOtherLink(current: NetworkTransport): Boolean =
        current != NetworkTransport.OTHER && transport != current
}

/**
 * Everything the per-film rule reads, stored as one device-wide JSON string
 * (PlayerSettingsDataStore.serviceNetworkTablesJson). [aliases] maps a request service key
 * (often the addon's domain) to the serving service key found after redirects, recorded by the
 * test. [automatic] false = the user set network values by hand: the rule stays off.
 * [retestRequestedAtMs] is Settings' "Run again" (Spec 33 D5): a table tested before it is due a
 * re-test on its service's next qualifying film, and keeps serving the rule until then. No size
 * bound: one entry per streaming service / addon domain the user plays from (a handful).
 */
@Serializable
data class ServiceNetworkTables(
    val automatic: Boolean = true,
    val tables: Map<String, ServiceTable> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val retestRequestedAtMs: Long = 0L
) {
    /** A table tested before the last "Run again" (D6 re-tests it on the next qualifying film). */
    fun retestDue(table: ServiceTable): Boolean = table.testedAtMs < retestRequestedAtMs

    /**
     * [key]'s table when it serves as is on [current]: not due the re-test "Run again" queued and
     * not tested on another link ([ServiceTable.testedOnOtherLink]). Null = a play from it tests.
     */
    fun currentTable(key: String, current: NetworkTransport): ServiceTable? =
        tables[key]?.takeUnless { retestDue(it) || it.testedOnOtherLink(current) }

    /** Whether any table still waits for the re-test "Run again" asked for. */
    val retestPending: Boolean get() = tables.values.any(::retestDue)

    /** The table for a stream URL: its service key, followed through [aliases]. */
    fun tableFor(url: String): ServiceTable? = tableKeyFor(url)?.let(tables::get)

    /** The key [tableFor] finds a table under (the serving service, "real-debrid.com"), else null. */
    fun tableKeyFor(url: String): String? {
        val key = serviceKeyOf(url) ?: return null
        return (aliases[key] ?: key).takeIf { it in tables }
    }

    /**
     * Whether [tableKeyFor] can return [key] for some URL: [key] is not aliased to another service,
     * or an alias leads to it. A table kept under a request domain that now aliases elsewhere (it
     * once served its own streams, [withTest] keeps that table) is reached by no stream.
     */
    fun reachable(key: String): Boolean = key !in aliases || key in aliases.values

    fun encode(): String = assessmentStoreJson.encodeToString(serializer(), this)

    companion object {
        /**
         * Null or unparseable JSON reads as no tables (automatic on), never an error. Unknown
         * keys are skipped and an unknown transport name reads as OTHER (coerceInputValues), so
         * a later build's blob keeps the user's automatic flag and tables.
         */
        fun decode(json: String?): ServiceNetworkTables =
            json?.let { runCatching { assessmentStoreJson.decodeFromString(serializer(), it) }.getOrNull() }
                ?: ServiceNetworkTables()
    }
}

/**
 * The service a URL belongs to: the host's last two labels ("download.real-debrid.com" and
 * "1.cdn.real-debrid.com" are both "real-debrid.com"), three under a two-letter country code
 * whose second level is a generic one ("foo.co.uk"); IP addresses and two-label hosts as they
 * are. Null when the URL does not parse.
 *
 * Not OkHttp's topPrivateDomain(): the runtime OkHttp (docs/stack-notes.md) loads its
 * public-suffix list from an Android asset; it throws on the unit-test JVM, and its on-device
 * load is unverified while Shield tests are paused (Spec 33 D3). An
 * unlisted public suffix (e.g. "ne.jp") would put its services under one key; the debrid and CDN
 * hosts this fork streams from sit under plain generic TLDs, and every row is still memory-fitted.
 */
fun serviceKeyOf(url: String): String? {
    val host = url.toHttpUrlOrNull()?.host?.trimEnd('.') ?: return null
    if (':' in host || host.all { it.isDigit() || it == '.' }) return host
    val labels = host.split('.')
    if (labels.size <= 2) return host
    val countrySecondLevel = labels.last().length == 2 && labels[labels.size - 2] in GENERIC_SECOND_LEVEL
    return labels.takeLast(if (countrySecondLevel) 3 else 2).joinToString(".")
}

private val GENERIC_SECOND_LEVEL = setOf("co", "com", "net", "org", "ac", "gov", "edu")

/**
 * localStoreJson plus coerceInputValues: an unknown enum name reads as the field's default. The
 * codec of the device-store JSON blobs in this package (tables, DeviceSetupRecord).
 */
@OptIn(ExperimentalSerializationApi::class)
internal val assessmentStoreJson = Json(localStoreJson) { coerceInputValues = true }

/**
 * The table a finished sweep leaves: the single-connection baseline plus every parallel pass
 * (best figure per configuration). Null when the sweep ended in error or measured nothing.
 */
fun tableFromSweep(
    outcome: StreamSweepEngine.SweepOutcome,
    transport: NetworkTransport,
    nowMs: Long
): ServiceTable? {
    if (outcome.errorText != null) return null
    val baseline = NetworkRow(1, 0, outcome.baselineMbps).takeIf { it.mbps > 0 }
    val passes = outcome.measured
        .filter { it.mbps > 0 }
        .groupBy { it.connections to it.chunkMb }
        .map { (cell, runs) -> NetworkRow(cell.first, cell.second, runs.maxOf { it.mbps }) }
    val rows = listOfNotNull(baseline) + passes.sortedWith(compareBy({ it.connections }, { it.chunkMb }))
    if (rows.isEmpty()) return null
    return ServiceTable(transport, nowMs, outcome.h2Variant?.let { !it.h1Wins }, rows)
}

/** A film's average bitrate, Mbit/s: size over runtime. Null when either is unknown. */
fun filmMbps(sizeBytes: Long?, runtimeMinutes: Int?): Double? {
    val size = sizeBytes?.takeIf { it > 0 } ?: return null
    val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return null
    return averageMbps(size, minutes * 60.0)
}

/** [sizeBytes] over [seconds] as Mbit/s (decimal mega, as the network test measures). */
fun averageMbps(sizeBytes: Long, seconds: Double): Double = sizeBytes * 8.0 / seconds / 1_000_000.0

/** What the per-film rule picked; [mayStall] = the picked row is below the film's bitrate. */
data class NetworkChoice(val config: PlaybackConfig, val row: NetworkRow, val mayStall: Boolean)

/** The bar a row must reach: the sweep's own target multiple and near-miss tolerance. */
private const val NETWORK_BAR_OF_BITRATE =
    StreamSweepEngine.TARGET_MULTIPLE * StreamSweepEngine.SUFFICIENCY_TOLERANCE

/** The highest film bitrate, Mbit/s, a row measured at [rowMbps] clears [choose]'s bar for. */
fun filmsUpToMbps(rowMbps: Double): Double = rowMbps / NETWORK_BAR_OF_BITRATE

/**
 * The per-film rule (Spec 33, decided 2026-10-18): the cheapest row (least parallel memory) whose
 * throughput reaches 2x the film's bitrate (the sweep's 5% near-miss tolerance included); none
 * does -> the fastest row, [NetworkChoice.mayStall] when it is below 1x. Null (keep [saved])
 * without a film bitrate or a row that fits.
 *
 * Each row is applied over [saved] with the target first cut to fit beside the row's pipe (never
 * below the gate's floor: throughput before depth), then passed through [fitPlaybackConfig]. A
 * row the gate still had to thin (smaller chunk, fewer connections, parallel off) is dropped,
 * since that is not the configuration that was measured.
 */
fun choose(table: ServiceTable, filmMbps: Double?, saved: PlaybackConfig, limits: DeviceLimits): NetworkChoice? {
    val film = filmMbps?.takeIf { it > 0 } ?: return null
    val floorMb = fitFloorMb(limits, film)
    val fitting = table.rows.mapNotNull { row ->
        val piped = saved.withRow(row, table.http2)
        val roomMb = MemoryBudget.maxSafeTargetMb(limits.safeMb, piped.parallelOverheadMb)
        val wanted = piped.copy(targetBufferMb = minOf(saved.targetBufferMb, maxOf(floorMb, roomMb)))
        val fitted = fitPlaybackConfig(wanted, limits, film)
        val kept = !row.parallel ||
            (fitted.parallel && fitted.connections == row.connections && fitted.chunkKb == wanted.chunkKb)
        NetworkChoice(fitted, row, mayStall = row.mbps < film).takeIf { kept }
    }
    return fitting
        .filter { it.row.mbps >= film * NETWORK_BAR_OF_BITRATE }
        .minWithOrNull(compareBy<NetworkChoice> { it.config.parallelOverheadMb }.thenByDescending { it.row.mbps })
        ?: fitting.maxByOrNull { it.row.mbps }
}

/**
 * The saved network a finished test leaves (Spec 33 D6), for the films the rule cannot judge (no
 * size or runtime): the fastest row that fits beside the saved buffer as it is. Unlike [choose] it
 * never cuts the target or the back buffer: [choose] caps every film at the saved target, so a
 * cut here would stay for every later film. Null when not even the single connection fits.
 */
fun savedNetworkAfterTest(table: ServiceTable, saved: PlaybackConfig, limits: DeviceLimits): PlaybackConfig? =
    table.rows.sortedByDescending { it.mbps }.firstNotNullOfOrNull { row ->
        val wanted = saved.withRow(row, table.http2)
        fitPlaybackConfig(wanted, limits, filmMbps = null).takeIf { it == wanted }
    }

/** [row]'s pipe over these values (a single-connection row keeps the stored pipe size, parallel off). */
private fun PlaybackConfig.withRow(row: NetworkRow, tableHttp2: Boolean?): PlaybackConfig = copy(
    parallel = row.parallel,
    connections = if (row.parallel) row.connections else connections,
    chunkKb = if (row.parallel) row.chunkMb * 1024 else chunkKb,
    http2 = tableHttp2 ?: http2
)
