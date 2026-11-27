package com.nuvio.tv.data.repository

import com.nuvio.tv.core.contentfilter.ActiveContentFilter
import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleRef
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/** A [CatalogContentFilter] for a profile with no content filters: every gate is skipped. */
fun noContentFilter(): CatalogContentFilter =
    CatalogContentFilter(mockk<ActiveContentFilter> { coEvery { current() } returns null }, mockk(), { 0L })

/**
 * A real [CatalogContentFilter] over [packs] (Spec 117 S5 tests): tag state per title id (default
 * words-only), every wait recorded as (ids in queue order, budget), [onWait] run inside a wait.
 */
class GateFixture(packs: List<FilterPack>, preview: Boolean = false) {
    val states = HashMap<String, TagState>()
    val waits = mutableListOf<Pair<List<String>, Long>>()
    var onWait: () -> Unit = {}

    private val lookup = mockk<TitleTagLookup> {
        every { state(any()) } answers { states[firstArg<TitleRef>().id] ?: TagState.WordsOnly }
        every { tmdbKey(any()) } returns null
        coEvery { awaitDecided(any(), any(), any()) } coAnswers {
            waits += firstArg<Collection<TitleRef>>().map { it.id } to thirdArg<Long>()
            onWait()
        }
    }
    private val active = ActiveFilter(ContentFilter(packs), preview = preview)

    val contentFilter =
        CatalogContentFilter(mockk<ActiveContentFilter> { coEvery { current() } returns active }, lookup) { 0L }

    suspend fun gate(): CatalogContentFilter.Gate = checkNotNull(contentFilter.open())
}

/** A TMDB-style preview (`tmdb:<id>`) as the list sources build them. */
fun tmdbPreview(
    id: Int,
    name: String,
    type: ContentType = ContentType.MOVIE,
    genreIds: List<Int> = emptyList(),
    adult: Boolean = false
) = MetaPreview(
    id = "tmdb:$id", type = type, name = name, poster = null, posterShape = PosterShape.POSTER, background = null,
    logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
    tmdbGenreIds = genreIds, tmdbAdult = adult
)
