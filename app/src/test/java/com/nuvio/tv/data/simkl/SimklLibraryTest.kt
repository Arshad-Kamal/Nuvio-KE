package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRateLimitException
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 106 S3a: Simkl as the Library source (every list, safe adds and removals, transfer reads and bulk adds). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimklLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val quota = SimklQuota { clock.now }
    private val session = SimklSession(client, SimklTokens(client, TEST_CONFIG, accounts) { clock.now }, quota)
    private val store by lazy { SimklSyncStore(folder.root, Dispatchers.Unconfined) }
    private val pending by lazy { SimklPendingMarks(folder.root, Dispatchers.Unconfined) }
    private val writer by lazy { SimklHistoryWriter(session, pending, { clock.now }, clock.sleep) }
    private val sync by lazy { SimklSync(session, store, accounts, replayPending = writer::replayPending) { clock.now } }

    private val day = 24L * 60 * 60 * 1_000

    private fun TestScope.library() = SimklTrackingLibraryProvider(
        store, sync, writer, MutableStateFlow(1), backgroundScope, Dispatchers.Unconfined, isPaused = quota::isPaused
    ) { clock.now }

    private suspend fun signedIn(profileId: Int = 1) =
        accounts.save(profileId, TrackingProviderId.SIMKL, TrackingAccount("at", "rt", clock.now + 7 * day, "arshad.k"))

    private fun ids(vararg pairs: Pair<String, Any>) = pairs.associate { (key, value) ->
        key to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
    }

    private fun show(
        simkl: Long,
        status: SimklListStatus?,
        vararg extra: Pair<String, Any>,
        added: String? = null,
        lastWatched: String? = null,
        watchedCount: Int? = null,
        rating: Double? = null
    ) = SimklEntry(
        type = SimklMediaType.SHOWS, status = status, lastWatchedAt = lastWatched,
        show = SimklMedia("Show $simkl", 2020, ids("simkl" to simkl, *extra), poster = "74/7441$simkl"),
        addedToWatchlistAt = added, watchedEpisodesCount = watchedCount, userRating = rating
    )

    private fun film(simkl: Long, status: SimklListStatus, vararg extra: Pair<String, Any>, type: SimklMediaType = SimklMediaType.MOVIES) =
        SimklEntry(
            type = type, status = status, animeType = "movie".takeIf { type == SimklMediaType.ANIME },
            movie = SimklMedia("Film $simkl", 2019, ids("simkl" to simkl, *extra))
        )

    private suspend fun seed(vararg entries: SimklEntry, profileId: Int = 1) {
        store.saveIf(profileId, store.generation(profileId), SimklSnapshot(activities = SimklActivities(all = "a"), entries = entries.toList()))
    }

    private fun input(id: String, type: String = "series", title: String = "Title") =
        LibraryEntryInput(itemId = id, itemType = type, title = title, releaseInfo = "2021")

    /** `/sync/activities` with nothing moved since the seeded snapshot: the sync each write runs first costs one call. */
    private fun nothingMoved() = engine.reply(200, """{"all":"a"}""")

    /** The write calls only (each write's sync reads first). */
    private fun writes() = engine.calls.filter { it.method == SimklHttpMethod.POST }

    private fun json(body: String?): JsonObject = Json.parseToJsonElement(checkNotNull(body)).jsonObject

    // Reads

    @Test
    fun `the library lists every Simkl list, newest added first, anime films as films`() = runTest {
        seed(
            show(1, SimklListStatus.WATCHING, "imdb" to "tt1", added = "2026-01-01T00:00:00Z"),
            show(2, SimklListStatus.COMPLETED, "tmdb" to 22, lastWatched = "2026-03-01T00:00:00Z"),
            show(3, SimklListStatus.DROPPED, "imdb" to "tt3", added = "2026-02-01T00:00:00Z"),
            film(4, SimklListStatus.PLAN_TO_WATCH, "mal" to 44, type = SimklMediaType.ANIME),
            show(5, status = null, "imdb" to "tt5"),
            show(6, SimklListStatus.ON_HOLD, "imdb" to "tt6", added = "2026-01-15T00:00:00Z")
        )
        val items = library().items.first()
        assertEquals(listOf("tmdb:22", "tt3", "tt6", "tt1", "mal:44"), items.map { it.id })
        assertEquals("movie", items.last().type)
        assertEquals("series", items.first().type)
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/74/74413_m.webp&q=90", items[1].poster)
        // Each row names its list (G1 shelves).
        assertEquals(
            listOf(
                TrackingListStatus.COMPLETED, TrackingListStatus.DROPPED, TrackingListStatus.ON_HOLD,
                TrackingListStatus.WATCHING, TrackingListStatus.PLAN_TO_WATCH
            ),
            items.map { it.trackerList }
        )
    }

    @Test
    fun `one per show folds an anime's seasons into one row, one per season lists each by Kitsu`() = runTest {
        fun season(simkl: Long, kitsu: Long, added: String, status: SimklListStatus = SimklListStatus.WATCHING) = SimklEntry(
            type = SimklMediaType.ANIME, status = status,
            show = SimklMedia("AoT $simkl", 2013, ids("simkl" to simkl, "imdb" to "tt2560140", "kitsu" to kitsu)),
            addedToWatchlistAt = added
        )
        seed(season(1, 7442, "2026-01-01T00:00:00Z"), season(2, 8671, "2026-02-01T00:00:00Z", SimklListStatus.COMPLETED), show(3, SimklListStatus.WATCHING, "imdb" to "tt3", "kitsu" to 9))
        val mode = MutableStateFlow(SimklAnimeSeasons.PER_SHOW)
        val library = SimklTrackingLibraryProvider(
            store, sync, writer, MutableStateFlow(1), backgroundScope, Dispatchers.Unconfined, isPaused = quota::isPaused,
            animeSeasons = { mode }
        ) { clock.now }
        assertEquals(setOf("tt2560140", "tt3"), library.items.first().map { it.id }.toSet())
        // The folded row keeps its newest season's list.
        assertEquals(TrackingListStatus.COMPLETED, library.items.first().first { it.id == "tt2560140" }.trackerList)
        mode.value = SimklAnimeSeasons.PER_SEASON
        runCurrent()
        // Shows stay as they are; the anime's seasons become two titles.
        assertEquals(setOf("kitsu:8671", "kitsu:7442", "tt3"), library.items.first().map { it.id }.toSet())
        assertTrue(library.isInLibrary("kitsu:7442", "series").first())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a title is in the library on any list, matched by any id and never across film and show`() = runTest {
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1", "tmdb" to 7), film(2, SimklListStatus.DROPPED, "tmdb" to 8))
        val library = library()
        assertTrue(library.isInLibrary("tmdb:7", "series").first())
        assertTrue(library.isInLibrary("tt1", "series").first())
        assertFalse(library.isInLibrary("tmdb:7", "movie").first())
        assertTrue(library.isInLibrary("tmdb:8", "movie").first())
        assertFalse(library.isInLibrary("tmdb:8", "series").first())
    }

    @Test
    fun `poster paths that are not plain image paths give no poster`() {
        assertNull(simklPosterUrl(null))
        assertNull(simklPosterUrl("../x"))
        assertNull(simklPosterUrl("74/a&url=https://evil"))
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/ab/cd_m.webp&q=90", simklPosterUrl("ab/cd"))
    }

    // Adds

    @Test
    fun `adding a title already on another list sends nothing and never moves it`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"))
        nothingMoved()
        val library = library()
        library.setInLibrary(1, input("tt1"), inLibrary = true)
        assertEquals(listOf("/sync/activities"), engine.calls.map { it.path })
        // Shown on the list Simkl has it on, not on Plan to watch.
        assertEquals(TrackingListStatus.COMPLETED, library.items.first().single().trackerList)
    }

    @Test
    fun `adding a title on no list puts it on Plan to watch and shows it at once`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, """{"added":{"shows":[{"to":"plantowatch"}]},"not_found":{"movies":[],"shows":[]}}""")
        library.setInLibrary(1, input("tmdb:9", title = "Nine"), inLibrary = true)

        val call = writes().single()
        assertEquals("/sync/add-to-list", call.path)
        val item = json(call.body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals("plantowatch", item["to"]!!.jsonPrimitive.content)
        assertEquals(9L, item["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content.toLong())
        assertEquals(2021, item["year"]!!.jsonPrimitive.content.toInt())
        assertTrue(library.isInLibrary("tmdb:9", "series").first())
        assertEquals("Nine", library.items.first().single().name)
        assertEquals(TrackingListStatus.PLAN_TO_WATCH, library.items.first().single().trackerList)
    }

    @Test
    fun `films, shows and anime go under their own keys, each with its own to`() {
        val body = json(
            addToListRequest(listOf(input("tt1", "movie"), input("tmdb:2"), input("kitsu:3"))).jsonBody
        )
        assertEquals(setOf("movies", "shows", "anime"), body.keys)
        fun idsUnder(key: String) = body[key]!!.jsonArray.single().jsonObject["ids"]!!.jsonObject
        assertEquals("tt1", idsUnder("movies")["imdb"]!!.jsonPrimitive.content)
        assertEquals("2", idsUnder("shows")["tmdb"]!!.jsonPrimitive.content)
        assertEquals("3", idsUnder("anime")["kitsu"]!!.jsonPrimitive.content)
        body.values.forEach { list -> assertEquals("plantowatch", list.jsonArray.single().jsonObject["to"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `an add Simkl cannot match fails and changes nothing`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, """{"added":{},"not_found":{"shows":[{"ids":{"tmdb":9}}]}}""")
        assertFailsSuspend<SimklUnmatchableException> { library.setInLibrary(1, input("tmdb:9"), inLibrary = true) }
        assertFalse(library.isInLibrary("tmdb:9", "series").first())
    }

    @Test
    fun `an addon's own id is never sent`() = runTest {
        signedIn()
        seed()
        assertFailsSuspend<SimklUnmatchableException> { library().setInLibrary(1, input("myaddon:42"), inLibrary = true) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a spent day fails a library write with nothing kept for later`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(429, """{"error":"user_limit_exceeded"}""")
        assertFailsSuspend<SimklApiException> { library.setInLibrary(1, input("tt1"), inLibrary = true) }
        // Paused now: the sync is skipped, so nothing is decided or sent.
        assertFailsSuspend<SimklException> { library.setInLibrary(1, input("tt1"), inLibrary = true) }
        assertEquals(2, engine.calls.size)
        assertTrue(pending.get(1).isEmpty())
        assertFalse(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `a library write waits out Simkl's write lock once`() = runTest {
        signedIn()
        seed()
        nothingMoved()
        engine.reply(400, """{"error":"rate_limit"}""")
        engine.reply(201, "{}")
        library().setInLibrary(1, input("tt1"), inLibrary = true)
        assertEquals(2, writes().size)
        assertEquals(listOf(21_000L), clock.sleeps)
    }

    // Removals

    @Test
    fun `a removal that would wipe history or a rating is refused with nothing sent`() = runTest {
        signedIn()
        seed(
            show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"),
            show(2, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt2", watchedCount = 3),
            show(3, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt3", rating = 8.0),
            show(4, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt4", lastWatched = "2026-01-01T00:00:00Z"),
            show(5, SimklListStatus.WATCHING, "imdb" to "tt5"),
            show(6, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt6").copy(
                seasons = listOf(SimklSeason(1, listOf(SimklEpisode(1, watchedAt = "2026-01-01T00:00:00Z"))))
            )
        )
        val library = library()
        listOf("tt1", "tt2", "tt3", "tt4", "tt5", "tt6").forEach { id ->
            nothingMoved()
            assertFailsSuspend<SimklRemovalNeedsConfirmException> { library.setInLibrary(1, input(id), inLibrary = false) }
        }
        assertTrue(writes().isEmpty())
        assertEquals(6, library.items.first().size)
    }

    @Test
    fun `a removal matching several Simkl entries is refused`() = runTest {
        signedIn()
        // Two anime seasons filed under the show's IMDb id, both plain Plan to watch.
        seed(show(1, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt1"), show(2, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt1"))
        nothingMoved()
        assertFailsSuspend<SimklDuplicateEntriesException> { library().setInLibrary(1, input("tt1"), inLibrary = false) }
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `a watch this TV sent since the last sync is seen before a removal`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt1"))
        // Simkl moved: the delta shows the title Completed with a watch (sent from this TV after the last sync).
        engine.reply(200, """{"all":"b","tv_shows":{"completed":"c2"}}""")
        engine.reply(200, """{"shows":[{"status":"completed","last_watched_at":"2026-11-17T00:00:00Z",
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}]}""")
        assertFailsSuspend<SimklRemovalNeedsConfirmException> { library().setInLibrary(1, input("tt1"), inLibrary = false) }
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `removing a plain Plan to watch title sends Simkl's own ids under its type and hides it at once`() = runTest {
        signedIn()
        seed(film(4, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt4", type = SimklMediaType.ANIME))
        val library = library()
        nothingMoved()
        engine.reply(201, """{"deleted":{"movies":0,"shows":1},"not_found":{"movies":[],"shows":[]}}""")
        library.setInLibrary(1, input("tt4", "movie"), inLibrary = false)

        val call = writes().single()
        assertEquals("/sync/history/remove", call.path)
        val item = json(call.body)["anime"]!!.jsonArray.single().jsonObject
        assertEquals(4L, item["ids"]!!.jsonObject["simkl"]!!.jsonPrimitive.content.toLong())
        assertFalse("seasons" in item)
        assertFalse(library.isInLibrary("tt4", "movie").first())
        assertTrue(library.items.first().isEmpty())
    }

    @Test
    fun `removing a title not in the library sends nothing`() = runTest {
        signedIn()
        seed()
        nothingMoved()
        library().setInLibrary(1, input("tt9"), inLibrary = false)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `a title added on this TV can be removed again without a confirm`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"))
        val library = library()
        // Added here: the removal's own sync reads it back as a plain Plan to watch entry, so nothing is wiped.
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt2"), inLibrary = true)
        assertTrue(library.isInLibrary("tt2", "series").first())
        engine.reply(200, """{"all":"b","tv_shows":{"plantowatch":"p2"}}""")
        engine.reply(200, """{"shows":[{"status":"plantowatch","show":{"title":"Show 2","ids":{"simkl":2,"imdb":"tt2"}}}]}""")
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt2"), inLibrary = false)
        assertEquals(listOf("/sync/add-to-list", "/sync/history/remove"), writes().map { it.path })
        assertEquals("2", json(writes().last().body)["shows"]!!.jsonArray.single().jsonObject["ids"]!!.jsonObject["simkl"]!!.jsonPrimitive.content)
        runCurrent()
        assertFalse(library.isInLibrary("tt2", "series").first())
        assertEquals(listOf("tt1"), library.items.first().map { it.id })
    }

    @Test
    fun `a mark sent while a removal syncs makes it decide again from a fresh pull`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt1"))
        val library = library()
        nothingMoved() // the removal's sync
        engine.reply(201, "{}") // the mark
        engine.reply(200, """{"all":"b","tv_shows":{"completed":"c2"}}""") // the re-decision's pull
        engine.reply(200, """{"shows":[{"status":"completed","last_watched_at":"2026-11-17T00:00:00Z",
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}]}""")
        val gate = CompletableDeferred<Unit>()
        engine.hold = gate
        val removal = async { runCatching { library.setInLibrary(1, input("tt1"), inLibrary = false) } }
        runCurrent() // the removal waits in its sync's activities call
        val episode = TrackingMediaReference(
            TrackingMediaKind.SHOW, "Show 1", 2020, TrackingExternalIds(imdb = "tt1"), TrackingEpisode(1, 1)
        )
        val mark = launch { writer.addToHistory(1, listOf(TrackingHistoryItem(episode, clock.now))) }
        runCurrent() // the mark holds the writer lock and waits for the client
        engine.hold = null
        gate.complete(Unit)
        mark.join()

        assertTrue(removal.await().exceptionOrNull() is SimklRemovalNeedsConfirmException)
        assertEquals(listOf("/sync/history"), writes().map { it.path })
    }

    @Test
    fun `a mark sent while an add syncs makes it decide again and never moves the now-listed title`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved() // the add's sync
        engine.reply(201, "{}") // the mark
        engine.reply(200, """{"all":"b","tv_shows":{"watching":"w2"}}""") // the re-decision's pull
        engine.reply(200, """{"shows":[{"status":"watching","last_watched_at":"2026-11-17T00:00:00Z",
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}]}""")
        val gate = CompletableDeferred<Unit>()
        engine.hold = gate
        val add = async { runCatching { library.setInLibrary(1, input("tt1"), inLibrary = true) } }
        runCurrent() // the add waits in its sync's activities call
        val episode = TrackingMediaReference(
            TrackingMediaKind.SHOW, "Show 1", 2020, TrackingExternalIds(imdb = "tt1"), TrackingEpisode(1, 1)
        )
        val mark = launch { writer.addToHistory(1, listOf(TrackingHistoryItem(episode, clock.now))) }
        runCurrent() // the mark holds the writer lock and waits for the client
        engine.hold = null
        gate.complete(Unit)
        mark.join()

        assertTrue(add.await().isSuccess)
        // Without the re-decision the add would send add-to-list and move the title from Watching to Plan to watch.
        assertEquals(listOf("/sync/history"), writes().map { it.path })
        assertTrue(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `a rating made elsewhere reaches the snapshot through the ratings delta and blocks the removal`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt1"))
        engine.reply(200, """{"all":"b","tv_shows":{"rated_at":"r2"}}""")
        engine.reply(200, """{"shows":[{"status":"plantowatch","user_rating":8,"show":{"ids":{"simkl":1}}}]}""")
        assertFailsSuspend<SimklRemovalNeedsConfirmException> { library().setInLibrary(1, input("tt1"), inLibrary = false) }
        assertEquals(listOf("/sync/activities", "/sync/ratings"), engine.calls.map { it.path })
        assertEquals("a", engine.calls[1].query("date_from"))
        assertEquals(8.0, store.get(1)!!.entries.single().userRating)
    }

    // The detail page's list picker (F1-F3)

    @Test
    fun `the picker opens on the synced list with what a removal would wipe, this TV's change first`() = runTest {
        signedIn()
        seed(
            show(1, SimklListStatus.WATCHING, "imdb" to "tt1", watchedCount = 2, rating = 8.0),
            show(2, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt2"),
            show(3, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt2"),
            film(4, SimklListStatus.COMPLETED, "imdb" to "tt4").copy(lastWatchedAt = "2026-01-01T00:00:00Z", watchedEpisodesCount = 3)
        )
        val library = library()
        assertEquals(SimklListing(TrackingListStatus.WATCHING, watched = 2, rating = 8), library.listing(1, input("tt1")))
        assertTrue(library.listing(1, input("tt1")).removalNeedsConfirm)
        assertEquals(SimklListing(TrackingListStatus.PLAN_TO_WATCH, duplicated = true), library.listing(1, input("tt2")))
        assertFalse(library.listing(1, input("tt2")).removalNeedsConfirm)
        // A film counts once.
        assertEquals(SimklListing(TrackingListStatus.COMPLETED, watched = 1), library.listing(1, input("tt4", "movie")))
        assertEquals(SimklListing(null), library.listing(1, input("tt9")))

        // Moved here: the picker shows the new list before a sync has it, watch facts still Simkl's.
        nothingMoved()
        engine.reply(201, "{}")
        assertEquals(TrackingListStatus.WATCHING, library.moveToList(1, input("tt1"), TrackingListStatus.DROPPED))
        assertEquals(SimklListing(TrackingListStatus.DROPPED, watched = 2, rating = 8), library.listing(1, input("tt1")))
    }

    @Test
    fun `a move sends the one matching entry by its Simkl id with the list and shows it at once`() = runTest {
        signedIn()
        seed(film(4, SimklListStatus.PLAN_TO_WATCH, "imdb" to "tt4", type = SimklMediaType.ANIME))
        val library = library()
        nothingMoved()
        engine.reply(201, """{"added":{"anime":[{"to":"completed"}]}}""")
        assertEquals(TrackingListStatus.PLAN_TO_WATCH, library.moveToList(1, input("tt4", "movie"), TrackingListStatus.COMPLETED))

        val call = writes().single()
        assertEquals("/sync/add-to-list", call.path)
        val item = json(call.body)["anime"]!!.jsonArray.single().jsonObject
        assertEquals("4", item["ids"]!!.jsonObject["simkl"]!!.jsonPrimitive.content)
        assertEquals(setOf("simkl"), item["ids"]!!.jsonObject.keys)
        assertEquals("completed", item["to"]!!.jsonPrimitive.content)
        assertEquals(listOf(TrackingListStatus.COMPLETED), library.items.first().map { it.trackerList })
    }

    @Test
    fun `a move of an unlisted title adds it by its ids onto the chosen list`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        assertNull(library.moveToList(1, input("tt1"), TrackingListStatus.WATCHING))
        val item = json(writes().single().body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals("tt1", item["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals("watching", item["to"]!!.jsonPrimitive.content)
        assertEquals(TrackingListStatus.WATCHING, library.items.first().single().trackerList)
    }

    @Test
    fun `a move onto the list Simkl already has sends nothing, and an unknown title fails`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.ON_HOLD, "imdb" to "tt1"))
        val library = library()
        nothingMoved()
        assertEquals(TrackingListStatus.ON_HOLD, library.moveToList(1, input("tt1"), TrackingListStatus.ON_HOLD))
        nothingMoved()
        engine.reply(201, """{"added":{},"not_found":{"shows":[{"ids":{"imdb":"tt9"}}]}}""")
        assertFailsSuspend<SimklUnmatchableException> { library.moveToList(1, input("tt9"), TrackingListStatus.WATCHING) }
        assertEquals(listOf("/sync/add-to-list"), writes().map { it.path })
        assertEquals(listOf("tt1"), library.items.first().map { it.id })
    }

    @Test
    fun `a confirmed removal goes when Simkl holds what was agreed, and asks again when it holds more`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.WATCHING, "imdb" to "tt1", watchedCount = 2, rating = 8.0))
        val library = library()
        val agreed = library.listing(1, input("tt1"))

        // Finished elsewhere since the picker opened (now Completed): refused, with what goes now.
        engine.reply(200, """{"all":"b","tv_shows":{"completed":"c2"}}""")
        engine.reply(200, """{"shows":[{"status":"completed","watched_episodes_count":2,
            "show":{"title":"Show 1","ids":{"simkl":1,"imdb":"tt1"}}}]}""")
        val refused = assertFailsSuspend<SimklRemovalNeedsConfirmException> { library.removeFromSimkl(1, input("tt1"), agreed) }
        assertEquals(TrackingListStatus.COMPLETED, refused.listing.list)
        assertTrue(writes().isEmpty())

        engine.reply(200, """{"all":"b","tv_shows":{"completed":"c2"}}""") // nothing moved since that sync
        engine.reply(201, "{}")
        library.removeFromSimkl(1, input("tt1"), refused.listing)
        assertEquals("/sync/history/remove", writes().single().path)
        assertFalse(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `a removal agreed on the picker's own listing goes when nothing changed`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1", watchedCount = 4, rating = 7.0))
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.removeFromSimkl(1, input("tt1"), library.listing(1, input("tt1")))
        assertEquals("/sync/history/remove", writes().single().path)
    }

    @Test
    fun `a move of a title on two Simkl entries goes by its ids and reads as a move`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.WATCHING, "imdb" to "tt1"), show(2, SimklListStatus.COMPLETED, "imdb" to "tt1"))
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        assertEquals(TrackingListStatus.WATCHING, library.moveToList(1, input("tt1"), TrackingListStatus.ON_HOLD))
        val item = json(writes().single().body)["shows"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("imdb"), item["ids"]!!.jsonObject.keys)
        assertEquals("hold", item["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the confirm's counts take the most Simkl gives, a last watch alone as one, the rating whole`() {
        val episodes = show(1, SimklListStatus.WATCHING, watchedCount = 1, rating = 7.6).copy(
            seasons = listOf(SimklSeason(1, listOf(SimklEpisode(1, "2026-01-01T00:00:00Z"), SimklEpisode(2, "2026-01-02T00:00:00Z"))))
        )
        assertEquals(SimklListing(TrackingListStatus.WATCHING, watched = 2, rating = 8), episodes.listing())
        assertEquals(1, show(2, SimklListStatus.PLAN_TO_WATCH, lastWatched = "2026-01-01T00:00:00Z").listing().watched)
        assertFalse(show(3, SimklListStatus.PLAN_TO_WATCH).listing().removalNeedsConfirm)
    }

    @Test
    fun `a removal matching two Simkl entries is refused even when agreed`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"), show(2, SimklListStatus.WATCHING, "imdb" to "tt1"))
        val library = library()
        nothingMoved()
        assertFailsSuspend<SimklDuplicateEntriesException> {
            library.removeFromSimkl(1, input("tt1"), library.listing(1, input("tt1")))
        }
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `the ratings delta sets ratings by Simkl id and never clears one`() {
        val rated = show(1, SimklListStatus.PLAN_TO_WATCH, rating = 6.0)
        val other = show(2, SimklListStatus.PLAN_TO_WATCH)
        val applied = applySimklRatings(
            listOf(rated, other),
            listOf(show(1, SimklListStatus.PLAN_TO_WATCH, rating = null), show(2, SimklListStatus.PLAN_TO_WATCH, rating = 9.0))
        )
        assertEquals(listOf(6.0, 9.0), applied.map { it.userRating })
        assertEquals(rated.seasons, applied.first().seasons)
    }

    @Test
    fun `a removal Simkl's rows cannot match is refused even when this TV shows the title listed`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("kitsu:5"), inLibrary = true)
        // Simkl filed it under ids that do not include kitsu.
        engine.reply(200, """{"all":"b","anime":{"plantowatch":"p2"}}""")
        engine.reply(200, """{"anime":[{"status":"completed","show":{"title":"A","ids":{"simkl":5,"mal":9}}}]}""")
        assertFailsSuspend<SimklUnmatchableException> { library.setInLibrary(1, input("kitsu:5"), inLibrary = false) }
        assertEquals(listOf("/sync/add-to-list"), writes().map { it.path })
    }

    @Test
    fun `a write that lands after a grant change is not shown`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        val sync1 = CompletableDeferred<Unit>()
        engine.hold = sync1
        val add = launch { library.setInLibrary(1, input("tt1"), inLibrary = true) }
        runCurrent() // in the sync's activities call
        val send = CompletableDeferred<Unit>()
        engine.hold = send
        sync1.complete(Unit)
        runCurrent() // in the add-to-list call
        library.forget(1)
        send.complete(Unit)
        add.join()
        assertFalse(library.isInLibrary("tt1", "series").first())
    }

    // Settling

    @Test
    fun `this TV's change stays until a sync that started a minute after it settles`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt1"), inLibrary = true)

        // A sync at once (nothing moved at Simkl yet in this fake): the change may not be in it, so it stays.
        engine.reply(200, """{"all":"a"}""")
        assertTrue(sync.refresh(1, com.nuvio.tv.core.tracking.TrackingRefreshIntent.USER_INITIATED))
        runCurrent() // the shared view takes the new settled stamp
        assertTrue(library.isInLibrary("tt1", "series").first())

        clock.now += 61_000L
        engine.reply(200, """{"all":"a"}""")
        assertTrue(sync.refresh(1, com.nuvio.tv.core.tracking.TrackingRefreshIntent.USER_INITIATED))
        runCurrent() // the shared view takes the new settled stamp
        assertFalse(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `forget drops this TV's changes`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt1"), inLibrary = true)
        library.forget(1)
        assertFalse(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `only a settled sync lets this TV's change go`() = runTest {
        signedIn()
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt1"), inLibrary = true)
        clock.now += 61_000L
        // Within the automatic floor of the write's own sync: skipped, not settled.
        assertFalse(sync.refresh(1, com.nuvio.tv.core.tracking.TrackingRefreshIntent.AUTOMATIC))
        runCurrent()
        assertTrue(library.isInLibrary("tt1", "series").first())
    }

    @Test
    fun `a change on another profile never shows on the active one`() = runTest {
        signedIn(2)
        seed(profileId = 2)
        seed()
        val library = library()
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(2, input("tt1"), inLibrary = true)
        assertFalse(library.isInLibrary("tt1", "series").first())
        assertTrue(library.items.first().isEmpty())
    }

    @Test
    fun `nothing is written when the sync before it fails`() = runTest {
        signedIn()
        // No snapshot yet (a fresh sign-in): the first full pull fails at its activities call.
        engine.reply(404, """{"error":"not_found"}""")
        assertFailsSuspend<SimklApiException> { library().setInLibrary(1, input("tt1"), inLibrary = true) }
        assertTrue(writes().isEmpty())
        assertNull(store.get(1))
    }

    @Test
    fun `the fields the library reads decode from Simkl's reply`() = runTest {
        signedIn()
        engine.reply(200, """{"all":"a"}""")
        engine.reply(
            200,
            """{"shows":[
              {"status":"plantowatch","added_to_watchlist_at":"2026-02-01T00:00:00Z","user_rating":7,"watched_episodes_count":0,
               "show":{"title":"Rated","poster":"12/1234abcd","year":2020,"ids":{"simkl":1,"imdb":"tt1"}}},
              {"status":"plantowatch","added_to_watchlist_at":"2026-03-01T00:00:00Z","user_rating":null,"watched_episodes_count":2,
               "show":{"title":"Started","poster":null,"ids":{"simkl":2,"imdb":"tt2"}}},
              {"status":"plantowatch","added_to_watchlist_at":"2026-01-01T00:00:00Z",
               "show":{"title":"Plain","ids":{"simkl":3,"imdb":"tt3"}}}
            ]}"""
        )
        engine.reply(200, "{}")
        engine.reply(200, "{}")
        engine.reply(200, "[]")
        assertTrue(sync.refresh(1, com.nuvio.tv.core.tracking.TrackingRefreshIntent.USER_INITIATED))

        val library = library()
        val items = library.items.first()
        assertEquals(listOf("tt2", "tt1", "tt3"), items.map { it.id })
        assertEquals("https://wsrv.nl/?url=https://simkl.in/posters/12/1234abcd_m.webp&q=90", items[1].poster)
        listOf("tt1", "tt2").forEach { id ->
            nothingMoved()
            assertFailsSuspend<SimklRemovalNeedsConfirmException> { library.setInLibrary(1, input(id), inLibrary = false) }
        }
        nothingMoved()
        engine.reply(201, "{}")
        library.setInLibrary(1, input("tt3"), inLibrary = false)
        assertEquals(listOf("/sync/history/remove"), writes().map { it.path })
    }

    // Transfer

    @Test
    fun `a bulk add sends one call for titles on no list and accepts listed ones as they are`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"))
        val library = library()
        nothingMoved()
        engine.reply(201, """{"added":{},"not_found":{"shows":[{"ids":{"imdb":"tt3"}}]}}""")
        val accepted = library.addAll(1, listOf(input("tt1"), input("tt2"), input("tt3"), input("myaddon:4")))

        assertEquals(setOf("tt1", "tt2"), accepted)
        val sent = json(writes().single().body)["shows"]!!.jsonArray.map { it.jsonObject["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content }
        assertEquals(listOf("tt2", "tt3"), sent)
        assertTrue(library.isInLibrary("tt2", "series").first())
        assertFalse(library.isInLibrary("tt3", "series").first())
    }

    @Test
    fun `a bulk add on a spent day stops the transfer`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.COMPLETED, "imdb" to "tt1"))
        nothingMoved()
        engine.reply(429, """{"error":"user_limit_exceeded"}""")
        val failure = assertFailsSuspend<TrackingRateLimitException> { library().addAll(1, listOf(input("tt1"), input("tt2"))) }
        assertEquals(setOf("tt1"), failure.accepted)
        assertNull(failure.sent)
    }

    @Test
    fun `a bulk add on an already spent day stops the transfer with nothing sent`() = runTest {
        signedIn()
        seed()
        engine.reply(429, """{"error":"user_limit_exceeded"}""")
        assertFailsSuspend<SimklApiException> { sync.refresh(1, com.nuvio.tv.core.tracking.TrackingRefreshIntent.USER_INITIATED) }
        engine.calls.clear()
        assertFailsSuspend<TrackingRateLimitException> { library().addAll(1, listOf(input("tt1"))) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a transfer read syncs first and gives every id a transfer matches by`() = runTest {
        signedIn()
        seed(show(1, SimklListStatus.DROPPED, "imdb" to "tt1", "tmdb" to 11))
        engine.reply(200, """{"all":"a"}""")
        val read = library().readLibrary(1)
        assertEquals("/sync/activities", engine.calls.single().path)
        assertEquals(setOf("tt1", "tmdb:11"), read.single().externalIds)
        assertEquals("tt1", read.single().entry.id)
    }

    @Test
    fun `a transfer read fails when the sync does not settle`() = runTest {
        seed(show(1, SimklListStatus.DROPPED, "imdb" to "tt1"))
        // Signed out: the sync is skipped.
        assertFailsSuspend<SimklException> { library().readLibrary(1) }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `not found ids are read from every list in the reply`() {
        val missed = simklNotFoundIds("""{"not_found":{"movies":[{"ids":{"imdb":"tt1"}}],"shows":[{"ids":{"tmdb":2}},{"title":"x"}]}}""")
        assertEquals(listOf("tt1", null), missed.map { it.imdb })
        assertEquals(2L, missed[1].tmdb)
        assertTrue(simklNotFoundIds("not json").isEmpty())
    }
}
