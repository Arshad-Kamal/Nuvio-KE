package com.nuvio.tv.ui.screens.library

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.screens.home.isWatchedTitle
import java.util.Locale

// Library shelves (approved sandbox `u7-library-marquee` Q1, Spec 27): the rows each type tab
// shows, built from the library alone. Pure, so the rules are unit-tested here.

/** A type tab: [key] = the lower-case library type, or [ALL_KEY]; [count] = its titles. */
@Immutable
data class LibraryTypeTab(val key: String, val count: Int) {
    companion object {
        const val ALL_KEY = "__all__"
        /** The History tab (stage 7): after the type tabs, always shown, no count. */
        const val HISTORY_KEY = "__history__"
    }
}

enum class LibraryShelfKind { RECENTLY_ADDED, NOT_WATCHED, MOVIES, SERIES, GENRE, WATCHED }

/** One shelf: [key] is unique per tab and shelf (row focus and scroll state key on it). */
@Immutable
data class LibraryShelf(
    val key: String,
    val kind: LibraryShelfKind,
    /** The genre as the library spells it (first occurrence), for [LibraryShelfKind.GENRE]. */
    val genre: String?,
    val entries: List<LibraryEntry>
)

/** Genre shelves need this many titles; at most [MAX_GENRE_SHELVES] of them, biggest first. */
internal const val MIN_GENRE_SHELF_SIZE = 3
internal const val MAX_GENRE_SHELVES = 6

private val TYPE_TAB_ORDER = listOf("movie", "series", "tv", "show", "anime")

internal fun LibraryEntry.typeKey(): String = type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)

/** Watched as the cards' tick reads it ([isWatchedTitle]). */
internal fun LibraryEntry.isWatchedIn(watchedMovieIds: Set<String>, watchedSeriesIds: Set<String>): Boolean =
    isWatchedTitle(id, type, watchedMovieIds, watchedSeriesIds)

/** The as-built tie-break of both "Added" orders: title, then id. */
private val LibraryTitleThenId: Comparator<LibraryEntry> =
    compareBy(String.CASE_INSENSITIVE_ORDER) { it: LibraryEntry -> it.name.ifBlank { it.id } }.thenBy { it.id }

/** Newest added first (Added ↓, the shelves' order). */
internal val LibraryAddedNewestFirst: Comparator<LibraryEntry> =
    compareByDescending<LibraryEntry> { it.listedAt }.then(LibraryTitleThenId)

/** Oldest added first (Added ↑). */
internal val LibraryAddedOldestFirst: Comparator<LibraryEntry> =
    compareBy<LibraryEntry> { it.listedAt }.then(LibraryTitleThenId)

// Strip leading English articles ("The", "A", "An") when sorting by title, so
// "The Walking Dead" sorts under W and not T. Matches how streaming services
// and most media libraries order titles alphabetically.
private val LEADING_ARTICLE_REGEX = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

private fun titleSortKey(title: String): String =
    title.trim().replace(LEADING_ARTICLE_REGEX, "").lowercase(Locale.ROOT)

private val LibraryTitleAscending: Comparator<LibraryEntry> =
    compareBy<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }.thenBy { it.id }

private val LibraryTitleDescending: Comparator<LibraryEntry> =
    compareByDescending<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }.thenBy { it.id }

/**
 * [entries] in [sort] order (the Sort & filter grid). Recently watched (U1): by [lastWatchedById]
 * (title id -> last watched, epoch ms), newest first; titles never watched follow, newest added first.
 */
internal fun sortLibraryEntries(
    entries: List<LibraryEntry>,
    sort: LibrarySortOption,
    lastWatchedById: Map<String, Long>
): List<LibraryEntry> = entries.sortedWith(
    when (sort) {
        LibrarySortOption.ADDED_DESC -> LibraryAddedNewestFirst
        LibrarySortOption.ADDED_ASC -> LibraryAddedOldestFirst
        LibrarySortOption.TITLE_ASC -> LibraryTitleAscending
        LibrarySortOption.TITLE_DESC -> LibraryTitleDescending
        LibrarySortOption.RECENTLY_WATCHED ->
            compareByDescending<LibraryEntry> { lastWatchedById[it.id] ?: 0L }.then(LibraryAddedNewestFirst)
    }
)

