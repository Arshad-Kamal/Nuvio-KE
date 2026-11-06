package com.nuvio.tv.core.stream

import com.nuvio.tv.core.assessment.NetworkTransport
import com.nuvio.tv.core.assessment.ServiceNetworkTables
import com.nuvio.tv.domain.model.SourceChipItem
import com.nuvio.tv.domain.model.Stream

/*
 * The stream list under the limits (Spec 51 P4; Spec 58 F3 adds the resolution limit): one hidden
 * set and one derivation, shared by the Stream screen, the player's Sources and episode-streams
 * panels and every auto-play caller (P5), so what a list hides is what auto-play skips.
 */

/**
 * Why a stream is hidden (Spec 58 D3): over the stream list limit ([bitrate]), the resolution limit
 * ([resolution]), or both. At least one is set.
 */
data class HiddenStream(
    val bitrate: StreamLimitVerdict? = null,
    val resolution: ResolutionVerdict? = null
)

/** Everything the limits read that is not the stream list: the setting, the network tables, the resolution limit. */
data class StreamLimitInputs(
    val settings: StreamLimitSettings = StreamLimitSettings(),
    val tables: ServiceNetworkTables = ServiceNetworkTables(),
    val resolution: ResolutionLimit = ResolutionLimit()
)

/** What a stream list shows (Spec 51 M7): one value per list, the user's own pick. */
sealed interface StreamListSelection {
    data object All : StreamListSelection
    data object Hidden : StreamListSelection
    data class Addon(val name: String) : StreamListSelection
}

/**
 * A stream's key in the hidden set (R5-7): [Stream.badgeMergeKey] plus the stream URL, since the
 * merge key alone is addon + infoHash for a torrent, so one file cached on two debrid services
 * (two network tables) would share it.
 */
fun Stream.limitKey(): String = badgeMergeKey() + "|" + (getStreamUrl() ?: "")

/**
 * The streams the limits hide, by [limitKey], with why. The stream list limit reads nothing while
 * [limit] is unknown (null), OFF, or NETWORK_TEST with no network table; the resolution limit
 * nothing while its [ResolutionLimit.cap] is null (OFF, an unknown or 4K TV). [transport] is read
 * once, only under NETWORK_TEST with a table (a system call). [runtimeMinutes] / [episode] are the
 * list's (see [estimatedStreamMbps]); a row whose own URL reaches no table takes its debrid
 * service's table as the list's other rows reach it ([debridServiceTableKeys]), so a verdict
 * depends on its list. Never hidden: the stream whose key is [playingKey], and under
 * an [exemptBingeGroup] (the playing stream is itself over a limit and Prefer Binge Group is on)
 * every stream of that group.
 */
fun hiddenStreamVerdicts(
    streams: List<Stream>,
    limit: StreamLimitInputs?,
    transport: () -> NetworkTransport,
    runtimeMinutes: Int?,
    episode: Boolean,
    playingKey: String? = null,
    exemptBingeGroup: String? = null
): Map<String, HiddenStream> {
    if (limit == null || streams.isEmpty()) return emptyMap()
    val networkTest = limit.settings.mode == StreamLimitMode.NETWORK_TEST
    val bitrateLimited = limit.settings.mode != StreamLimitMode.OFF && !(networkTest && limit.tables.tables.isEmpty())
    val resolutionLimited = limit.resolution.cap != null
    if (!bitrateLimited && !resolutionLimited) return emptyMap()
    val link = if (bitrateLimited && networkTest) transport() else NetworkTransport.OTHER
    // Spec 59 N-10: one mapping per list, built from every row before the playing / exempt skips,
    // so each caller judging this list (the playing row's check included) takes the same tables.
    val debridTableKeys = if (bitrateLimited && networkTest) debridServiceTableKeys(streams, limit.tables) else emptyMap()
    val hidden = LinkedHashMap<String, HiddenStream>()
    for (stream in streams) {
        if (exemptBingeGroup != null && stream.behaviorHints?.bingeGroup == exemptBingeGroup) continue
        val key = stream.limitKey()
        if (key == playingKey || key in hidden) continue
        val bitrate = if (bitrateLimited) {
            streamLimitVerdict(stream, limit.settings, limit.tables, link, runtimeMinutes, episode, debridTableKeys)
                ?.takeIf { it.hidden }
        } else {
            null
        }
        val resolution = if (resolutionLimited) resolutionLimitVerdict(stream, limit.resolution) else null
        if (bitrate != null || resolution != null) hidden[key] = HiddenStream(bitrate, resolution)
    }
    return hidden
}

/**
 * The addons the chip row shows, in its order, each once: those with streams, then any other
 * source chip (still loading, or failed until its chip is removed). StreamSourcesPanel draws this
 * row, its chips keyed by name: two addons the user renamed alike share one chip (and one
 * Addon(name) view) rather than a duplicate key.
 */
fun sourceChipAddonNames(availableAddons: List<String>, sourceChips: List<SourceChipItem>): List<String> =
    LinkedHashSet<String>().apply {
        addAll(availableAddons)
        sourceChips.forEach { add(it.name) }
    }.toList()

/**
 * This pick, kept while its addon still has a chip in [chipAddons] ([sourceChipAddonNames]), else
 * All (m3). Every chip writer that can drop a name applies it, so a reload that brings the chip
 * back does not reopen an empty view of an addon whose chip had left the row.
 */
