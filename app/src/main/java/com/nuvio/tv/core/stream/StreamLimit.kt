package com.nuvio.tv.core.stream

import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.core.assessment.ServiceTable
import com.nuvio.tv.core.assessment.qualifiesForNetworkRule
import com.nuvio.tv.domain.model.Stream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How the stream list limit is set (Spec 51 P2; Settings › Playback › Buffer & network › This TV). */
enum class StreamLimitMode {
    /** [NETWORK_TEST_HEADROOM_PERCENT] of each tested service's measured speed (Spec 51 D2: the default; Spec 58 F2). */
    NETWORK_TEST,

    /** One figure for every stream with a known size, torrents included (D3). */
    MANUAL,
    OFF;

    companion object {
        /** A stored name, else the default (an unknown or missing value). */
        fun fromStored(name: String?): StreamLimitMode = entries.firstOrNull { it.name == name } ?: NETWORK_TEST
    }
}

/** The stream list limit as stored (device-wide, Spec 51 P3). */
data class StreamLimitSettings(
    val mode: StreamLimitMode = StreamLimitMode.NETWORK_TEST,
    val manualMbps: Int = DEFAULT_MANUAL_MBPS
) {
    companion object {
        const val MIN_MANUAL_MBPS = 5
        const val MAX_MANUAL_MBPS = 100
        const val MANUAL_STEP_MBPS = 5

        /** As drawn in the approved mockup's picker. */
        const val DEFAULT_MANUAL_MBPS = 60

        /** The manual figure's range, on read (a stored value from elsewhere) and on write. */
        fun coerceManualMbps(mbps: Int): Int = mbps.coerceIn(MIN_MANUAL_MBPS, MAX_MANUAL_MBPS)
    }
}

/**
 * Where a NETWORK_TEST figure came from (Spec 51 D7, Spec 58 D1): the service, when it was tested,
 * and the speed it measured ([networkMeasuredMbps]), all of which the hidden line names.
 */
data class NetworkTestSource(val serviceKey: String, val testedAtMs: Long, val measuredMbps: Int)

/**
 * The limit as it applies to one stream: its [estimateMbps] against [figureMbps], the whole Mbit/s
 * the chip, the hidden line and Settings name. [source] is the network test's, null under MANUAL.
 */
data class StreamLimitVerdict(
    val estimateMbps: Double,
    val figureMbps: Int,
    val source: NetworkTestSource?
) {
    val hidden: Boolean get() = estimateMbps > figureMbps
}

/**
 * The verdict for [stream], or null when no limit applies to it (OFF; no estimate; under
 * NETWORK_TEST also a torrent, a stream outside the network rule, or a service with no table on
 * this link). [transport] is the link now, [runtimeMinutes] / [episode] the list's (see
 * [estimatedStreamMbps]). The tables count as stored, whether Automatic network is on or not.
 * [debridTableKeys] is the list's [debridServiceTableKeys]: the table a row takes when its own
 * URL reaches none. No default: a verdict depends on the list it is judged in.
 */
fun streamLimitVerdict(
    stream: Stream,
    settings: StreamLimitSettings,
    tables: ServiceNetworkTables,
    transport: NetworkTransport,
    runtimeMinutes: Int?,
    episode: Boolean,
    debridTableKeys: Map<String, String>
): StreamLimitVerdict? = when (settings.mode) {
    StreamLimitMode.OFF -> null
    StreamLimitMode.MANUAL -> estimatedStreamMbps(stream, runtimeMinutes, episode)
        ?.let { StreamLimitVerdict(it, settings.manualMbps, source = null) }
    StreamLimitMode.NETWORK_TEST ->
        networkTestVerdict(stream, tables, transport, runtimeMinutes, episode, debridTableKeys)
}

/**
 * Spec 59 N-10: per debrid service ([com.nuvio.tv.domain.model.AioStreamData.serviceId], AIOStreams
 * `streamData.service.id`, "premiumize"), the one table
 * key that service's rows in [streams] reach through their own URLs. A row from a domain no test
 * has reached (STorz on its StremThru host, MediaFusion on ElfHosted) serves from the same
 * service's CDN, so it takes that table. A service whose rows reach two keys (a proxying addon
 * tested on its own) or none maps to nothing: those rows stay unjudged and show (Spec 51 D5).
 */
