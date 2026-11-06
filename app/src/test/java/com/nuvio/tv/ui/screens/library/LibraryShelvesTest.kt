package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Library shelves and type tabs (Spec 27 Q1, sandbox u7-library-marquee stages 1, 3, 9). */
class LibraryShelvesTest {

    private val monthStart = 1_000L

    private fun entry(
        id: String,
        type: String = "movie",
        addedAt: Long = 0L,
        genres: List<String> = emptyList(),
        name: String = id
    ) = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = genres,
        addonBaseUrl = null,
        listedAt = addedAt
    )

    private fun shelves(
        entries: List<LibraryEntry>,
        typeKey: String = LibraryTypeTab.ALL_KEY,
        watched: Set<String> = emptySet()
    ) = libraryShelves(entries, typeKey, { it.id in watched }, monthStart)

    @Test
    fun `All lists the shelves in Q1 order, empty ones left out`() {
        val entries = listOf(
            entry("m1", addedAt = 2_000L, genres = listOf("Drama")),
            entry("m2", addedAt = 500L, genres = listOf("Drama")),
            entry("s1", type = "series", addedAt = 400L, genres = listOf("Drama"))
        )
        val result = shelves(entries, watched = setOf("m2"))
        assertEquals(
            listOf(
                LibraryShelfKind.RECENTLY_ADDED,
                LibraryShelfKind.NOT_WATCHED,
                LibraryShelfKind.MOVIES,
                LibraryShelfKind.SERIES,
                LibraryShelfKind.GENRE,
                LibraryShelfKind.WATCHED
            ),
            result.map { it.kind }
        )
        assertEquals(listOf("m1"), result[0].entries.map { it.id })
        assertEquals(listOf("m1", "s1"), result[1].entries.map { it.id })
        assertEquals(listOf("m2"), result.last().entries.map { it.id })
    }

    @Test
    fun `a type tab drops the type shelves and keeps only its titles`() {
        val entries = listOf(entry("m1"), entry("s1", type = "series"), entry("s2", type = "Series "))
        val result = shelves(entries, typeKey = "series")
        assertEquals(listOf(LibraryShelfKind.NOT_WATCHED), result.map { it.kind })
        assertEquals(setOf("s1", "s2"), result.single().entries.map { it.id }.toSet())
    }

    @Test
    fun `tv titles land on the Series shelf`() {
        val result = shelves(listOf(entry("t1", type = "tv")))
        assertEquals(listOf("t1"), result.first { it.kind == LibraryShelfKind.SERIES }.entries.map { it.id })
    }

    @Test
    fun `shelves list newest added first, ties by title`() {
        val entries = listOf(
            entry("a", addedAt = 10L, name = "Beta"),
            entry("b", addedAt = 30L),
            entry("c", addedAt = 10L, name = "Alpha")
        )
        assertEquals(listOf("b", "c", "a"), shelves(entries).first { it.kind == LibraryShelfKind.NOT_WATCHED }.entries.map { it.id })
    }

    @Test
    fun `Recently added needs a known add date in this month`() {
        val entries = listOf(entry("old", addedAt = monthStart - 1), entry("new", addedAt = monthStart), entry("unknown"))
        assertEquals(listOf("new"), shelves(entries).first { it.kind == LibraryShelfKind.RECENTLY_ADDED }.entries.map { it.id })
    }

    @Test
    fun `genre shelves need 3 titles, biggest first, at most 6, case-insensitive`() {
        val genres = listOf("Action", "Comedy", "Drama", "Horror", "Mystery", "Sci-Fi", "Thriller")
        val entries = (0 until 4).map { entry("x$it", genres = genres) } +
            entry("y", genres = listOf("drama")) +
            entry("z", genres = listOf("Western", "Western"))
        val genreShelves = shelves(entries).filter { it.kind == LibraryShelfKind.GENRE }
        assertEquals(MAX_GENRE_SHELVES, genreShelves.size)
        // Drama has 5 titles (one spelled lower-case); the rest tie at 4 and order by name.
        assertEquals("Drama", genreShelves.first().genre)
        assertEquals(5, genreShelves.first().entries.size)
        assertEquals(listOf("Action", "Comedy", "Horror", "Mystery", "Sci-Fi"), genreShelves.drop(1).map { it.genre })
        assertTrue(genreShelves.none { it.genre == "Western" })
    }

    @Test
    fun `shelf keys are unique per tab`() {
        val entries = (0 until 3).map { entry("m$it", addedAt = 2_000L, genres = listOf("Drama")) }
        val all = shelves(entries).map { it.key }
        val movies = shelves(entries, typeKey = "movie").map { it.key }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.intersect(movies.toSet()).isEmpty())
    }

    @Test
    fun `an empty library has no shelves`() {
        assertTrue(shelves(emptyList()).isEmpty())
    }

    @Test
    fun `type tabs come from the library, All first, movies and series before the rest`() {
        val entries = listOf(
            entry("a", type = "anime"),
            entry("c", type = "channel"),
            entry("s", type = "series"),
            entry("m1"),
            entry("m2", type = "Movie")
        )
        assertEquals(
            listOf(
                LibraryTypeTab(LibraryTypeTab.ALL_KEY, 5),
                LibraryTypeTab("movie", 2),
                LibraryTypeTab("series", 1),
                LibraryTypeTab("anime", 1),
                LibraryTypeTab("channel", 1),
                LibraryTypeTab(LibraryTypeTab.HISTORY_KEY, 0)
            ),
            libraryTypeTabs(entries)
        )
        assertEquals(
            listOf(LibraryTypeTab(LibraryTypeTab.ALL_KEY, 0), LibraryTypeTab(LibraryTypeTab.HISTORY_KEY, 0)),
            libraryTypeTabs(emptyList())
        )
    }

    @Test
    fun `a persisted tab the library no longer holds falls back to All`() {
        val tabs = libraryTypeTabs(listOf(entry("m1")))
        assertEquals("movie", activeTypeKey("movie", tabs))
        assertEquals(LibraryTypeTab.ALL_KEY, activeTypeKey("series", tabs))
        assertEquals(LibraryTypeTab.ALL_KEY, activeTypeKey(null, tabs))
    }

    @Test
    fun `watched reads series titles from the series set`() {
        val movie = entry("1")
        val series = entry("1", type = "series")
        assertTrue(movie.isWatchedIn(setOf("1"), emptySet()))
        assertTrue(series.isWatchedIn(emptySet(), setOf("1")))
        assertEquals(false, series.isWatchedIn(setOf("1"), emptySet()))
        assertNull(shelves(listOf(series), watched = emptySet()).firstOrNull { it.kind == LibraryShelfKind.WATCHED })
    }

    @Test
    fun `Recently watched lists last watched first, never watched after them by newest added`() {
        val entries = listOf(
            entry("old", addedAt = 100L),
            entry("new", addedAt = 900L),
            entry("seen1", addedAt = 50L),
            entry("seen2", addedAt = 60L)
        )
        val sorted = sortLibraryEntries(entries, LibrarySortOption.RECENTLY_WATCHED, mapOf("seen1" to 5_000L, "seen2" to 4_000L))
        assertEquals(listOf("seen1", "seen2", "new", "old"), sorted.map { it.id })
    }

    @Test
    fun `title sorts skip a leading article, both ways`() {
        val entries = listOf(entry("1", name = "The Zoo"), entry("2", name = "Apple"), entry("3", name = "A Bear"))
        assertEquals(listOf("2", "3", "1"), sortLibraryEntries(entries, LibrarySortOption.TITLE_ASC, emptyMap()).map { it.id })
        assertEquals(listOf("1", "3", "2"), sortLibraryEntries(entries, LibrarySortOption.TITLE_DESC, emptyMap()).map { it.id })
    }

    @Test
    fun `added sorts order by add date both ways`() {
        val entries = listOf(entry("a", addedAt = 2L), entry("b", addedAt = 3L), entry("c", addedAt = 1L))
        assertEquals(listOf("b", "a", "c"), sortLibraryEntries(entries, LibrarySortOption.ADDED_DESC, emptyMap()).map { it.id })
        assertEquals(listOf("c", "a", "b"), sortLibraryEntries(entries, LibrarySortOption.ADDED_ASC, emptyMap()).map { it.id })
    }
}
