package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.LibraryEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 105 S5b1: the active profile's MDBList Home rows, what they read, and their pages. */
class MdbListHomeRowsTest {

    private val engine = FakeMdbListEngine().apply { watchlistDefault = null }
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val session = MdbListSession(mdbListClient(engine, clock), accounts, MdbListQuota { clock.now })
    private val lists = MdbListHomeLists(session) { clock.now }
    private val watchlist = MutableStateFlow<List<LibraryEntry>>(emptyList())
    private val library = mockk<MdbListTrackingLibraryProvider> { every { items } returns watchlist }
    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(1)
        every { activeProfileReady } returns MutableStateFlow(true)
    }
    private var cap: ContentRatingCap? = null
    private val ratingCap = mockk<ActiveRatingCap> { coEvery { current() } answers { cap } }
    private val admitted = mutableListOf<List<String>>()
    private var hidden = emptySet<String>()
    private val layout = mockk<LayoutPreferenceDataStore> {
        coEvery { admitMdbListKeys(any(), any()) } answers { admitted += secondArg<List<String>>(); hidden }
    }

    private fun TestScope.homeRows() =
        MdbListHomeRows(lists, library, accounts, profileManager, ratingCap, layout, this) { "Watchlist" }

    private suspend fun connected(revoked: Boolean = false) = accounts.save(
        1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789", accountName = "arshadk", revoked = revoked)
    )

    private fun index() {
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, OWN)
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, LIKED)
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, EXTERNAL)
    }

    private fun itemCalls() = engine.calls.map { it.path }.filter { it.endsWith("/items") }

    private suspend fun MdbListHomeRows.rowsWhen(predicate: (MdbListHomeRowsState) -> Boolean) = rows().first(predicate)

    @Test
    fun `a refresh admits the watchlist and the index, then reads only the shown lists' first pages`() = runTest {
        connected()
        index()
        hidden = setOf("mdblist_12", "mdblist_21")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/11/items", 200, PAGE)
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()

        assertEquals(listOf(listOf("mdblist_watchlist", "mdblist_11", "mdblist_12", "mdblist_21", "mdblist_ext_11")), admitted)
        assertEquals(listOf("/lists/11/items", "/external/lists/11/items"), itemCalls())
        val state = rows.rowsWhen { it.rows.size == 3 }
        assertEquals(listOf("mdblist_watchlist", "mdblist_11", "mdblist_12", "mdblist_21", "mdblist_ext_11"), state.keys)
        val weekend = state.rows.getValue("mdblist_11")
        assertEquals("Weekend", weekend.catalogName)
        assertEquals(listOf("tt0133093", "tt0903747"), weekend.items.map { it.id })
        assertEquals(listOf("movie", "series"), weekend.items.map { it.apiType })
        assertEquals("https://image.tmdb.org/t/p/w500/m.jpg", weekend.items[0].poster)
        assertTrue(weekend.hasMore)
        assertTrue(weekend.supportsSkip) // Home observes load-more only on such rows
        assertEquals(1, weekend.currentPage)
        assertTrue(weekend.isMdbListHomeRow())
        assertEquals("MDBList", weekend.addonName)
    }

    @Test
    fun `the watchlist row is the library view, with nothing sent for it`() = runTest {
        connected()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/user", 200, "[]")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/liked", 200, """{"lists":[]}""")
        engine.replyTo(MdbListHttpMethod.GET, "/external/lists/user", 200, "[]")
        watchlist.value = listOf(entry("tt1", "movie"), entry("tt2", "series"))
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()
        val row = rows.rowsWhen { it.rows.isNotEmpty() }.rows.getValue("mdblist_watchlist")
        assertEquals("Watchlist", row.catalogName)
        assertEquals(listOf("tt1", "tt2"), row.items.map { it.id })
        assertFalse(row.hasMore)
        assertEquals(3, engine.calls.size) // the index only
    }

    @Test
    fun `a capped profile shows no row and sends nothing`() = runTest {
        connected()
        cap = ContentRatingCap.STRICTEST
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()
        assertEquals(MdbListHomeRowsState(), rows.rows().first())
        assertTrue(engine.calls.isEmpty())
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun `a revoked or removed key hides every row at once`() = runTest {
        connected()
        index()
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        hidden = setOf("mdblist_12", "mdblist_21", "mdblist_ext_11")
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()
        rows.rowsWhen { it.rows.size == 2 }
        connected(revoked = true)
        assertEquals(MdbListHomeRowsState(), rows.rowsWhen { it.rows.isEmpty() })
    }

    @Test
    fun `the index names every list, hidden ones too, and goes with the key or on a capped profile`() = runTest {
        connected()
        index()
        hidden = setOf("mdblist_12", "mdblist_21", "mdblist_ext_11")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        val rows = homeRows()
        assertEquals(null, rows.index().first())
        rows.refresh()
        advanceUntilIdle()
        val read = rows.index().first { it != null }!!
        assertEquals("Watchlist", read.watchlistTitle)
        assertEquals(listOf("mdblist_11", "mdblist_12", "mdblist_21", "mdblist_ext_11"), read.lists.map { it.key })
        val calls = engine.calls.size
        rows.index().first()
        assertEquals(calls, engine.calls.size) // read from what refresh kept
        connected(revoked = true)
        assertEquals(null, rows.index().first())

        cap = ContentRatingCap.STRICTEST
        connected()
        rows.refresh()
        advanceUntilIdle()
        assertEquals(null, rows.index().first())
    }

    @Test
    fun `a next page appends only new titles, a failure leaves the row to retry, and a refresh keeps the pages`() = runTest {
        connected()
        index()
        hidden = setOf("mdblist_12", "mdblist_21", "mdblist_ext_11")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE)
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()

        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 404, "{}")
        rows.loadMore("mdblist_11")
        advanceUntilIdle()
        val failed = rows.rowsWhen { it.rows["mdblist_11"]?.isLoading == false }.rows.getValue("mdblist_11")
        assertEquals(2, failed.items.size)
        assertTrue(failed.hasMore)

        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, NEXT_PAGE)
        rows.loadMore("mdblist_11")
        advanceUntilIdle()
        val paged = rows.rowsWhen { it.rows["mdblist_11"]?.currentPage == 2 }.rows.getValue("mdblist_11")
        assertEquals(listOf("tt0133093", "tt0903747", "tt0234215"), paged.items.map { it.id })
        assertFalse(paged.hasMore)
        assertEquals("c2", engine.calls.last().query("cursor"))

        val sent = engine.calls.size
        rows.refresh() // inside every floor: nothing sent, the loaded pages stay
        advanceUntilIdle()
        assertEquals(sent, engine.calls.size)
        assertEquals(3, rows.rows().first().rows.getValue("mdblist_11").items.size)
    }

    @Test
    fun `a row that ends or is not shown never pages`() = runTest {
        connected()
        index()
        hidden = setOf("mdblist_12", "mdblist_21", "mdblist_ext_11")
        engine.replyTo(MdbListHttpMethod.GET, "/lists/11/items", 200, PAGE.replace("\"c2\"", "null"))
        val rows = homeRows()
        rows.refresh()
        advanceUntilIdle()
        val sent = engine.calls.size
        rows.loadMore("mdblist_11")
        rows.loadMore("mdblist_12")
        rows.loadMore("mdblist_watchlist")
        advanceUntilIdle()
        assertEquals(sent, engine.calls.size)
    }

    @Test
    fun `only MDBList's own rows count as MDBList rows`() {
        assertTrue(isMdbListHomeRow("mdblist", "", "mdblist_ext_4"))
        assertFalse(isMdbListHomeRow("mdblist", "https://mdblist.example", "mdblist_4"))
        assertFalse(isMdbListHomeRow("mdblist", "", "top"))
        assertFalse(isMdbListHomeRow("other", "", "mdblist_4"))
    }

    private fun entry(id: String, type: String) = LibraryEntry(
        id = id, type = type, name = id, poster = null, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
    )

    private companion object {
        const val OWN = """[{"id":11,"name":"Weekend","items":3},{"id":12,"name":"Anime","items":80}]"""
        const val LIKED = """{"lists":[{"id":21,"name":"Top 250","items":250}],"pagination":{"has_more":false}}"""
        const val EXTERNAL = """[{"id":11,"name":"Letterboxd 2024","items":40}]"""
        const val PAGE = """{"movies":[{"id":604,"rank":1,"title":"The Matrix","imdb_id":"tt0133093","poster":"/m.jpg"}],
          "shows":[{"id":1396,"rank":2,"title":"Breaking Bad","imdb_id":"tt0903747"}],
          "pagination":{"next_cursor":"c2"}}"""
        const val NEXT_PAGE = """{"movies":[{"id":604,"rank":1,"title":"The Matrix","imdb_id":"tt0133093"},
          {"id":605,"rank":3,"title":"The Matrix Reloaded","imdb_id":"tt0234215"}]}"""
    }
}
