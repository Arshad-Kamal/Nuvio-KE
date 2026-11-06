package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.nuvio.tv.data.local.dismissedKeyMatcher
import com.nuvio.tv.ui.screens.home.nextUpDismissKey
import com.nuvio.tv.ui.screens.home.parseNextUpDismissKey
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Library's History tab (Spec 27c, sandbox u7-library-marquee stage 7, Q5). */
class LibraryHistoryTest {

    private fun progress(
        id: String,
        at: Long,
        position: Long = 100L,
        season: Int? = null,
        episode: Int? = null,
        type: String = "movie"
    ) = WatchProgress(
        contentId = id,
        contentType = type,
        name = "P $id",
        poster = "poster-$id",
        backdrop = "backdrop-$id",
        logo = null,
        videoId = id,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 100L,
        lastWatched = at
    )

    private fun watched(id: String, at: Long, season: Int? = null, episode: Int? = null, type: String = "series") =
        WatchedItem(contentId = id, contentType = type, title = "W $id", season = season, episode = episode, watchedAt = at)

    @Test
    fun `one entry per title, newest first, watched-only titles included`() {
        val result = libraryHistory(
            progress = listOf(progress("a", at = 300L), progress("b", at = 100L)),
            watched = listOf(watched("c", at = 200L), watched("b", at = 50L))
        )
        assertEquals(listOf("a", "c", "b"), result.map { it.id })
        assertEquals("W c", result[1].name)
        assertEquals(100L, result[2].watchedAt)
    }

    @Test
    fun `a newer watched mark names its episode and drops the bar`() {
        val result = libraryHistory(
            progress = listOf(progress("s", at = 100L, position = 40L, season = 1, episode = 2, type = "series")),
            watched = listOf(watched("s", at = 200L, season = 1, episode = 3), watched("s", at = 150L, season = 1, episode = 1))
        ).single()
        assertEquals(1 to 3, result.season to result.episode)
        assertEquals(200L, result.watchedAt)
        assertNull(result.progressFraction)
        // Art still comes from the progress entry.
        assertEquals("backdrop-s", result.backdrop)
        assertEquals("P s", result.name)
    }

    @Test
    fun `unfinished latest play keeps its bar, a finished one does not`() {
        val result = libraryHistory(
            progress = listOf(progress("half", at = 200L, position = 35L), progress("done", at = 100L, position = 95L)),
            watched = listOf(watched("done", at = 100L, type = "movie"))
        )
        assertEquals(0.35f, result.first { it.id == "half" }.progressFraction!!, 0.001f)
        assertNull(result.first { it.id == "done" }.progressFraction)
    }

    @Test
    fun `at equal times the progress entry wins`() {
        val result = libraryHistory(
            progress = listOf(progress("s", at = 100L, position = 50L, season = 1, episode = 3, type = "series")),
            watched = listOf(watched("s", at = 100L, season = 1, episode = 2))
        ).single()
        assertEquals(1 to 3, result.season to result.episode)
        assertEquals("backdrop-s", result.backdrop)
        assertEquals(0.5f, result.progressFraction!!, 0.001f)
    }

    @Test
    fun `a play stopped before the started mark keeps its bar`() {
        val result = libraryHistory(listOf(progress("m", at = 100L, position = 1L)), emptyList()).single()
        assertEquals(0.01f, result.progressFraction!!, 0.001f)
    }

    @Test
    fun `a batch mark at one time picks the highest episode`() {
        val result = libraryHistory(
            progress = emptyList(),
            watched = listOf(watched("s", at = 100L, season = 1, episode = 4), watched("s", at = 100L, season = 2, episode = 1))
        ).single()
        assertEquals(2 to 1, result.season to result.episode)
    }

