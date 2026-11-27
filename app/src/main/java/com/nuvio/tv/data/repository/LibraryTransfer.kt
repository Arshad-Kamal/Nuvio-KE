package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryEntry
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput

/**
 * Library transfer planning (Spec 104 S4a, mockup stage D1): pure, it decides what a Copy or Move would do; the
 * transfer service performs it. Ported in idea from `5956fc92c^:.../LibraryTransfer.kt`, rewritten for KE (one id per
 * [LibraryEntry]; trackers give every id they have, [TrackingLibraryEntry.externalIds]).
 *
 * Identity is (type, id) with only ids every tracker resolves: `tt<digits>` and `tmdb:<digits>`. A bare number,
 * `kitsu:`, `mal:` or a custom addon id "can't be matched" (a bare number is not taken as TMDB: an addon's own number
 * would name another title on the tracker, and a Move would then delete the original). Movies and series never match
 * each other, whatever their ids. Titles sharing any key are one title: a source holding the same film as `tt...` and
 * `tmdb:...` copies it once and a Move removes both entries.
 */

/** One title in a plan: [members] are every source entry for it (usually one); [first] is the one written. */
data class TransferTitle(val members: List<TrackingLibraryEntry>, val keys: Set<String>) {
    val first: TrackingLibraryEntry get() = members.first()
}

data class LibraryTransferPlan(
    /** Unique titles in From, the unmatched ones included ("In <from>"). */
    val sourceTitles: Int,
    /** Not at the destination yet ("To copy"). */
    val toCopy: List<TransferTitle>,
    /** At the destination already ("Already in <to>"). */
    val alreadyThere: List<TransferTitle>,
    /** With no id a destination can resolve ("Can't be matched"); they stay where they are. */
    val unmatched: Int
) {
    /** What a Move removes from From once the destination lists it (D11): copied + already there. */
    val moveCandidates: List<TransferTitle> get() = toCopy + alreadyThere
}

/** `movie`, `series` (`show`, `tv` and `anime` too: trackers report only movie or series), else as given, lowercase. */
internal fun transferType(type: String): String = when (val t = type.trim().lowercase()) {
    "show", "shows", "tv", "series", "anime" -> "series"
    "movie", "movies", "film" -> "movie"
    else -> t
}

private val IMDB_ID = Regex("^(?:imdb:)?(tt\\d+)$", RegexOption.IGNORE_CASE)
private val TMDB_ID = Regex("^tmdb:(\\d+)$", RegexOption.IGNORE_CASE)

/** `tt...` (lowercase) or `tmdb:N`, else null. */
internal fun transferId(id: String): String? {
    val raw = id.trim()
    IMDB_ID.matchEntire(raw)?.let { return it.groupValues[1].lowercase() }
    TMDB_ID.matchEntire(raw)?.let { return "tmdb:${it.groupValues[1].trimStart('0').ifEmpty { "0" }}" }
    return null
}

/** Every (type, id) key [entry] is known by; empty = can't be matched (also any type but movie or series: a tracker has no other). */
internal fun transferKeys(entry: TrackingLibraryEntry): Set<String> {
    val type = transferType(entry.entry.type)
    if (type != "movie" && type != "series") return emptySet()
    return (entry.externalIds + entry.entry.id).mapNotNullTo(linkedSetOf()) { id -> transferId(id)?.let { "$type|$it" } }
}

/** A local entry has no other ids. */
internal fun LibraryEntry.asTransferEntry(): TrackingLibraryEntry = TrackingLibraryEntry(this)

/**
 * Groups [entries] sharing any key into titles, in first-seen order; the second value counts unmatched entries.
 * [bridges] (the destination's key sets) also join entries: a local `tt603` and a local `tmdb:603` are one title when
 * the destination lists one entry with both ids.
 */
internal fun groupTransferTitles(
    entries: List<TrackingLibraryEntry>,
    bridges: List<Set<String>> = emptyList()
): Pair<List<TransferTitle>, Int> {
    val members = mutableListOf<MutableList<TrackingLibraryEntry>>()
    val keys = mutableListOf<MutableSet<String>>()
    val groupOfKey = HashMap<String, Int>()
    // A merged group points at the group that absorbed it.
    val mergedInto = mutableListOf<Int>()
    fun root(i: Int): Int {
        var r = i
        while (mergedInto[r] != r) r = mergedInto[r]
        return r
    }
    var unmatched = 0
    val inputs = entries.map { it to transferKeys(it) } + bridges.map { null to it }
    for ((entry, entryKeys) in inputs) {
        if (entryKeys.isEmpty()) {
            if (entry != null) unmatched++
            continue
        }
        val found = entryKeys.mapNotNull { groupOfKey[it]?.let(::root) }.distinct().sorted()
        val target = found.firstOrNull() ?: members.size.also {
            members += mutableListOf<TrackingLibraryEntry>()
            keys += mutableSetOf<String>()
            mergedInto += it
        }
        for (other in found.drop(1)) {
            members[target] += members[other]
            keys[target] += keys[other]
            members[other].clear()
            mergedInto[other] = target
        }
        if (entry != null) members[target] += entry
        keys[target] += entryKeys
        keys[target].forEach { groupOfKey[it] = target }
    }
    val titles = members.indices.filter { root(it) == it && members[it].isNotEmpty() }.map { TransferTitle(members[it].toList(), keys[it].toSet()) }
    return titles to unmatched
}

/** Plans a transfer of [source] into a destination holding [destination]. */
internal fun computeTransferPlan(
    source: List<TrackingLibraryEntry>,
    destination: List<TrackingLibraryEntry>
): LibraryTransferPlan {
    val destinationKeySets = destination.map(::transferKeys)
    val destinationKeys = destinationKeySets.flatMapTo(HashSet()) { it }
    val (titles, unmatched) = groupTransferTitles(source, destinationKeySets)
    val (already, toCopy) = titles.partition { title -> title.keys.any { it in destinationKeys } }
    return LibraryTransferPlan(
        sourceTitles = titles.size + unmatched,
        toCopy = toCopy,
        alreadyThere = already,
        unmatched = unmatched
    )
}

/** The candidates [destination] lists now (a Move removes only these). */
internal fun confirmedAt(candidates: List<TransferTitle>, destination: List<TrackingLibraryEntry>): List<TransferTitle> {
    val destinationKeys = destination.flatMapTo(HashSet(), ::transferKeys)
    return candidates.filter { title -> title.keys.any { it in destinationKeys } }
}

/** The write for [entry], keeping its display fields. */
internal fun LibraryEntry.toTransferInput(): LibraryEntryInput = LibraryEntryInput(
    itemId = id,
    itemType = type,
    title = name,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl
)

/**
 * The write for [this] title: its first entry's display fields under its `tt` id when it has one, else its `tmdb:N`
 * (addons resolve both; `tt` first, design red team 3f6c2106), typed movie or series.
 */
internal fun TransferTitle.writeInput(): LibraryEntryInput {
    val ids = keys.map { it.substringAfter('|') }
    val id = ids.firstOrNull { it.startsWith("tt") } ?: ids.first()
    return first.entry.toTransferInput().copy(itemId = id, itemType = transferType(first.entry.type))
}
