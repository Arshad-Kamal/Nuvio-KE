package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.WatchProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 105 S2a: the snapshot store, the key-change hook and MDBList as the Continue Watching source. */
class MdbListTrackingProgressProviderTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeMdbListEngine()
    private val clock = FakeClock()
    private val accounts = accountStore()
    private val client = mdbListClient(engine, clock)
    private val session = MdbListSession(client, accounts, MdbListQuota { clock.now })
    private val profile = MutableStateFlow(1)

    private fun store(dir: File = folder.root) = MdbListSyncStore(dir, Dispatchers.Unconfined)

    private fun TestScope.maps() = tvdbMapStore(File(folder.root, "tvdb_maps.json"), backgroundScope)

    private fun TestScope.provider(store: MdbListSyncStore, maps: MdbListTvdbMapStore = maps()) = MdbListTrackingProgressProvider(
        store, MdbListSync(session, store, accounts) { clock.now }, accounts, profile, backgroundScope,
        UnconfinedTestDispatcher(testScheduler), MdbListEpisodeNumbering(session, maps, backgroundScope) { clock.now }
    ) { clock.now }

    private val stored = MdbListSnapshot(
        activities = MdbListActivities(journalAt = "j1", serverTime = "2026-11-09T00:00:00Z"),
        journalSince = "2026-11-09T00:00:00Z",
        marks = listOf(MdbListWatchedMark(MdbListMedia("Matrix", ids = mapOf("imdb" to JsonPrimitive("tt0133093"))), watchedAt = "2026-11-01T00:00:00Z"))
    )

    private fun progress(contentId: String, percent: Float, at: Long, season: Int? = null, episode: Int? = null) = WatchProgress(
        contentId = contentId, contentType = if (season == null) "movie" else "series", name = "x", poster = null, backdrop = null,
        logo = null, videoId = contentId, season = season, episode = episode, episodeTitle = null, position = 0L, duration = 0L,
        lastWatched = at, progressPercent = percent
    )

    @Test
    fun `the store keeps a snapshot across instances and drops an unreadable one`() = runTest(UnconfinedTestDispatcher()) {
        assertTrue(store().saveIf(1, 0L, stored))
        assertEquals(stored, store().get(1))
        File(folder.root, "snapshot_2.json").writeText("{not json")
        assertNull(store().get(2))
    }

    @Test
    fun `a clear drops the file and refuses a save begun before it`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.saveIf(1, 0L, stored)
        val generation = store.generation(1)
        store.clear(1)
        assertFalse(store.saveIf(1, generation, stored))
        assertNull(store().get(1))
        assertFalse(File(folder.root, "snapshot_1.json").exists())
    }

    @Test
    fun `connecting or disconnecting a key drops the profile's synced data`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.saveIf(1, 0L, stored)
        val provider = provider(store)
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        val auth = MdbListKeyAuth(client, session, accounts) { profileId ->
            provider.forget(profileId)
            store.clear(profileId)
        }
        engine.reply(200, """{"username":"arshadk"}""")
        auth.connect(1, "abc123def456ghi789") { true }
        assertNull(store.get(1))
        assertTrue(provider.allProgress.first().isEmpty())
        store.saveIf(1, store.generation(1), stored)
        auth.disconnect(1)
        assertNull(store.get(1))
        assertNull(accounts.get(1, TrackingProviderId.MDBLIST))
    }

    @Test
    fun `the first values are the stored snapshot's`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored)
        val provider = provider(store())
        assertEquals(setOf("tt0133093"), provider.watchedMovieIds.first())
        assertTrue(provider.isWatched("tt0133093", null, null).first())
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `a play on this TV shows before MDBList has it, and one past 80 percent counts as watched`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store())
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        provider.applyOptimisticProgress(progress("tt2", 85f, 2_000L, season = 1, episode = 3))
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId })
        assertTrue(provider.isWatched("tt2", 1, 3).first())
        assertEquals(85f, provider.episodeProgress("tt2").first().getValue(1 to 3).progressPercent)
    }

    @Test
    fun `a removal hides the snapshot's mark and a whole-title removal its resume points`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored.copy(playback = listOf(MdbListPlayback(id = 4, progress = 20.0, pausedAt = "2026-11-01T00:00:00Z",
            type = "episode", show = MdbListMedia(ids = mapOf("imdb" to JsonPrimitive("tt9"))), episode = MdbListPlaybackEpisode(1, 2)))))
        val maps = maps()
        maps.update { MdbListTvdbMapsState(aliases = mapOf("tt9" to 9L), shows = mapOf(9L to MdbListShowNumbering(differs = false, fetchedAt = clock.now))) }
        val provider = provider(store(), maps)
        assertEquals(listOf("tt9"), provider.allProgress.first().map { it.contentId })
        provider.applyOptimisticRemoval("tt0133093", null, null)
        provider.applyOptimisticRemoval("tt9", null, null)
        assertFalse(provider.isWatched("tt0133093", null, null).first())
        assertTrue(provider.allProgress.first().isEmpty())
        assertTrue(provider.episodeProgress("tt9").first().isEmpty())
    }

    @Test
    fun `a sync started a minute after a play replaces it with MDBList's state`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789"))
        val store = store()
        store.saveIf(1, 0L, stored)
        val provider = provider(store)
        provider.applyOptimisticProgress(progress("tt1", 30f, clock.now))
        val quiet = """{"journal_at":"j1","server_time":"2026-11-09T01:00:00Z"}"""
        clock.now = parseMdbListTime("2026-11-09T01:00:00Z")!!
        provider.applyOptimisticProgress(progress("tt1", 30f, clock.now))
        engine.reply(200, quiet)
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId }) // too recent to have reached MDBList
        clock.now += 61_000L
        engine.reply(200, quiet)
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `after a profile switch the first values are the new profile's`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored)
        val provider = provider(store())
        assertEquals(setOf("tt0133093"), provider.watchedMovieIds.first())
        testScheduler.advanceTimeBy(10_000L)
        profile.value = 2
        assertEquals(emptySet<String>(), provider.watchedMovieIds.first())
    }

    @Test
    fun `a skipped sync keeps this TV's plays`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store()) // no key: the sync is skipped
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        clock.now += 120_000L
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId })
    }

    private fun conanMark(season: Int, episode: Int, tmdb: Long? = CONAN_TMDB) = MdbListWatchedMark(
        MdbListMedia("Detective Conan", ids = buildMap {
            put("imdb", JsonPrimitive(CONAN_IMDB))
            tmdb?.let { put("tmdb", JsonPrimitive(it)) }
        }),
        season, episode, "2026-11-10T00:00:00Z"
    )

    private suspend fun MdbListTvdbMapStore.withConan() {
        val map = mdbListJson.decodeFromString(MdbListTvdbMapReply.serializer(), tvdbMapFixture("tvdb-map-30983.json"))
        update { it.copy(aliases = it.aliases + (CONAN_IMDB to CONAN_TMDB)).withShow(CONAN_TMDB, map.toNumbering(null, 1L)) }
    }

    @Test
    fun `episodes read in the addon's numbering, an unknown show is withheld until its map arrives`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.saveIf(1, 0L, stored.copy(marks = listOf(conanMark(1, 1221), conanMark(1, 15, tmdb = 1L))))
        val maps = maps()
        val provider = provider(store, maps)
        assertTrue(provider.watchedItems.first().none { it.contentId == CONAN_IMDB })
        maps.withConan()
        // TMDb S1E1221 is TVDB S34E35; the row naming another TMDb show stays withheld.
        assertEquals(listOf(34 to 35), provider.watchedItems.first().filter { it.contentId == CONAN_IMDB }.map { it.season to it.episode })
        assertTrue(provider.isWatched(CONAN_IMDB, 34, 35).first())
        assertEquals(setOf(34 to 35), provider.episodeProgress(CONAN_IMDB).first().keys)
        assertEquals("$CONAN_IMDB:34:35", provider.nextUpSeeds.first().single().videoId)
    }

    @Test
    fun `an episode removal clears MDBList's counterpart, and sends nothing when there is none`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789"))
        val maps = maps()
        maps.withConan()
        val provider = provider(store(), maps)
        clock.now = 2L // the map is fresh
        val playback = """[{"id":7,"progress":20,"type":"episode","show":{"ids":{"imdb":"$CONAN_IMDB","tmdb":$CONAN_TMDB}},"episode":{"season":1,"number":1221}}]"""
        engine.reply(200, playback)
        engine.reply(200, "{}")
        provider.removeProgress(1, CONAN_IMDB, 34, 35)
        assertEquals(listOf("/sync/playback", "/scrobble/clear"), engine.calls.map { it.path })
        val body = engine.calls[1].body!!
        assertTrue(body, body.contains("\"season\":1,") && body.contains("\"episode\":1221"))

        provider.removeProgress(1, CONAN_IMDB, 20, 2) // merged at MDBList: no one-to-one counterpart
        assertEquals(2, engine.calls.size)
        engine.reply(400)
        provider.removeProgress(1, "tt42", 1, 1) // its alias lookup fails: nothing is cleared
        assertEquals(listOf("/imdb/show/tt42/"), engine.calls.drop(2).map { it.path })
    }

    @Test
    fun `local entries are kept per episode by the maps`() = runTest(UnconfinedTestDispatcher()) {
        val maps = maps()
        maps.withConan()
        val provider = provider(store(), maps)
        assertTrue(provider.retainsLocalEpisode(CONAN_IMDB, 0, 1, 5L)) // tvdb_only special
        assertFalse(provider.retainsLocalEpisode(CONAN_IMDB, 34, 35, 5L)) // linked before this play
        assertTrue(provider.retainsLocalEpisode("kitsu:1", 1, 1, 5L))
    }

    @Test
    fun `a refresh that brought MDBList's state fetches the maps its shows lack`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.MDBLIST, TrackingAccount(accessToken = "abc123def456ghi789"))
        val store = store()
        store.saveIf(1, 0L, stored.copy(marks = listOf(conanMark(1, 1221))))
        val provider = provider(store)
        engine.reply(200, """{"journal_at":"j1","server_time":"2026-11-09T01:00:00Z"}""")
        engine.reply(200, tvdbMapFixture("tvdb-map-30983.json"))
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        // The stored snapshot predates S3a: its watchlist is pulled once in the same run.
        assertEquals(listOf("/sync/last_activities", "/watchlist/items", "/shows/30983/tvdb-map/"), engine.calls.map { it.path })
        assertEquals(listOf(34 to 35), provider.watchedItems.first().filter { it.contentId == CONAN_IMDB }.map { it.season to it.episode })
    }

    @Test
    fun `the keep answers are re-asked only when a differing map or its alias changes`() = runTest(UnconfinedTestDispatcher()) {
        val maps = maps()
        val provider = provider(store(), maps)
        var signals = 0
        backgroundScope.launch { provider.localRetentionChanges.collect { signals++ } }
        assertEquals(1, signals)
        maps.update { it.copy(aliases = it.aliases + ("tt2" to 2L)).withShow(2L, MdbListShowNumbering(differs = false, fetchedAt = 1L)) }
        assertEquals(1, signals) // a matching map changes no answer
        maps.withConan()
        assertEquals(2, signals)
    }
}
