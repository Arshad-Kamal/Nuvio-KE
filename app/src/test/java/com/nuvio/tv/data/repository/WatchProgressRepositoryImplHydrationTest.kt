package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B17/B18: progress artwork hydration claims its batch up front and merges into fresh state. */
class WatchProgressRepositoryImplHydrationTest {

    private val activeProfileIdFlow = MutableStateFlow(PROFILE_ID)
    private val profileManager = mockk<ProfileManager> {
        every { activeProfileId } returns activeProfileIdFlow
    }
    private val prefs = mockk<WatchProgressPreferences>(relaxed = true)
    private val metaRepository = mockk<MetaRepository>(relaxed = true)

    init {
        prefs.injectProfileManager(profileManager)
    }

    private val repository = WatchProgressRepositoryImpl(
        watchProgressPreferences = prefs,
        continueWatchingSettings = mockk<ContinueWatchingSettingsDataStore>(relaxed = true),
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
        watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
        watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
        watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
        authManager = mockk<AuthManager>(relaxed = true),
        metaRepository = metaRepository,
        tmdbService = mockk<TmdbService>(relaxed = true),
        profileManager = profileManager,
        tvRecommendationManager = mockk<TvRecommendationManager>(relaxed = true)
    )

    @Test
    fun `planner skips claimed and artwork-bearing entries and caps the batch`() {
        val items = (0 until 12).map { progress("tt$it") } +
            progress("ttArt").copy(poster = "p")

        val batch = planProgressArtworkHydrationBatch(
            items, claims = setOf(progressHydrationClaim(PROFILE_ID, "tt0")), profileId = PROFILE_ID
        )

        assertEquals(10, batch.size)
        assertEquals("tt1", batch.first().contentId)
        assertTrue(batch.none { it.contentId == "tt0" || it.contentId == "ttArt" })
    }

    @Test
    fun `another profile's claim does not block this profile's entry`() {
        val batch = planProgressArtworkHydrationBatch(
            listOf(progress("tt0")), claims = setOf(progressHydrationClaim(PROFILE_ID, "tt0")), profileId = 2
        )

        assertEquals(listOf("tt0"), batch.map { it.contentId })
    }

    @Test
    fun `the same title is hydrated again for a second profile`() = runTest {
        val snapshot = progress(FIRST_ID)
        every { prefs.allProgress } returns flowOf(listOf(snapshot))
        every { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) } returns
            flowOf(NetworkResult.Success(meta(FIRST_ID)))

        repository.allProgress.first()
        coVerify(timeout = TIMEOUT_MS, exactly = 1) {
            prefs.mergeHydratedArtwork(snapshot, any(), any(), any(), any(), any(), PROFILE_ID)
        }
        activeProfileIdFlow.value = 2
        repository.allProgress.first()

        coVerify(timeout = TIMEOUT_MS, exactly = 1) {
            prefs.mergeHydratedArtwork(snapshot, any(), any(), any(), any(), any(), 2)
        }
        verify(exactly = 2) { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) }
    }

    @Test
    fun `hydration merges into the profile that claimed it even after a switch`() = runTest {
        val snapshot = progress(FIRST_ID)
        every { prefs.allProgress } returns flowOf(listOf(snapshot))
        val gate = CompletableDeferred<Unit>()
        every { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) } returns flow {
            gate.await()
            emit(NetworkResult.Success(meta(FIRST_ID)))
        }

        repository.allProgress.first()
        verify(timeout = TIMEOUT_MS, exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) }
        activeProfileIdFlow.value = 2
        gate.complete(Unit)

        coVerify(timeout = TIMEOUT_MS, exactly = 1) {
            prefs.mergeHydratedArtwork(snapshot, any(), any(), any(), any(), any(), PROFILE_ID)
        }
        coVerify(exactly = 0) { prefs.mergeHydratedArtwork(any(), any(), any(), any(), any(), any(), 2) }
    }

    @Test
    fun `re-emission during hydration does not refetch entries still queued in the batch`() = runTest {
        val first = progress(FIRST_ID)
        val second = progress(SECOND_ID)
        every { prefs.allProgress } returns flowOf(listOf(first, second))
        val gate = CompletableDeferred<Unit>()
        every { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) } returns flow {
            gate.await()
            emit(NetworkResult.Error("no meta"))
        }
        every { metaRepository.getMetaFromPrimaryAddon("movie", SECOND_ID) } returns
            flowOf(NetworkResult.Error("no meta"))

        repository.allProgress.first()
        verify(timeout = TIMEOUT_MS, exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) }
        // The first entry's fetch is in flight; the second is queued in the same batch.
        repository.allProgress.first()
        gate.complete(Unit)

        verify(timeout = TIMEOUT_MS, exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", SECOND_ID) }
        Thread.sleep(SETTLE_MS)
        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) }
        verify(exactly = 1) { metaRepository.getMetaFromPrimaryAddon("movie", SECOND_ID) }
    }

    @Test
    fun `hydration merges into the stored entry instead of saving the launch-time snapshot`() = runTest {
        val snapshot = progress(FIRST_ID)
        every { prefs.allProgress } returns flowOf(listOf(snapshot))
        every { metaRepository.getMetaFromPrimaryAddon("movie", FIRST_ID) } returns
            flowOf(NetworkResult.Success(meta(FIRST_ID)))

        repository.allProgress.first()

        coVerify(timeout = TIMEOUT_MS, exactly = 1) {
            prefs.mergeHydratedArtwork(snapshot, "meta-poster", any(), "meta-logo", "Meta Name", 45 * 60_000L, PROFILE_ID)
        }
        coVerify(exactly = 0) { prefs.saveProgress(any(), any()) }
    }

    private fun progress(contentId: String) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 0L,
        lastWatched = 1L
    )

    private fun meta(id: String) = Meta(
        id = id,
        type = ContentType.MOVIE,
        name = "Meta Name",
        poster = "meta-poster",
        posterShape = PosterShape.POSTER,
        background = "meta-backdrop",
        logo = "meta-logo",
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        runtime = "45",
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        links = emptyList(),
        country = null,
        awards = null,
        language = null
    )

    private companion object {
        const val PROFILE_ID = 1
        const val FIRST_ID = "tt0000011"
        const val SECOND_ID = "tt0000012"
        const val TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 300L
    }
}
