package com.nuvio.tv.ui.screens.library

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.ui.screens.home.parseNextUpDismissKey
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Library's History tab (approved sandbox `u7-library-marquee` stage 7, Q5): one entry per title
// from the watch progress and the watched set, newest first, grouped Today / This week / by month.
// Pure, so the rules are unit-tested here.

/** One watched title: the newest of its progress and watched marks. */
@Immutable
data class LibraryHistoryEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    /** The latest episode watched (series); null for movies and series-level marks. */
    val season: Int?,
    val episode: Int?,
    /** Last watched, epoch ms. */
    val watchedAt: Long,
    /** Played part (0..1) while the latest play is unfinished (under 90%); null once finished or marked watched. */
    val progressFraction: Float?
)

enum class LibraryHistoryGroupKind { TODAY, THIS_WEEK, MONTH }

/** One History row: [key] is unique across the tab (row focus and scroll state key on it). */
@Immutable
data class LibraryHistoryGroup(
    val key: String,
    val kind: LibraryHistoryGroupKind,
    /** The month of a [LibraryHistoryGroupKind.MONTH] group. */
    val month: YearMonth?,
    val entries: List<LibraryHistoryEntry>
)

// The maxOf orders picking a title's latest mark and latest progress entry: time, then season,
// then episode (a batch mark shares one time, so the higher episode wins, as allProgress does).
private val WatchedItemOrder: Comparator<WatchedItem> =
    compareBy<WatchedItem> { it.watchedAt }.thenBy { it.season ?: -1 }.thenBy { it.episode ?: -1 }

private val ProgressOrder: Comparator<WatchProgress> =
    compareBy<WatchProgress> { it.lastWatched }.thenBy { it.season ?: -1 }.thenBy { it.episode ?: -1 }

/**
 * The History list: per title the newer of its latest progress entry and its latest watched mark
 * (equal times: the progress entry, which carries the art; markAsCompleted stamps both), newest first.
 * A title known only from the watched set (a manual mark, a sync, a Continue Watching removal)
 * still lists, with the watched title and poster.
 */
internal fun libraryHistory(progress: List<WatchProgress>, watched: List<WatchedItem>): List<LibraryHistoryEntry> {
    val latestProgress = HashMap<String, WatchProgress>(progress.size)
    progress.forEach { p -> latestProgress.merge(p.contentId, p) { a, b -> maxOf(a, b, ProgressOrder) } }
    val latestWatched = HashMap<String, WatchedItem>()
    watched.forEach { w -> latestWatched.merge(w.contentId, w) { a, b -> maxOf(a, b, WatchedItemOrder) } }
    val ids = LinkedHashSet<String>(latestProgress.keys).apply { addAll(latestWatched.keys) }
    return ids.map { id ->
        val p = latestProgress[id]
        val w = latestWatched[id]
        // The progress entry when it is the newer of the two.
        val latestPlay = p?.takeIf { w == null || it.lastWatched >= w.watchedAt }
        LibraryHistoryEntry(
            id = id,
            type = p?.contentType ?: w?.contentType.orEmpty(),
            name = p?.name?.takeIf { it.isNotBlank() } ?: w?.title.orEmpty(),
            poster = p?.poster ?: w?.poster,
            backdrop = p?.backdrop,
            logo = p?.logo,
            season = if (latestPlay != null) latestPlay.season else w?.season,
            episode = if (latestPlay != null) latestPlay.episode else w?.episode,
            watchedAt = maxOf(p?.lastWatched ?: 0L, w?.watchedAt ?: 0L),
            // Unfinished includes a play stopped before the 2% "started" mark (never "Watched").
            progressFraction = latestPlay?.takeUnless { it.isCompleted() }?.progressPercentage
        )
    }.sortedWith(compareByDescending<LibraryHistoryEntry> { it.watchedAt }.thenBy { it.id })
}

/**
 * [history] (newest first) as rows: Today (since local midnight; a time ahead of [now] counts as
 * today), This week (the six days before), then one row per month. Empty rows are left out.
 */
