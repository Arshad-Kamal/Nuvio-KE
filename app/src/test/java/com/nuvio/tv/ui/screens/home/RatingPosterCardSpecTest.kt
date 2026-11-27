package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import com.nuvio.tv.core.posters.RatingPosterFailure
import com.nuvio.tv.core.posters.RatingPosterFailureMemory
import com.nuvio.tv.core.posters.RatingPosterFallback
import com.nuvio.tv.core.posters.RatingPosterState
import com.nuvio.tv.core.posters.RatingPosterTemplate
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.NextUpInfo
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.theme.NuvioMarquee
import com.nuvio.tv.ui.util.asStable
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 114 P0-2..P0-4, P0-6: which cards ask for a rating poster, and how. */
class RatingPosterCardSpecTest {

    private val home = NuvioMarquee.tokens.home
    private val failures = RatingPosterFailureMemory()
    private val template = (RatingPosterTemplate.parse(
        "https://pp.example.com/poster?imdb_id={imdb_id?}&tmdb_id={tmdb_id?}&type={type}&access_key=SECRET"
    ) as RatingPosterTemplate.Parsed.Valid).template
    private val on = RatingPosterState.On(template)
    private val ratingUrl = "https://pp.example.com/poster?imdb_id=tt1254207&tmdb_id=&type=movie&access_key=SECRET"

    private val catalog = CatalogRow(
        addonId = "a", addonName = "A", addonBaseUrl = "https://a.example", catalogId = "top",
        catalogName = "Top", type = ContentType.MOVIE, items = emptyList()
    )

    private fun card(
        id: String = "tt1254207",
        shape: PosterShape = PosterShape.POSTER,
        type: ContentType = ContentType.MOVIE,
        poster: String? = "https://img/$id.jpg",
        imdbId: String? = null,
    ) = buildCatalogItem(
        MetaPreview(
            id = id, type = type, name = "Title", poster = poster, posterShape = shape,
            background = "https://img/bg.jpg", logo = null, description = null, releaseInfo = null,
            imdbRating = null, genres = emptyList(), imdbId = imdbId
        ),
        catalog, useLandscapePosters = false, occurrence = 0
    )

    private fun ModernCarouselItem.spec(state: RatingPosterState, landscape: Boolean = false, cwPoster: Boolean = false) =
        marqueeImageRequestSpec(landscape, cwPoster, false, false, home, state, failures)

    @Test
    fun `off leaves every card as it was`() {
        val spec = card().spec(RatingPosterState.Off)!!
        assertEquals("https://img/tt1254207.jpg", spec.url)
        assertNull(spec.fallback)
        assertFalse(spec.pending)
    }

    @Test
    fun `a poster card gets the rating poster, its addon art as the fallback`() {
        val spec = card().spec(on)!!
        assertEquals(ratingUrl, spec.url)
        assertEquals("https://img/tt1254207.jpg", spec.fallback?.url)
        assertEquals(template.fingerprint, spec.templateFingerprint)
        // Same recipe as a catalog card, so card and prefetch keys agree.
        assertEquals("${ratingUrl}_100x150", spec.cacheKey(100, 150))
        assertEquals("https://img/tt1254207.jpg_100x150", spec.fallback?.cacheKey(100, 150))
    }

    @Test
    fun `tv types count as series, other types keep their art`() {
        assertTrue(card(id = "tmdb:1396", type = ContentType.TV).spec(on)!!.url.contains("type=series"))
        assertTrue(card(id = "tmdb:1396", type = ContentType.SERIES).spec(on)!!.url.contains("tmdb_id=1396"))
        assertNull(card(type = ContentType.CHANNEL).spec(on)!!.fallback)
        assertFalse(card(type = ContentType.CHANNEL).spec(RatingPosterState.Loading)!!.pending)
    }

    @Test
    fun `landscape art, a landscape row, a backdrop pick and id-less cards keep their art`() {
        assertNull(card(shape = PosterShape.LANDSCAPE).spec(on)!!.fallback)
        assertNull(card(shape = PosterShape.SQUARE).spec(on)!!.fallback)
        assertNull(card().spec(on, landscape = true)!!.fallback)
        // No poster: the card shows its backdrop, never a rating poster.
        assertEquals("https://img/bg.jpg", card(poster = null).spec(on)!!.url)
        assertNull(card(id = "ctmdb.collection:10").spec(on)!!.fallback)
        assertFalse(card(id = "ctmdb.collection:10").spec(RatingPosterState.Loading)!!.pending)
    }

    @Test
    fun `an explicit imdb id serves a card with another id`() {
        val spec = card(id = "kitsu:7442", imdbId = "tt2560140").spec(on)!!
        assertTrue(spec.url.contains("imdb_id=tt2560140"))
    }

    @Test
    fun `while the setting loads a card that could qualify waits`() {
        val spec = card().spec(RatingPosterState.Loading)!!
        assertTrue(spec.pending)
        assertEquals("https://img/tt1254207.jpg", spec.url)
    }