    @Test
    fun `groups are Today, This week, then months newest first`() {
        val now = ZonedDateTime.of(2026, 10, 16, 21, 0, 0, 0, ZoneOffset.UTC)
        fun at(month: Int, day: Int, hour: Int = 12) =
            ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val history = listOf(
            at(10, 16, 0), // today, just after midnight
            at(10, 10), // six days before today: this week
            at(10, 9, 23), // seven days before: the month
            at(9, 30),
            at(9, 2)
        ).mapIndexed { index, time ->
            LibraryHistoryEntry("t$index", "movie", "T", null, null, null, null, null, time, null)
        }
        val groups = libraryHistoryGroups(history, now)
        assertEquals(
            listOf(
                LibraryHistoryGroupKind.TODAY,
                LibraryHistoryGroupKind.THIS_WEEK,
                LibraryHistoryGroupKind.MONTH,
                LibraryHistoryGroupKind.MONTH
            ),
            groups.map { it.kind }
        )
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), groups.drop(2).map { it.month })
        assertEquals(listOf(listOf("t0"), listOf("t1"), listOf("t2"), listOf("t3", "t4")), groups.map { g -> g.entries.map { it.id } })
        assertEquals(groups.size, groups.map { it.key }.toSet().size)
    }

    @Test
    fun `empty groups are left out`() {
        val now = ZonedDateTime.of(2026, 10, 16, 21, 0, 0, 0, ZoneOffset.UTC)
        val old = LibraryHistoryEntry("x", "movie", "X", null, null, null, null, null, 0L, null)
        assertEquals(listOf(LibraryHistoryGroupKind.MONTH), libraryHistoryGroups(listOf(old), now).map { it.kind })
        assertEquals(emptyList<LibraryHistoryGroup>(), libraryHistoryGroups(emptyList(), now))
    }

    private fun historyEntry(id: String, at: Long) =
        LibraryHistoryEntry(id, "series", "T $id", null, null, null, null, null, at, null)

    @Test
    fun `Hidden lists one card per title in history order, highest seed, all its keys`() {
        val history = listOf(historyEntry("tmdb:7", at = 300L), historyEntry("a", at = 200L), historyEntry("m", at = 100L))
        val hidden = libraryHiddenNextUp(
            keys = setOf("m", "a:s1:e2", "tmdb:7:s2:e1", "a:s1:e4", "gone:s1:e1"),
            history = history
        )
        assertEquals(listOf("tmdb:7", "a", "m"), hidden.map { it.entry.id })
        assertEquals(2 to 1, hidden[0].seedSeason to hidden[0].seedEpisode)
        assertEquals(listOf("a:s1:e2", "a:s1:e4"), hidden[1].keys)
        assertEquals(1 to 4, hidden[1].seedSeason to hidden[1].seedEpisode)
        assertNull(hidden[2].seedSeason)
        assertEquals(listOf("m"), hidden[2].keys)
    }

    @Test
    fun `dismiss keys read back as written`() {
        assertEquals(Triple("tmdb:7", 2, 1), parseNextUpDismissKey(nextUpDismissKey("tmdb:7", 2, 1)))
        assertEquals(Triple("tt1", null, null), parseNextUpDismissKey(nextUpDismissKey("tt1", null, null)))
    }

    @Test
    fun `restore removes exactly the named keys, legacy spelling included`() {
        val matches = dismissedKeyMatcher(listOf("tmdb:7", "a:s1:e2"))
        val stored = listOf("tmdb:7", "tmdb:7|1|2", "tmdb:7:s2:e1", "a:s1:e2", "a:s1:e3", "b")
        assertEquals(listOf("tmdb:7:s2:e1", "a:s1:e3", "b"), stored.filterNot(matches))
    }

    @Test
    fun `no dismissals, no Hidden group`() {
        assertEquals(emptyList<LibraryHiddenNextUp>(), libraryHiddenNextUp(emptySet(), listOf(historyEntry("a", 1L))))
    }

    @Test
    fun `watched ago counts calendar days, then names the month`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 16)
        fun at(date: LocalDate, hour: Int = 12) = date.atTime(hour, 0).toInstant(zone).toEpochMilli()
        assertEquals(LibraryWatchedAgo.Today, libraryWatchedAgo(at(today, 0), today, zone))
        // A clock ahead of the device counts as today.
        assertEquals(LibraryWatchedAgo.Today, libraryWatchedAgo(at(today.plusDays(1)), today, zone))
        // Late last night is yesterday, not "0 days ago".
        assertEquals(LibraryWatchedAgo.Yesterday, libraryWatchedAgo(at(today.minusDays(1), 23), today, zone))
        assertEquals(LibraryWatchedAgo.Days(3), libraryWatchedAgo(at(today.minusDays(3)), today, zone))
        assertEquals(LibraryWatchedAgo.Days(29), libraryWatchedAgo(at(today.minusDays(29)), today, zone))
        assertEquals(
            LibraryWatchedAgo.InMonth(YearMonth.of(2026, 9), withYear = false),
            libraryWatchedAgo(at(today.minusDays(30)), today, zone)
        )
        assertEquals(
            LibraryWatchedAgo.InMonth(YearMonth.of(2025, 8), withYear = true),
            libraryWatchedAgo(at(LocalDate.of(2025, 8, 3)), today, zone)
        )
    }

    @Test
    fun `History is always a valid tab`() {
        assertEquals(LibraryTypeTab.HISTORY_KEY, activeTypeKey(LibraryTypeTab.HISTORY_KEY, libraryTypeTabs(emptyList())))
    }
}