internal fun libraryHistoryGroups(history: List<LibraryHistoryEntry>, now: ZonedDateTime): List<LibraryHistoryGroup> {
    val zone: ZoneId = now.zone
    val today = now.toLocalDate()
    val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val weekStartMs = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEntries = mutableListOf<LibraryHistoryEntry>()
    val weekEntries = mutableListOf<LibraryHistoryEntry>()
    val byMonth = linkedMapOf<YearMonth, MutableList<LibraryHistoryEntry>>()
    history.forEach { entry ->
        when {
            entry.watchedAt >= todayStartMs -> todayEntries += entry
            entry.watchedAt >= weekStartMs -> weekEntries += entry
            else -> byMonth.getOrPut(YearMonth.from(Instant.ofEpochMilli(entry.watchedAt).atZone(zone))) { mutableListOf() } += entry
        }
    }
    return buildList {
        add(LibraryHistoryGroup(HISTORY_TODAY_KEY, LibraryHistoryGroupKind.TODAY, null, todayEntries))
        add(LibraryHistoryGroup(HISTORY_WEEK_KEY, LibraryHistoryGroupKind.THIS_WEEK, null, weekEntries))
        byMonth.forEach { (month, entries) ->
            add(LibraryHistoryGroup("history:$month", LibraryHistoryGroupKind.MONTH, month, entries))
        }
    }.filter { it.entries.isNotEmpty() }
}

/** When a title was last watched, as the hero eyebrow says it (stages 1, 3, 7: "3 days ago", "in August"). */
sealed interface LibraryWatchedAgo {
    data object Today : LibraryWatchedAgo
    data object Yesterday : LibraryWatchedAgo
    data class Days(val days: Int) : LibraryWatchedAgo
    data class InMonth(val month: YearMonth, val withYear: Boolean) : LibraryWatchedAgo
}

/**
 * [watchedAt] against [today] in calendar days: today (a time ahead of today too), yesterday, "N days
 * ago" under [WATCHED_DAYS_AGO_LIMIT], then its month (with the year when not this year).
 */
internal fun libraryWatchedAgo(watchedAt: Long, today: LocalDate, zone: ZoneId): LibraryWatchedAgo {
    val date = Instant.ofEpochMilli(watchedAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> LibraryWatchedAgo.Today
        days == 1L -> LibraryWatchedAgo.Yesterday
        days < WATCHED_DAYS_AGO_LIMIT -> LibraryWatchedAgo.Days(days.toInt())
        else -> LibraryWatchedAgo.InMonth(YearMonth.from(date), withYear = date.year != today.year)
    }
}

private const val WATCHED_DAYS_AGO_LIMIT = 30L

private const val HISTORY_TODAY_KEY = "history:today"
private const val HISTORY_WEEK_KEY = "history:week"

/** History's last row (stage 8): the Next Up cards removed from Continue Watching. */
internal const val HISTORY_HIDDEN_KEY = "history:hidden"

/**
 * One title's dismissed Next Up cards (stage 8, Q6): [keys] as the dismissal set spells them
 * (Restore removes them all), [entry] its History entry (title and art), [seedSeason]/[seedEpisode]
 * the highest seed episode the cards were built from (the key stores the seed, not the next episode).
 */
@Immutable
data class LibraryHiddenNextUp(
    val keys: List<String>,
    val entry: LibraryHistoryEntry,
    val seedSeason: Int?,
    val seedEpisode: Int?
)

/**
 * The Hidden group from the dismissed Next Up [keys]: one entry per title found in [history] (the
 * store holds no title or art; a title gone from the history is left out), ordered by the title's
 * last watched time, newest first (dismissals carry no date).
 */
internal fun libraryHiddenNextUp(keys: Set<String>, history: List<LibraryHistoryEntry>): List<LibraryHiddenNextUp> {
    if (keys.isEmpty()) return emptyList()
    data class Seed(val id: String, val key: String, val season: Int?, val episode: Int?)
    val seedsById = keys.map { key ->
        val (id, season, episode) = parseNextUpDismissKey(key)
        Seed(id, key, season, episode)
    }.groupBy { it.id }
    // history is newest first, so this keeps that order.
    return history.mapNotNull { entry ->
        val seeds = seedsById[entry.id] ?: return@mapNotNull null
        val latest = seeds.maxWith(compareBy<Seed> { it.season ?: -1 }.thenBy { it.episode ?: -1 })
        LibraryHiddenNextUp(seeds.map { it.key }.sorted(), entry, latest.season, latest.episode)
    }
}
