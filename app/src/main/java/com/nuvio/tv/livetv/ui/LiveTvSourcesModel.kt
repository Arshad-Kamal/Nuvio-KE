package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.parse.EpgSnapshot
import com.nuvio.tv.livetv.source.LiveSource
import com.nuvio.tv.livetv.source.LiveSourceManager
import com.nuvio.tv.livetv.source.SourceKind
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A Sources row's health label (D16 stage 14), the row's last word. */
sealed interface SourceHealth {
    data object Ok : SourceHealth
    data class Lost(val count: Int) : SourceHealth
    /** The last refresh kept part of a guide (cut-short file). */
    data object Partial : SourceHealth
    /** The last refresh failed; [LiveSource.lastError] says why. */
    data object Failed : SourceHealth
    /** Added, not fetched yet. */
    data object Waiting : SourceHealth
    data object Refreshing : SourceHealth
}

/**
 * One Sources row: [listed] = the playlist's or addon's channels, or the channels a guide file
 * holds (null until it is cached); [matched] = a guide's matched channels; [preview] = the
 * built-in preset's channel names (its line lists them).
 */
data class SourceRow(
    val source: LiveSource,
    val listed: Int?,
    val matched: Int?,
    val health: SourceHealth,
    val preview: List<String> = emptyList()
)

/**
 * A guide offered on Sources: the Pluto guide (Spec 38 S2 stage 6) or the India guides (Spec 49
 * D27 F5). [channels] = listed channels it covers; [sourceName] = the one source holding them all,
 * null when several do.
 */
data class GuideSuggestion(val channels: Int, val sourceName: String?)

/** One hidden channel, for Sources > Hidden channels. */
data class HiddenRow(val key: String, val name: String, val sourceName: String?)

/** How long ago a source refreshed, as the row says it (stage 14). */
sealed interface RefreshAge {
    data object Never : RefreshAge
    data object JustNow : RefreshAge
    data class Minutes(val count: Int) : RefreshAge
    /** Earlier today, at [atMs]. */
    data class Today(val atMs: Long) : RefreshAge
    /** [count] calendar days ago (1 = yesterday). */
    data class Days(val count: Int) : RefreshAge
}

/** Pure derivations of the Sources route (Spec 34 L7e). */
object LiveTvSourcesModel {

    fun rows(
        sources: List<LiveSource>,
        channels: List<Channel>,
        guides: Map<String, EpgSnapshot>,
        refreshing: Set<String>
    ): List<SourceRow> {
        val lost = channels.filter { it.lostAtMs != null }.groupingBy { it.sourceId }.eachCount()
        return sources.map { source ->
            val guide = source.kind == SourceKind.XMLTV
            SourceRow(
                source = source,
                listed = if (guide) guides[source.id]?.channels?.size else source.channelCount,
                matched = if (guide) source.channelCount else null,
                health = health(source, lost[source.id] ?: 0, source.id in refreshing),
                preview = if (source.id == LiveSourceManager.PRESET_ID) {
                    channels.filter { it.sourceId == source.id && it.lostAtMs == null }.map { it.name }
                } else {
                    emptyList()
                }
            )
        }
    }

    /**
     * The Pluto guide suggestion: shown while a source's listed (not lost) channels hold a
     * `jmp2.uk/plu-` stream and no source has the guide's URL; null otherwise.
     */
    fun plutoSuggestion(sources: List<LiveSource>, channels: List<Channel>): GuideSuggestion? {
        if (sources.any { it.url == LiveSourceManager.PLUTO_GUIDE.url }) return null
        return suggestion(sources, channels.filter { it.lostAtMs == null && ChannelIdentity.plutoIds(it).isNotEmpty() })
    }

    /**
     * The India guides suggestion (Spec 49 D27 F5): shown while listed (not lost) channels come from
     * India and no source has either guide's URL; null otherwise.
     */
    fun indiaSuggestion(sources: List<LiveSource>, channels: List<Channel>): GuideSuggestion? {
        val urls = LiveSourceManager.INDIA_GUIDES.map { it.url }
        if (sources.any { it.url in urls }) return null
        return suggestion(
            sources,
            channels.filter { it.lostAtMs == null && ChannelIdentity.normalizeCountry(it.country) == INDIA }
        )
    }

    private fun suggestion(sources: List<LiveSource>, covered: List<Channel>): GuideSuggestion? {
        if (covered.isEmpty()) return null
        val holders = covered.mapTo(HashSet()) { it.sourceId }
        val name = holders.singleOrNull()?.let { id -> sources.firstOrNull { it.id == id }?.name }
        return GuideSuggestion(covered.size, name)
    }

    private const val INDIA = "in"

    internal fun health(source: LiveSource, lost: Int, refreshing: Boolean): SourceHealth = when {
        refreshing -> SourceHealth.Refreshing
        source.lastError == LiveSourceManager.GUIDE_INCOMPLETE -> SourceHealth.Partial
        source.lastError != null -> SourceHealth.Failed
        source.lastRefreshMs == null -> SourceHealth.Waiting
        lost > 0 -> SourceHealth.Lost(lost)
        else -> SourceHealth.Ok
    }

    /** Hidden channels that still exist, by name; keys of vanished channels are left out. */
    fun hidden(keys: Set<String>, channels: List<Channel>, sources: List<LiveSource>): List<HiddenRow> {
        if (keys.isEmpty()) return emptyList()
        val names = sources.associate { it.id to it.name }
        return channels.filter { it.key in keys }
            .map { HiddenRow(it.key, it.shownName, names[it.sourceId]) }
            .sortedBy { it.name.lowercase() }
    }

    fun refreshAge(lastMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): RefreshAge {
        if (lastMs == null) return RefreshAge.Never
        val elapsed = nowMs - lastMs
        if (elapsed < MINUTE_MS) return RefreshAge.JustNow
        if (elapsed < HOUR_MS) return RefreshAge.Minutes((elapsed / MINUTE_MS).toInt())
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(lastMs).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        ).toInt()
        return if (days <= 0) RefreshAge.Today(lastMs) else RefreshAge.Days(days)
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
}