fun debridServiceTableKeys(streams: List<Stream>, tables: ServiceNetworkTables): Map<String, String> {
    if (tables.tables.isEmpty()) return emptyMap()
    val reached = HashMap<String, MutableSet<String>>()
    for (stream in streams) {
        val service = stream.streamData?.serviceId ?: continue
        val key = stream.getStreamUrl()?.let(tables::tableKeyFor) ?: continue
        reached.getOrPut(service) { HashSet() } += key
    }
    return reached.mapNotNull { (service, keys) -> keys.singleOrNull()?.let { service to it } }.toMap()
}

private fun networkTestVerdict(
    stream: Stream,
    tables: ServiceNetworkTables,
    transport: NetworkTransport,
    runtimeMinutes: Int?,
    episode: Boolean,
    debridTableKeys: Map<String, String>
): StreamLimitVerdict? {
    val url = stream.getStreamUrl() ?: return null
    val size = countedSizeBytes(stream, episode)
    if (!qualifiesForNetworkRule(url, size, stream.isTorrent())) return null
    // The row's own URL first: a domain tested on its own keeps its table.
    val key = tables.tableKeyFor(url)
        ?: stream.streamData?.serviceId?.let(debridTableKeys::get)
        ?: return null
    val table = tables.tables[key] ?: return null
    val fastest = fastestOn(table, transport) ?: return null
    val estimate = estimatedStreamMbps(size, stream, runtimeMinutes) ?: return null
    val source = NetworkTestSource(key, table.testedAtMs, networkMeasuredMbps(fastest))
    return StreamLimitVerdict(estimate, networkLimitFigureMbps(fastest), source)
}

/**
 * One tested service's limit as Settings names it (Spec 51 P7): [figureMbps] the limit, [measuredMbps]
 * the speed it keeps [NETWORK_TEST_HEADROOM_PERCENT] of (Spec 58 D1 names both).
 */
data class ServiceLimitFigure(val serviceKey: String, val figureMbps: Int, val measuredMbps: Int)

/**
 * Every service whose table sets the NETWORK_TEST limit on [transport] now, in key order (the This
 * TV tables' order), each with the figure its verdicts use; empty = the network test hides nothing
 * yet. A table no stream reaches ([ServiceNetworkTables.reachable]) sets nothing, so it is left
 * out. Settings' Stream list limit row and picker name these.
 */
fun networkLimitFigures(tables: ServiceNetworkTables, transport: NetworkTransport): List<ServiceLimitFigure> =
    tables.tables.toSortedMap().mapNotNull { (key, table) ->
        if (!tables.reachable(key)) return@mapNotNull null
        fastestOn(table, transport)?.let {
            ServiceLimitFigure(key, networkLimitFigureMbps(it), networkMeasuredMbps(it))
        }
    }

/**
 * [table]'s fastest row in Mbit/s on [transport]; null when it counts as none on this link (tested on
 * another) or has no row above 0.
 */
private fun fastestOn(table: ServiceTable, transport: NetworkTransport): Double? =
    if (table.testedOnOtherLink(transport)) null else table.rows.maxOfOrNull { it.mbps }?.takeIf { it > 0 }

/**
 * The share of a service's measured speed the NETWORK_TEST limit keeps, in percent (Spec 58 F2, Arshad
 * 2026-11-04; the one home: the Settings copy formats it). A stream at the full measured speed plays,
 * but a seek refills the buffer at that same speed and stalls; at 80% a fifth stays spare (the seek
 * headroom the addon's 40 Mbps cap gave, 39/D28, 12/O-1). Replaces Spec 51 D6's 1x stall line.
 * `stream_limit_note` says "a fifth" in words (approved copy): change it with this value.
 */
const val NETWORK_TEST_HEADROOM_PERCENT = 80

/**
 * The speed the hidden line and Settings name as measured (Spec 58 D1): the fastest row [fastestMbps]
 * rounded to nearest, as the This TV table prints its rows (132.4 -> 132).
 */
fun networkMeasuredMbps(fastestMbps: Double): Int = fastestMbps.roundToInt()

/**
 * The NETWORK_TEST limit for a service whose fastest row is [fastestMbps]: [NETWORK_TEST_HEADROOM_PERCENT]
 * of it, rounded up so a rounding error never hides a stream (56 -> 45, 90 -> 72, 132.4 -> 106). Times
 * the whole percent, then / 100: `90 * 0.8` is 72.00000000000001 in floating point (ceil 73), `90 * 80 / 100`
 * is 72.0.
 */
fun networkLimitFigureMbps(fastestMbps: Double): Int =
    ceil(fastestMbps * NETWORK_TEST_HEADROOM_PERCENT / 100.0).toInt()
