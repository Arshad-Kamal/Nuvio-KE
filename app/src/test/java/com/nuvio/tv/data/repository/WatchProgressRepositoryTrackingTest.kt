package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.FakeTrackingProvider
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressRouter
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingSource
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 104 S2b: Continue Watching reads from the chosen tracker, local data is always written and never cleared
 * (S2-R1), and marks reach every connected tracker.
 */
class WatchProgressRepositoryTrackingTest {

    private val profileManager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(PROFILE_ID) }
    private val localProgress = MutableStateFlow(listOf(progress(SERIES_ID, 1, 5, at = 100L), progress("kitsu:9", 1, 1, at = 90L)))
    private val localWatched = MutableStateFlow(listOf(watched(MOVIE_ID)))
    private val prefs = mockk<WatchProgressPreferences>(relaxed = true) {
        every { allProgress } returns localProgress
        coEvery { getAllRawEntries(any()) } returns emptyMap()
    }
    private val watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true) {
        every { allItems } returns localWatched
    }
    private val source = MutableStateFlow(TrackingSource.NUVIO)
    private val mdblist = FakeTrackingProvider(TrackingProviderId.MDBLIST, connected = true)
    private val simkl = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = true)
    private val trackerProgress = MutableStateFlow(listOf(progress(SERIES_ID, 1, 4, at = 50L), progress("tt7", at = 80L)))
    private val retention = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val provider = mockk<TrackingProgressProvider>(relaxed = true) {
        every { providerId } returns TrackingProviderId.MDBLIST
        every { allProgress } returns trackerProgress
        every { watchedItems } returns MutableStateFlow(emptyList())
        every { retainsLocal(any()) } answers { firstArg<String>().startsWith("kitsu:") }
        every { retainsLocalEpisode(any(), any(), any(), any()) } answers { firstArg<String>().startsWith("kitsu:") }
        every { localRetentionChanges } returns retention
    }
    private val added = mutableListOf<Pair<TrackingProviderId, List<TrackingHistoryItem>>>()
    private val removed = mutableListOf<Pair<TrackingProviderId, List<TrackingMediaReference>>>()

    private fun writer(id: TrackingProviderId) = object : TrackingHistoryWriter {
        override val providerId = id
        override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
            added += id to items.toList()
            return TrackingMutationResult(items.size)
        }
        override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
            removed += id to items.toList()
            return TrackingMutationResult(items.size)
        }
    }

    private fun TestScope.repository(): Pair<WatchProgressRepositoryImpl, TrackingProgressRouter> {
        val router = TrackingProgressRouter(
            activeProviderFlow = source.map { if (it == TrackingSource.MDBLIST) provider else null },
            hasProgressProviders = true,
            historyWriters = TrackingHistoryWriterRegistry(setOf(writer(TrackingProviderId.MDBLIST), writer(TrackingProviderId.SIMKL))),
            registry = TrackingProviderRegistry(setOf(mdblist, simkl)),
            scope = backgroundScope
        )
        val authManager = mockk<AuthManager> { every { isAuthenticated } returns false }
        val settings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true)
        listOf(prefs, watchedItemsPreferences, settings).forEach { it.injectProfileManager(profileManager) }
        return WatchProgressRepositoryImpl(
            watchProgressPreferences = prefs,
            continueWatchingSettings = settings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = watchedItemsPreferences,
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            tvRecommendationManager = mockk<TvRecommendationManager>(relaxed = true),
            trackingRouter = router
        ) to router
    }

    @Test
    fun `Continue Watching follows the source, and switching back shows the untouched local entries`() = runTest {
        val (repository, _) = repository()
        assertEquals(listOf(SERIES_ID, "kitsu:9"), repository.allProgress.first().map { it.contentId })

        source.value = TrackingSource.MDBLIST
        // The tracker's titles; the newer local episode of the same show; the local title the tracker cannot hold.
        assertEquals(
            listOf(Triple(SERIES_ID, 1, 5), Triple("kitsu:9", 1, 1), Triple("tt7", null, null)),
            repository.allProgress.first().map { Triple(it.contentId, it.season, it.episode) }
        )

        source.value = TrackingSource.NUVIO
        assertEquals(localProgress.value, repository.allProgress.first())
        coVerify(exactly = 0) { watchedItemsPreferences.unmarkAsWatched(any(), any(), any(), any()) }
        coVerify(exactly = 0) { prefs.removeProgress(any(), any(), any(), any()) }
    }

    @Test
    fun `a mark is written locally and reaches every connected tracker, but not a whole-series mark`() = runTest {
        val (repository, router) = repository()
        val episode = progress(SERIES_ID, 1, 6, at = 200L)

        repository.markAsCompleted(episode)
        repository.markAsCompleted(progress(SERIES_ID, null, null, at = 201L))
        router.awaitQueuedWrites()

        coVerify(exactly = 1) { watchedItemsPreferences.markAsWatched(match { it.episode == 6 }, PROFILE_ID) }
        assertEquals(listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL), added.map { it.first }.sorted())
        assertTrue(added.all { (_, items) -> items.single().media.episode?.number == 6 })
    }

    @Test
    fun `the player's own completion is not sent as a mark (its scrobble already is)`() = runTest {
        val (repository, router) = repository()
        repository.markAsCompleted(progress(MOVIE_ID, at = 1L), broadcastToTrackers = false)
        router.awaitQueuedWrites()
        assertTrue(added.isEmpty())
    }

    @Test
    fun `an unmark reaches the trackers for an episode or a film, never for a whole series`() = runTest {
        val (repository, router) = repository()
        repository.removeFromHistory(SERIES_ID, contentType = "series")
        repository.removeFromHistory(SERIES_ID, season = 1, episode = 2)
        repository.removeFromHistory(MOVIE_ID, contentType = "movie")
        router.awaitQueuedWrites()

        assertEquals(
            setOf(TrackingMediaKind.SHOW to 2, TrackingMediaKind.MOVIE to null),
            removed.filter { it.first == TrackingProviderId.MDBLIST }.flatMap { it.second }.map { it.kind to it.episode?.number }.toSet()
        )
        assertEquals(2, removed.count { it.first == TrackingProviderId.SIMKL })
    }

    @Test
    fun `an episode unmark is built from its stored progress, so it carries the video id and key its mark had (Spec 106 D17)`() = runTest {
        val stored = progress(SERIES_ID, 2, 19, at = 1L).copy(videoId = "mal:42203:7")
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns MutableStateFlow(mapOf((2 to 19) to stored))
        val (repository, router) = repository()
        repository.markAsCompleted(stored)
        repository.removeFromHistory(SERIES_ID, season = 2, episode = 19)
        router.awaitQueuedWrites()

        val mark = added.first { it.first == TrackingProviderId.SIMKL }.second.single().media
        val unmark = removed.first { it.first == TrackingProviderId.SIMKL }.second.single()
        assertEquals("mal:42203:7", unmark.catalog?.videoId)
        assertEquals(mark.kind, unmark.kind)
        assertEquals(mark.stableKey, unmark.stableKey)
    }

    @Test
    fun `a stored episode video id outranks the caller's, so an unmark from another screen goes where the mark went (Spec 106 D18)`() = runTest {
        // Marked in an anime addon's meta (the stored record); unmarked from a screen showing Cinemeta's ids.
        val stored = progress(SERIES_ID, 2, 19, at = 1L).copy(videoId = "mal:42203:7", contentType = "anime")
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns MutableStateFlow(mapOf((2 to 19) to stored))
        val (repository, router) = repository()
        repository.markAsCompleted(stored)
        repository.removeFromHistory(SERIES_ID, season = 2, episode = 19, contentType = "series", videoId = "$SERIES_ID:2:19")
        router.awaitQueuedWrites()

        val mark = added.first { it.first == TrackingProviderId.SIMKL }.second.single().media
        val unmark = removed.first { it.first == TrackingProviderId.SIMKL }.second.single()
        assertEquals("mal:42203:7", unmark.catalog?.videoId)
        assertEquals("anime", unmark.catalog?.contentType)
        assertEquals(mark.stableKey, unmark.stableKey)
    }

    @Test
    fun `a single unmark with nothing stored goes by the caller's video id and type, on the mark's key (Spec 106 S5b)`() = runTest {
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns MutableStateFlow(emptyMap())
        val (repository, router) = repository()
        val mark = progress(SERIES_ID, 3, 1, at = 1L).copy(videoId = "mal:50602:1", contentType = "anime")
        repository.markAsCompleted(mark)
        repository.removeFromHistory(SERIES_ID, season = 3, episode = 1, contentType = "anime", videoId = "mal:50602:1")
        router.awaitQueuedWrites()

        val marked = added.first { it.first == TrackingProviderId.SIMKL }.second.single().media
        val unmark = removed.first { it.first == TrackingProviderId.SIMKL }.second.single()
        assertEquals("mal:50602:1", unmark.catalog?.videoId)
        assertEquals(marked.stableKey, unmark.stableKey)
    }

    @Test
    fun `the caller's video id and type fill in for an episode with no stored episode video id (Spec 106 S5b)`() = runTest {
        val known = progress(SERIES_ID, 2, 19, at = 1L).copy(videoId = "mal:42203:7", contentType = "anime")
        val bareParent = progress(SERIES_ID, 3, 2, at = 1L).copy(videoId = SERIES_ID, contentType = "series")
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns
            MutableStateFlow(mapOf((2 to 19) to known, (3 to 2) to bareParent))
        val (repository, router) = repository()
        val callerMark = progress(SERIES_ID, 3, 1, at = 1L).copy(videoId = "mal:50602:1", contentType = "anime")
        repository.markAsCompleted(callerMark)
        repository.removeFromHistoryBatch(
            SERIES_ID,
            listOf(2 to 19, 3 to 1, 3 to 2),
            videoIds = mapOf((2 to 19) to "$SERIES_ID:2:19", (3 to 1) to "mal:50602:1", (3 to 2) to "mal:50602:2"),
            contentType = "anime"
        )
        router.awaitQueuedWrites()

        val mark = added.first { it.first == TrackingProviderId.SIMKL }.second.single().media
        val unmarks = removed.filter { it.first == TrackingProviderId.SIMKL }.flatMap { it.second }
            .associateBy { it.episode?.season to it.episode?.number }
        assertEquals("mal:42203:7", unmarks.getValue(2 to 19).catalog?.videoId)
        assertEquals("mal:50602:1", unmarks.getValue(3 to 1).catalog?.videoId)
        assertEquals(mark.stableKey, unmarks.getValue(3 to 1).stableKey)
        // Stored, but only the parent id: the caller's id and type replace it.
        assertEquals("mal:50602:2", unmarks.getValue(3 to 2).catalog?.videoId)
        assertEquals("anime", unmarks.getValue(3 to 2).catalog?.contentType)
    }

    @Test
    fun `a disconnected tracker gets no marks`() = runTest {
        simkl.authenticated.value = false
        val (repository, router) = repository()
        repository.markAsCompleted(progress(MOVIE_ID, at = 1L))
        router.awaitQueuedWrites()
        assertEquals(listOf(TrackingProviderId.MDBLIST), added.map { it.first })
    }

    @Test
    fun `with a tracker as source, a save shows at once and a Remove reaches the tracker`() = runTest {
        source.value = TrackingSource.MDBLIST
        val (repository, router) = repository()
        val save = progress(SERIES_ID, 1, 6, at = 300L)

        repository.saveProgress(save, syncRemote = false)
        repository.removeProgress("tt7")
        router.awaitQueuedWrites()

        verify(exactly = 1) { provider.applyOptimisticProgress(save) }
        verify(exactly = 1) { provider.applyOptimisticRemoval("tt7", null, null) }
        coVerify(exactly = 1) { provider.removeProgress(PROFILE_ID, "tt7", null, null) }
        coVerify(exactly = 1) { prefs.saveProgress(save, PROFILE_ID) }
    }

    @Test
    fun `with Nuvio as source the tracker sees no progress calls`() = runTest {
        val (repository, router) = repository()
        repository.saveProgress(progress(MOVIE_ID, at = 1L), syncRemote = false)
        repository.removeProgress(MOVIE_ID)
        router.awaitQueuedWrites()
        verify(exactly = 0) { provider.applyOptimisticProgress(any()) }
        coVerify(exactly = 0) { provider.removeProgress(any(), any(), any(), any()) }
    }

    @Test
    fun `with a tracker as source, only sendable marks show at once, and a History removal drops its resume points`() = runTest {
        source.value = TrackingSource.MDBLIST
        val (repository, router) = repository()

        repository.markAsCompleted(progress(SERIES_ID, 1, 6, at = 200L))
        repository.markAsCompleted(progress(SERIES_ID, null, null, at = 201L))
        repository.removeFromHistory(SERIES_ID, contentType = "series")
        repository.removeFromHistory(SERIES_ID, season = 1, episode = 2)
        router.awaitQueuedWrites()

        verify(exactly = 1) { provider.applyOptimisticProgress(match { it.episode == 6 && it.progressPercent == 100f && it.lastWatched > 200L }) }
        verify(exactly = 1) { provider.applyOptimisticProgress(any()) }
        // The series row: its resume point goes (not watched history); the episode: unmark + resume point.
        coVerify(exactly = 1) { provider.removeProgress(PROFILE_ID, SERIES_ID, null, null) }
        coVerify(exactly = 1) { provider.removeProgress(PROFILE_ID, SERIES_ID, 1, 2) }
        verify(exactly = 1) { provider.applyOptimisticRemoval(SERIES_ID, null, null) }
        verify(exactly = 2) { provider.applyOptimisticRemoval(SERIES_ID, 1, 2) }
        assertEquals(listOf(2), removed.filter { it.first == TrackingProviderId.MDBLIST }.flatMap { it.second }.map { it.episode?.number })
    }

    @Test
    fun `batch marks and unmarks fan out per episode`() = runTest {
        val (repository, router) = repository()
        repository.markAsCompletedBatch(listOf(progress(SERIES_ID, 1, 1, at = 1L), progress(SERIES_ID, 1, 2, at = 1L)))
        repository.removeFromHistoryBatch(SERIES_ID, listOf(1 to 1, 1 to 2))
        router.awaitQueuedWrites()

        listOf(TrackingProviderId.MDBLIST, TrackingProviderId.SIMKL).forEach { id ->
            assertEquals(listOf(1, 2), added.single { it.first == id }.second.map { it.media.episode?.number })
            assertEquals(listOf(1, 2), removed.single { it.first == id }.second.map { it.episode?.number })
        }
    }

    @Test
    fun `with a tracker as source, Detail reads its episodes and watched state, plus retained local ones`() = runTest {
        source.value = TrackingSource.MDBLIST
        every { provider.episodeProgress(SERIES_ID) } returns MutableStateFlow(mapOf((1 to 4) to progress(SERIES_ID, 1, 4, at = 50L)))
        every { provider.isWatched(SERIES_ID, 1, 4) } returns MutableStateFlow(true)
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns MutableStateFlow(
            mapOf((1 to 4) to progress(SERIES_ID, 1, 4, at = 90L), (1 to 5) to progress(SERIES_ID, 1, 5, at = 100L))
        )
        every { prefs.getEpisodeProgress(SERIES_ID, 1, 4, any()) } returns MutableStateFlow(null)
        val (repository, _) = repository()

        // Same episode: the newer local entry; the local-only episode stays hidden (the tracker holds this show).
        assertEquals(mapOf((1 to 4) to 90L), repository.getAllEpisodeProgress(SERIES_ID).first().mapValues { it.value.lastWatched })
        assertTrue(repository.isWatched(SERIES_ID, 1, 4).first())
    }

    @Test
    fun `a local episode mark shows beside the tracker's only when the tracker's per-episode check keeps it`() = runTest {
        source.value = TrackingSource.MDBLIST
        every { provider.isWatched(SERIES_ID, 1, 6) } returns MutableStateFlow(false)
        every { prefs.getEpisodeProgress(SERIES_ID, 1, 6, any()) } returns MutableStateFlow(null)
        localWatched.value = listOf(WatchedItem(contentId = SERIES_ID, contentType = "series", title = SERIES_ID, season = 1, episode = 6, watchedAt = 77L))
        val asked = mutableListOf<Long>()
        every { provider.retainsLocalEpisode(SERIES_ID, 1, 6, any()) } answers { asked += arg<Long>(3); keep }
        val (repository, _) = repository()

        assertEquals(false, repository.isWatched(SERIES_ID, 1, 6).first())
        keep = true
        assertTrue(repository.isWatched(SERIES_ID, 1, 6).first())
        assertEquals(listOf(77L, 77L), asked)
    }

    private var keep = false

    @Test
    fun `a change of the tracker's keep answers re-runs the merge without new tracker data`() = runTest {
        source.value = TrackingSource.MDBLIST
        every { provider.episodeProgress(SERIES_ID) } returns MutableStateFlow(emptyMap())
        every { prefs.getAllEpisodeProgress(SERIES_ID, any()) } returns MutableStateFlow(mapOf((1 to 7) to progress(SERIES_ID, 1, 7, at = 70L)))
        every { provider.retainsLocalEpisode(SERIES_ID, 1, 7, 70L) } answers { keep }
        val (repository, _) = repository()
        val seen = mutableListOf<Set<Pair<Int, Int>>>()
        val job = backgroundScope.launch { repository.getAllEpisodeProgress(SERIES_ID).collect { seen += it.keys } }
        testScheduler.runCurrent()
        keep = true
        retention.emit(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(emptySet(), setOf(1 to 7)), seen.distinct())
        job.cancel()
    }

    @Test
    fun `isWatched counts a completed local play at its own time, and a started replay as not watched`() = runTest {
        source.value = TrackingSource.MDBLIST
        every { provider.isWatched(SERIES_ID, 1, 8) } returns MutableStateFlow(false)
        val local = MutableStateFlow<WatchProgress?>(progress(SERIES_ID, 1, 8, at = 88L))
        every { prefs.getEpisodeProgress(SERIES_ID, 1, 8, any()) } returns local
        val asked = mutableListOf<Long>()
        every { provider.retainsLocalEpisode(SERIES_ID, 1, 8, any()) } answers { asked += arg<Long>(3); true }
        val (repository, _) = repository()
        assertTrue(repository.isWatched(SERIES_ID, 1, 8).first()) // position 95 of 100: completed
        assertEquals(listOf(88L), asked)
        local.value = progress(SERIES_ID, 1, 8, at = 99L).copy(position = 10L)
        assertEquals(false, repository.isWatched(SERIES_ID, 1, 8).first())
    }

    private companion object {
        const val PROFILE_ID = 1
        const val MOVIE_ID = "tt0133093"
        const val SERIES_ID = "tt0903747"

        fun progress(id: String, season: Int? = null, episode: Int? = null, at: Long) = WatchProgress(
            contentId = id, contentType = if (season == null && id != SERIES_ID) "movie" else "series", name = id,
            poster = null, backdrop = null, logo = null, videoId = id, season = season, episode = episode,
            episodeTitle = null, position = 95L, duration = 100L, lastWatched = at
        )

        fun watched(id: String) = WatchedItem(contentId = id, contentType = "movie", title = id, watchedAt = 1L)
    }

    @Test
    fun `isWatched asks the keep check per local watch and follows its changes`() = runTest {
        source.value = TrackingSource.MDBLIST
        every { provider.isWatched(SERIES_ID, 1, 9) } returns MutableStateFlow(false)
        every { prefs.getEpisodeProgress(SERIES_ID, 1, 9, any()) } returns MutableStateFlow(progress(SERIES_ID, 1, 9, at = 95L))
        localWatched.value = listOf(WatchedItem(contentId = SERIES_ID, contentType = "series", title = SERIES_ID, season = 1, episode = 9, watchedAt = 40L))
        // The mark (40) was made before MDBList could hold it; the later completed play (95) was sent.
        var keepMark = true
        every { provider.retainsLocalEpisode(SERIES_ID, 1, 9, any()) } answers { arg<Long>(3) == 40L && keepMark }
        val (repository, _) = repository()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { repository.isWatched(SERIES_ID, 1, 9).collect { seen += it } }
        testScheduler.runCurrent()
        keepMark = false
        retention.emit(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(true, false), seen.distinct())
        job.cancel()
    }
}