fun StreamListSelection.keptIn(chipAddons: Collection<String>): StreamListSelection =
    if (this is StreamListSelection.Addon && name !in chipAddons) StreamListSelection.All else this

/** [hiddenStreamVerdicts]' keys: the set auto-play skips (AutoPlaySelection.Inputs.hiddenKeys). */
fun hiddenStreamKeys(
    streams: List<Stream>,
    limit: StreamLimitInputs?,
    transport: () -> NetworkTransport,
    runtimeMinutes: Int?,
    episode: Boolean,
    playingKey: String? = null,
    exemptBingeGroup: String? = null
): Set<String> =
    hiddenStreamVerdicts(streams, limit, transport, runtimeMinutes, episode, playingKey, exemptBingeGroup).keys

/**
 * One stream list as derived (Spec 51 P4). [selection] is the effective one (Hidden with nothing
 * hidden, or an addon no longer in the chip row, reads as All), the one every chip highlight and focus rule
 * reads; the stored pick changes only on a user pick. [streams] are the rows the view lists;
 * [listsHidden] when they are hidden streams: the Hidden view, or a view whose shown list is empty
 * while it has hidden streams (N3, N4). Counts are per chip, each the rows its view lists (r5):
 * [allCount] for All, [addonCounts] per addon name, [hiddenCount] for the Hidden chip. [total] is
 * every stream, shown or hidden.
 */
data class StreamListView(
    val selection: StreamListSelection = StreamListSelection.All,
    val streams: List<Stream> = emptyList(),
    val listsHidden: Boolean = false,
    val hiddenVerdicts: Map<String, HiddenStream> = emptyMap(),
    val hiddenCount: Int = 0,
    val allCount: Int = 0,
    val addonCounts: Map<String, Int> = emptyMap(),
    val total: Int = 0
)

/**
 * The bitrate verdict the limit chip and the hidden line both name (Spec 51 P6, R4-11): the lowest
 * figure among the streams hidden for bitrate, the first such in list order, so chip and line
 * always agree; null when none is.
 */
val StreamListView.limitVerdict: StreamLimitVerdict?
    get() = hiddenVerdicts.values.mapNotNull { it.bitrate }.minByOrNull { it.figureMbps }

/**
 * The resolution verdict the chip and the line name (Spec 58 D3): every stream hidden for resolution
 * is over the one cap, so the first; null when none is.
 */
val StreamListView.resolutionVerdict: ResolutionVerdict?
    get() = hiddenVerdicts.values.firstNotNullOfOrNull { it.resolution }

/**
 * Derives [all] under [selection] and the hidden set [hidden] (from [hiddenStreamVerdicts] over
 * the same list). [chipAddons] are the addons the chip row shows ([sourceChipAddonNames]): an addon
 * still loading has a chip but no stream yet and stays selected (an empty view, as before the
 * limit). The only fallback rule for a selection: nothing writes it back.
 */
fun deriveStreamList(
    all: List<Stream>,
    selection: StreamListSelection,
    hidden: Map<String, HiddenStream>,
    chipAddons: Collection<String>
): StreamListView {
    val (hiddenStreams, shown) = if (hidden.isEmpty()) {
        emptyList<Stream>() to all
    } else {
        all.partition { it.limitKey() in hidden }
    }
    val effective = when (selection) {
        StreamListSelection.All -> selection
        StreamListSelection.Hidden -> if (hiddenStreams.isEmpty()) StreamListSelection.All else selection
        is StreamListSelection.Addon ->
            if (selection.name in chipAddons || all.any { it.addonName == selection.name }) selection
            else StreamListSelection.All
    }

    /** A view's rows (r5, N3): its shown streams, else its hidden ones when it has no shown stream. */
    fun rowsOf(shownRows: List<Stream>, hiddenRows: List<Stream>): Pair<List<Stream>, Boolean> =
        if (shownRows.isEmpty() && hiddenRows.isNotEmpty()) hiddenRows to true else shownRows to false

    /** The same rule as a chip count: the rows its view lists. */
    fun countOf(shownRows: Int, hiddenRows: Int): Int = if (shownRows == 0) hiddenRows else shownRows

    val (rows, listsHidden) = when (effective) {
        StreamListSelection.All -> rowsOf(shown, hiddenStreams)
        StreamListSelection.Hidden -> hiddenStreams to true
        is StreamListSelection.Addon -> rowsOf(
            shown.filter { it.addonName == effective.name },
            hiddenStreams.filter { it.addonName == effective.name }
        )
    }
    val shownByAddon = shown.groupingBy { it.addonName }.eachCount()
    val hiddenByAddon = hiddenStreams.groupingBy { it.addonName }.eachCount()
    val addonCounts = all.mapTo(LinkedHashSet()) { it.addonName }.associateWith { name ->
        countOf(shownByAddon[name] ?: 0, hiddenByAddon[name] ?: 0)
    }
    return StreamListView(
        selection = effective,
        streams = rows,
        listsHidden = listsHidden,
        hiddenVerdicts = hidden,
        hiddenCount = hiddenStreams.size,
        allCount = countOf(shown.size, hiddenStreams.size),
        addonCounts = addonCounts,
        total = all.size
    )
}
