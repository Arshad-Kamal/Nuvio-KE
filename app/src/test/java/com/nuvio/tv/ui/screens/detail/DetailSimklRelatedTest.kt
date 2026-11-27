package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.FakeTrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.local.ContinueWatchingSettingsDataStore
import com.nuvio.tv.data.simkl.RelatedTitlesSource
import com.nuvio.tv.data.simkl.SimklAnimeSeasons
import com.nuvio.tv.data.simkl.SimklRelated
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 106 S4b: which More like this list the detail page asks Simkl for, and when it does not. */
class DetailSimklRelatedTest {

    private val source = MutableStateFlow(RelatedTitlesSource.SIMKL)
    private val simkl = FakeTrackingProvider(TrackingProviderId.SIMKL, connected = true)
    private val related = mockk<SimklRelated>()
    private val card = MetaPreview(
        id = "tt0113277", type = ContentType.MOVIE, name = "Heat", poster = null, posterShape = PosterShape.LANDSCAPE,
        background = null, logo = null, description = null, releaseInfo = "1995", imdbRating = null, genres = emptyList()
    )

    private val loader = DetailSimklRelated(
        settings = mockk<ContinueWatchingSettingsDataStore> {
            every { relatedTitlesSource } returns source
            every { activeSimklAnimeSeasons } returns flowOf(SimklAnimeSeasons.PER_SEASON)
        },
        registry = TrackingProviderRegistry(setOf(simkl)),
        related = related,
        timeoutMs = 1_000L
    )

    private fun meta(id: String, imdbId: String? = null, type: ContentType = ContentType.MOVIE) = Meta(
        id = id, type = type, name = "Ronin", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), runtime = null, director = emptyList(), writer = emptyList(), cast = emptyList(),
        castMembers = emptyList(), videos = emptyList(), country = null, awards = null, language = null,
        links = emptyList(), imdbId = imdbId
    )

    @Test
    fun `Simkl chosen and connected asks for the title with the profile's anime mode`() = runTest {
        coEvery { related.related("kitsu:41370", "series", SimklAnimeSeasons.PER_SEASON) } returns listOf(card)
        assertEquals(listOf(card), loader.load(meta("kitsu:41370", type = ContentType.SERIES)))
    }

    @Test
    fun `TMDB chosen, or Simkl signed out, is not Simkl's to answer`() = runTest {
        source.value = RelatedTitlesSource.TMDB
        assertNull(loader.load(meta("tt0122690")))
        source.value = RelatedTitlesSource.SIMKL
        simkl.authenticated.value = false
        assertNull(loader.load(meta("tt0122690")))
        coVerify(exactly = 0) { related.related(any(), any(), any(), any()) }
    }

    @Test
    fun `an addon's own id falls back to the title's IMDb id`() = runTest {
        coEvery { related.related("tt0122690", "movie", any()) } returns listOf(card)
        assertEquals(listOf(card), loader.load(meta("myaddon:ronin", imdbId = "tt0122690")))
    }

    @Test
    fun `an id Simkl can look up wins over the IMDb id, an mdblist id does not`() = runTest {
        coEvery { related.related("tmdb:603", "movie", any()) } returns listOf(card)
        coEvery { related.related("tt0133093", "movie", any()) } returns emptyList()
        assertEquals(listOf(card), loader.load(meta("tmdb:603", imdbId = "tt0133093")))
        assertEquals(emptyList<MetaPreview>(), loader.load(meta("mdblist:x", imdbId = "tt0133093")))
        coVerify(exactly = 0) { related.related("mdblist:x", any(), any(), any()) }
    }

    @Test
    fun `Simkl taking too long gives an empty list, so TMDB's shows instead`() = runTest {
        coEvery { related.related(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        assertEquals(emptyList<MetaPreview>(), loader.load(meta("tt0122690")))
    }
}
