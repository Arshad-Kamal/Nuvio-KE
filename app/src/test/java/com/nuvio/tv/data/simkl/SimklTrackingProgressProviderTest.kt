package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingAccount
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.WatchProgress
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Spec 106 S2a: the snapshot store and Simkl as the Continue Watching source. */
class SimklTrackingProgressProviderTest {
    @get:Rule val folder = TemporaryFolder()

    private val engine = FakeSimklEngine()
    private val clock = FakeClock()
    private val client = simklClient(engine, clock)
    private val accounts = accountStore()
    private val session = SimklSession(client, SimklTokens(client, TEST_CONFIG, accounts) { clock.now }, SimklQuota { clock.now })
    private val profile = MutableStateFlow(1)
    private val appId = MutableStateFlow<String?>("client-v2")

    private fun store(dir: File = folder.root) = SimklSyncStore(dir, Dispatchers.Unconfined)

    private val animeModes = mutableMapOf(1 to MutableStateFlow(SimklAnimeSeasons.PER_SHOW))

    private fun TestScope.provider(store: SimklSyncStore) = SimklTrackingProgressProvider(
        store, SimklSync(session, store, accounts) { clock.now }, accounts, appId, profile, backgroundScope,
        UnconfinedTestDispatcher(testScheduler),
        animeSeasons = { profileId -> animeModes.getOrPut(profileId) { MutableStateFlow(SimklAnimeSeasons.PER_SHOW) } }
    ) { clock.now }

    private val animeStored = SimklSnapshot(
        activities = SimklActivities(all = "a"),
        entries = decodeAllItems("""{"anime":[{"status":"watching","show":{"ids":{"simkl":5,"imdb":"tt2560140","kitsu":"8671"}},
            "seasons":[{"number":1,"episodes":[{"number":4,"watched_at":"2026-11-01T10:00:00Z","tvdb":{"season":2,"episode":4}}]}]}]}""")
    )

    private val stored = SimklSnapshot(
        activities = SimklActivities(all = "a"),
        entries = decodeAllItems("""{"movies":[{"status":"completed","movie":{"title":"Matrix","ids":{"simkl":1,"imdb":"tt0133093"}}}]}""")
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
    fun `the first values are the stored snapshot's`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored)
        val provider = provider(store())
        assertEquals(setOf("tt0133093"), provider.watchedMovieIds.first())
        assertTrue(provider.isWatched("tt0133093", null, null).first())
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `a play on this TV shows before Simkl has it, and a finished one counts as watched`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store())
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        provider.applyOptimisticProgress(progress("tt2", 100f, 2_000L, season = 1, episode = 3))
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId })
        assertTrue(provider.isWatched("tt2", 1, 3).first())
        assertEquals(100f, provider.episodeProgress("tt2").first().getValue(1 to 3).progressPercent)
    }

    @Test
    fun `a removal hides the snapshot's mark and a whole-title removal its resume points`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored.copy(playback = listOf(SimklPlayback(id = 4, progress = 20.0, pausedAt = "2026-11-01T00:00:00Z",
            episode = SimklPlaybackEpisode(1, 2), show = SimklMedia(ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt9")))))))
        val provider = provider(store())
        assertEquals(listOf("tt9"), provider.allProgress.first().map { it.contentId })
        provider.applyOptimisticRemoval("tt0133093", null, null)
        provider.applyOptimisticRemoval("tt9", null, null)
        assertFalse(provider.isWatched("tt0133093", null, null).first())
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `a sync started a minute after a play replaces it with Simkl's state`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt", clock.now + 7 * 86_400_000L))
        val store = store()
        store.saveIf(1, 0L, stored)
        val provider = provider(store)
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        engine.reply(200, """{"all":"a"}""")
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId }) // too recent to have reached Simkl
        clock.now += 61_000L
        engine.reply(200, """{"all":"a"}""")
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `a new anime mode re-projects the snapshot with no call to Simkl`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, animeStored)
        val provider = provider(store())
        assertTrue(provider.isWatched("tt2560140", 2, 4).first())
        animeModes.getValue(1).value = SimklAnimeSeasons.PER_SEASON
        testScheduler.runCurrent()
        assertTrue(provider.isWatched("kitsu:8671", 1, 4).first())
        assertFalse(provider.isWatched("tt2560140", 2, 4).first())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `each profile projects with its own anime mode across a switch`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, animeStored)
        store().saveIf(2, 0L, animeStored)
        animeModes[2] = MutableStateFlow(SimklAnimeSeasons.PER_SEASON)
        val provider = provider(store())
        assertTrue(provider.isWatched("tt2560140", 2, 4).first())
        profile.value = 2
        assertTrue(provider.isWatched("kitsu:8671", 1, 4).first())
        profile.value = 1
        assertTrue(provider.isWatched("tt2560140", 2, 4).first())
    }

    @Test
    fun `removing an anime resume point matches it as the profile's mode names it`() = runTest(UnconfinedTestDispatcher()) {
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt", clock.now + 7 * 86_400_000L))
        animeModes.getValue(1).value = SimklAnimeSeasons.PER_SEASON
        val provider = provider(store())
        engine.reply(200, """[{"id":7,"progress":10,"paused_at":"2026-11-01T11:00:00Z",
            "episode":{"season":1,"number":5,"tvdb_season":2,"tvdb_number":5},"anime":{"ids":{"simkl":5,"kitsu":"8671"}}}]""")
        engine.reply(200)
        provider.removeProgress(1, "kitsu:8671", 1, 5)
        assertEquals(listOf("/sync/playback", "/sync/playback/7"), engine.calls.map { it.path })
    }

    @Test
    fun `after a profile switch the first values are the new profile's`() = runTest(UnconfinedTestDispatcher()) {
        store().saveIf(1, 0L, stored)
        val provider = provider(store())
        assertEquals(setOf("tt0133093"), provider.watchedMovieIds.first())
        // Nobody reads for longer than the sharing timeout: a shared cache would now hold profile 1's value.
        testScheduler.advanceTimeBy(10_000L)
        profile.value = 2
        assertEquals(emptySet<String>(), provider.watchedMovieIds.first())
    }

    @Test
    fun `plays on one profile are not shown on another, and forget drops them`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store())
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        profile.value = 2
        assertTrue(provider.allProgress.first().isEmpty())
        profile.value = 1
        provider.forget(1)
        assertTrue(provider.allProgress.first().isEmpty())
    }

    @Test
    fun `a skipped sync keeps this TV's plays`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store()) // signed out: the sync is skipped
        provider.applyOptimisticProgress(progress("tt1", 30f, 1_000L))
        clock.now += 120_000L
        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("tt1"), provider.allProgress.first().map { it.contentId })
    }

    @Test
    fun `signed in needs this TV's app id as well as the account`() = runTest(UnconfinedTestDispatcher()) {
        val provider = provider(store())
        accounts.save(1, TrackingProviderId.SIMKL, TrackingAccount("at", "rt"))
        assertTrue(provider.isAuthenticated.first())
        appId.value = null
        assertFalse(provider.isAuthenticated.first())
    }
}