/** One genre shelf in the making: the first spelling met, and its titles. */
private class GenreBucket(val label: String) {
    val entries = mutableListOf<LibraryEntry>()
}

/**
 * All, then one tab per library type (movies, series, then the rest alphabetically) with counts,
 * then History (no count; there even for an empty library, stage 9).
 */
internal fun libraryTypeTabs(entries: List<LibraryEntry>): List<LibraryTypeTab> {
    val counts = entries.groupingBy { it.typeKey() }.eachCount()
    val keys = counts.keys.sortedWith(
        compareBy<String> { key -> TYPE_TAB_ORDER.indexOf(key).takeIf { it >= 0 } ?: TYPE_TAB_ORDER.size }
            .thenBy { it }
    )
    return listOf(LibraryTypeTab(LibraryTypeTab.ALL_KEY, entries.size)) +
        keys.map { LibraryTypeTab(it, counts.getValue(it)) } +
        LibraryTypeTab(LibraryTypeTab.HISTORY_KEY, count = 0)
}

/** [requestedKey] when the library has that tab, else All (a persisted type the library no longer holds). */
internal fun activeTypeKey(requestedKey: String?, tabs: List<LibraryTypeTab>): String =
    requestedKey?.takeIf { key -> tabs.any { it.key == key } } ?: LibraryTypeTab.ALL_KEY

internal fun entriesOfType(entries: List<LibraryEntry>, typeKey: String): List<LibraryEntry> =
    if (typeKey == LibraryTypeTab.ALL_KEY) entries else entries.filter { it.typeKey() == typeKey }

/**
 * The shelves of tab [typeKey] (Q1): Recently added (added since [recentSinceMs]) · Not watched
 * yet · Movies · Series · one per genre with [MIN_GENRE_SHELF_SIZE]+ titles (most titles first,
 * up to [MAX_GENRE_SHELVES]) · Watched. Type tabs drop the Movies and Series shelves; empty
 * shelves are left out. Every shelf lists newest added first.
 */
internal fun libraryShelves(
    entries: List<LibraryEntry>,
    typeKey: String,
    isWatched: (LibraryEntry) -> Boolean,
    recentSinceMs: Long
): List<LibraryShelf> {
    val tabEntries = entriesOfType(entries, typeKey).sortedWith(LibraryAddedNewestFirst)
    val (watched, notWatched) = tabEntries.partition(isWatched)
    val isAll = typeKey == LibraryTypeTab.ALL_KEY

    // Genres match case-insensitively; a shelf keeps the first spelling it met.
    val byGenre = linkedMapOf<String, GenreBucket>()
    tabEntries.forEach { entry ->
        entry.genres.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .forEach { genre -> byGenre.getOrPut(genre.lowercase(Locale.ROOT)) { GenreBucket(genre) }.entries += entry }
    }
    val genreShelves = byGenre.entries
        .filter { (_, bucket) -> bucket.entries.size >= MIN_GENRE_SHELF_SIZE }
        .sortedWith(compareByDescending<Map.Entry<String, GenreBucket>> { it.value.entries.size }.thenBy { it.key })
        .take(MAX_GENRE_SHELVES)
        .map { (genreKey, bucket) ->
            LibraryShelf("$typeKey:genre:$genreKey", LibraryShelfKind.GENRE, bucket.label, bucket.entries.toList())
        }

    fun shelf(kind: LibraryShelfKind, items: List<LibraryEntry>) =
        LibraryShelf("$typeKey:${kind.name.lowercase(Locale.ROOT)}", kind, null, items)

    return buildList {
        add(shelf(LibraryShelfKind.RECENTLY_ADDED, tabEntries.filter { it.listedAt >= recentSinceMs && it.listedAt > 0L }))
        add(shelf(LibraryShelfKind.NOT_WATCHED, notWatched))
        if (isAll) {
            add(shelf(LibraryShelfKind.MOVIES, tabEntries.filter { it.typeKey() == "movie" }))
            add(shelf(LibraryShelfKind.SERIES, tabEntries.filter { isSeriesType(it.typeKey()) }))
        }
        addAll(genreShelves)
        add(shelf(LibraryShelfKind.WATCHED, watched))
    }.filter { it.entries.isNotEmpty() }
}
