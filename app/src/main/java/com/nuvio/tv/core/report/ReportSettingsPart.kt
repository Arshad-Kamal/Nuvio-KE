package com.nuvio.tv.core.report

import com.nuvio.tv.core.assessment.ServiceNetworkTables
import java.time.Instant
import java.time.ZoneId

/** One Playback settings line: its section, the stored key and its raw value (A7). */
data class SettingLine(val section: String, val key: String, val value: String)

/**
 * Spec 116 D3.5 (A3, A7, B3 as approved in A8): the settings that change how a film plays, as stored
 * keys and raw values in English, the same text in every language; B3 shows these lines and the
 * report carries them. Never user-typed fields or subtitle look. Pure: the caller passes the stored
 * values by key name (all 26 are device keys, `DEVICE_PLAYER_KEYS`).
 */
object ReportSettingsPart {

    private const val NETWORK_TABLES = "service_network_tables"
    private const val CHUNK_KB = "parallel_chunk_size_kb"
    private const val CHUNK_MB = "parallel_chunk_size_mb"

    /** Sections and keys in B3's order. */
    val SECTIONS: List<Pair<String, List<String>>> = listOf(
        "player" to listOf(
            "internal_player_engine", "auto_switch_internal_player_on_error", "decoder_priority", "tunneling_enabled",
        ),
        "video" to listOf(
            "frame_rate_matching_mode", "resolution_matching_enabled", "afr_settle_hold_seconds", "dv7_handling_mode",
        ),
        "audio" to listOf(
            "allow_truehd_passthrough", "allow_dts_passthrough", "allow_dts_hd_passthrough", "mat_passthrough_enabled",
            "denied_codec_handling", "audio_output_channels",
        ),
        "buffer and network" to listOf(
            "buffer_budget_managed", "target_buffer_size_mb", "max_buffer_ms", "min_buffer_ms", "buffer_for_playback_ms",
            "buffer_for_playback_after_rebuffer_ms", "use_parallel_connections", "parallel_connection_count",
            CHUNK_KB, "enable_http2", "stream_limit_mode",
        ),
        "network test" to listOf(NETWORK_TABLES),
    )

    /**
     * The lines from [stored] (key name to stored value). A key with no stored value reads `default`.
     * The network-test table is reduced to its service count and newest test date: its stored form
     * names hosts.
     */
    fun lines(stored: Map<String, Any?>, zone: ZoneId): List<SettingLine> = SECTIONS.flatMap { (section, keys) ->
        keys.map { key ->
            val raw = stored[key]
            val value = when {
                // An install upgraded before the KB key keeps only the MB one, which the player still reads.
                raw == null && key == CHUNK_KB && stored[CHUNK_MB] != null -> "${stored[CHUNK_MB]} (as $CHUNK_MB)"
                raw == null -> "default"
                key == NETWORK_TABLES -> networkSummary(raw.toString(), zone)
                raw is Set<*> -> raw.map { it.toString() }.sorted().joinToString(",")
                else -> raw.toString()
            }
            SettingLine(section, key, value)
        }
    }

    /** `# section` headers and `key = value` lines, as uploaded. */
    fun text(lines: List<SettingLine>): String = buildString {
        var section: String? = null
        for (line in lines) {
            if (line.section != section) {
                if (section != null) append('\n')
                append("# ").append(line.section).append('\n')
                section = line.section
            }
            append(line.key).append(" = ").append(line.value).append('\n')
        }
    }.trimEnd('\n')

    private fun networkSummary(json: String, zone: ZoneId): String {
        val tables = ServiceNetworkTables.decode(json).tables.values
        if (tables.isEmpty()) return "none"
        val count = if (tables.size == 1) "1 service" else "${tables.size} services"
        val newest = tables.maxOf { it.testedAtMs }
        return if (newest <= 0L) count else "$count, tested ${Instant.ofEpochMilli(newest).atZone(zone).toLocalDate()}"
    }
}