    @Test
    fun `a known-bad rating url or a rejected key gives the plain addon spec`() {
        failures.record(ratingUrl, template.fingerprint, RatingPosterFailure.TERMINAL, template.fingerprint)
        assertEquals("https://img/tt1254207.jpg", card().spec(on)!!.url)
        val rejected = RatingPosterFailureMemory()
        rejected.record("https://pp.example.com/x", template.fingerprint, RatingPosterFailure.UNAUTHORIZED, template.fingerprint)
        val spec = card(id = "tt0000001").marqueeImageRequestSpec(false, false, false, false, home, on, rejected)!!
        assertEquals("https://img/tt0000001.jpg", spec.url)
        assertNull(spec.fallback)
    }

    private fun continueWatching(poster: String?, backdrop: String? = "https://img/cw-bg.jpg") = ModernCarouselItem(
        key = "cw",
        title = "Show",
        subtitle = null,
        imageUrl = poster,
        heroPreview = HeroPreview(
            title = "Show", logo = null, description = null, episodeLabel = null, yearText = null, imdbText = null,
            genres = emptyList<String>().asStable(), poster = poster, backdrop = backdrop, imageUrl = poster
        ),
        payload = ModernPayload.ContinueWatching(
            ContinueWatchingItem.InProgress(
                WatchProgress(
                    contentId = "tt0944947", contentType = "series", name = "Show", poster = poster,
                    backdrop = backdrop, logo = null, videoId = "tt0944947:3:9", season = 3, episode = 9,
                    episodeTitle = null, position = 1_000L, duration = 10_000L, lastWatched = 0L
                )
            )
        )
    )

    private fun nextUp(poster: String?) = continueWatching(poster).copy(
        payload = ModernPayload.ContinueWatching(
            ContinueWatchingItem.NextUp(
                NextUpInfo(
                    contentId = "tmdb:1396", contentType = "series", name = "Show", poster = poster,
                    backdrop = "https://img/cw-bg.jpg", logo = null, videoId = "tmdb:1396:5:1", season = 5, episode = 1,
                    episodeTitle = null, thumbnail = null, lastWatched = 0L, sortTimestamp = 0L
                )
            )
        )
    )

    @Test
    fun `a poster-style continue watching card showing the entry's poster gets the rating poster`() {
        val spec = continueWatching("https://img/cw.jpg").spec(on, cwPoster = true)!!
        assertTrue(spec.continueWatching)
        assertTrue(spec.url.startsWith("https://pp.example.com/poster?imdb_id=tt0944947"))
        assertEquals("https://img/cw.jpg", spec.fallback?.url)
        // A failed load blacklists the addon's poster, so the chain moves on to the backdrop.
        assertEquals("https://img/cw.jpg", spec.addonUrl)
        assertEquals("https://img/cw.jpg_100x150_blurfalse", spec.fallback?.cacheKey(100, 150))
        assertTrue(continueWatching("https://img/cw.jpg").spec(RatingPosterState.Loading, cwPoster = true)!!.pending)
        assertTrue(nextUp("https://img/nu.jpg").spec(on, cwPoster = true)!!.url.contains("tmdb_id=1396"))
        // The card style uses backdrops: unchanged.
        assertNull(continueWatching("https://img/cw.jpg").spec(on, cwPoster = false)!!.fallback)
        // No poster: the backdrop pick is never swapped.
        assertNull(continueWatching(null).spec(on, cwPoster = true)!!.fallback)
    }

    @Test
    fun `folder tiles keep their cover`() {
        val folder = buildCollectionFolderItem(
            Collection(id = "c", title = "C", folders = emptyList()),
            CollectionFolder(id = "f", title = "F", coverImageUrl = "https://img/cover.jpg")
        )
        assertNull(folder.spec(on)!!.fallback)
        assertFalse(folder.spec(RatingPosterState.Loading)!!.pending)
    }

    @Test
    fun `a plain spec's addon url is its own`() {
        assertEquals("https://img/tt1254207.jpg", card().spec(RatingPosterState.Off)!!.addonUrl)
    }

    @Test
    fun `prefetch never loads a pending card`() {
        val loader = mockk<ImageLoader>(relaxed = true)
        enqueueMarqueeImage(loader, mockk(relaxed = true), Density(1f), card().spec(RatingPosterState.Loading)!!)
        verify(exactly = 0) { loader.enqueue(any()) }
    }

    @Test
    fun `the spec never prints the address`() {
        val text = card().spec(on)!!.toString()
        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("host=pp.example.com"))
    }

    @Test
    fun `the request carries the fallback extra, the plain one none`() {
        val context = mockk<Context>(relaxed = true)
        val request = card().spec(on)!!.request(context, 100, 150).build()
        val fallback = request.extras[RatingPosterFallback.KEY]!!
        assertEquals("https://img/tt1254207.jpg", fallback.url)
        assertEquals("https://img/tt1254207.jpg_100x150", fallback.memoryCacheKey)
        assertEquals(template.fingerprint, fallback.fingerprint)
        assertNull(card().spec(RatingPosterState.Off)!!.request(context, 100, 150).build().extras[RatingPosterFallback.KEY])
    }
}
