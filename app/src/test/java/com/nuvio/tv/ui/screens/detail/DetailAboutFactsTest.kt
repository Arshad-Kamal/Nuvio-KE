package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaReleaseDate
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.search.DiscoverCatalog
import com.nuvio.tv.ui.screens.search.genreTarget
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** About sheet facts (Spec 26g): release country and rows, people, collection years, genre targets. */
class DetailAboutFactsTest {

    private val countries = listOf(
        MetaReleaseDateCountry("NL", listOf(MetaReleaseDate(releaseDate = "2012-09-26T00:00:00.000Z", type = 1))),
        MetaReleaseDateCountry("US", listOf(MetaReleaseDate(certification = "PG-13"))),
        MetaReleaseDateCountry(
            "CA",
            listOf(
                MetaReleaseDate(releaseDate = "2012-10-01T00:00:00.000Z", type = 4),
                MetaReleaseDate(releaseDate = "2012-09-28T00:00:00.000Z", type = 3, note = " Festival "),
                MetaReleaseDate(releaseDate = "2012-09-28T00:00:00.000Z", type = 3)
            )
        )
    )

    @Test
    fun `release country follows the region order, skips undated countries, else the first dated`() {
        assertEquals("CA", selectReleaseCountry(countries, listOf("CA", "US", "GB"))?.countryCode)
        // US has only a certification, no date: the next region (none) then the first dated country.
        assertEquals("NL", selectReleaseCountry(countries, listOf("US", "GB"))?.countryCode)
        assertNull(selectReleaseCountry(emptyList(), listOf("US")))
    }

    @Test
    fun `release rows are the written calendar dates, earliest first, one per type and date`() {
        val rows = aboutReleases(countries[2])
        assertEquals(
            listOf(
                AboutRelease(3, LocalDate.of(2012, 9, 28), "Festival"),
                AboutRelease(4, LocalDate.of(2012, 10, 1), null)
            ),
            rows
        )
        assertEquals(LocalDate.of(2012, 9, 26), releaseLocalDate("2012-09-26T00:00:00.000Z"))
        assertNull(releaseLocalDate("soon"))
    }

    @Test
    fun `people lead with creators directors writers then the cast, ids kept`() {
        val people = aboutPeople(
            meta(
                castMembers = listOf(
                    MetaCastMember("Derek", "Thom", tmdbId = 3),
                    MetaCastMember("Ian", "Writer", tmdbId = 1),
                    MetaCastMember("Ian", "Director", tmdbId = 1),
                    MetaCastMember("Vanja", "Celia")
                )
            )
        )
        assertEquals(listOf("Director", "Writer"), people.crew.map { it.role })
        assertEquals(listOf(1, 1), people.crew.map { it.tmdbId })
        assertEquals(listOf("Derek", "Vanja"), people.cast.map { it.name })
        assertEquals(listOf(3, null), people.cast.map { it.tmdbId })
    }

    @Test
    fun `addon name lists stand in without credits and carry no ids`() {
        val people = aboutPeople(meta(director = listOf("Ian"), writer = listOf("Ian"), cast = listOf("Derek", " ")))
        assertEquals(listOf(AboutPerson("Ian", "Director", null), AboutPerson("Ian", "Writer", null)), people.crew)
        assertEquals(listOf(AboutPerson("Derek", null, null)), people.cast)
    }

    @Test
    fun `collection years span the titles' years`() {
        assertEquals("2006\u20132023", collectionYears(listOf(preview("2023"), preview("2006-2008"), preview(null))))
        assertEquals("2010", collectionYears(listOf(preview("2010"))))
        assertNull(collectionYears(listOf(preview(null))))
    }

    @Test
    fun `genre target is the type's first catalog listing the genre, in the catalog's spelling`() {
        val catalogs = listOf(
            catalog("movie_top", "movie", emptyList()),
            catalog("series_genre", "series", listOf("Sci-Fi")),
            catalog("movie_genre", "movie", listOf("Action", "Sci-Fi"))
        )
        val target = catalogs.genreTarget("movie", "sci-fi")
        assertEquals("movie_genre", target?.catalog?.key)
        assertEquals("Sci-Fi", target?.genre)
        assertNull(catalogs.genreTarget("movie", "Drama"))
    }

    @Test
    fun `lead credits open the person page on their department, everyone else on Acting`() {
        assertEquals("Directing", personPageDepartment(" director "))
        assertEquals("Writing", personPageDepartment("Writer"))
        assertEquals("Creator", personPageDepartment("Creator"))
        assertEquals("Acting", personPageDepartment("Captain Celia"))
        assertEquals("Acting", personPageDepartment(null))
        assertEquals(R.string.detail_credit_directed_by, personChipSectionRes("Director"))
        assertEquals(R.string.detail_credit_written_by, personChipSectionRes("writer"))
        assertEquals(R.string.detail_credit_created_by, personChipSectionRes("Creator"))
        assertEquals(R.string.detail_section_cast, personChipSectionRes("Thom"))
    }

    private fun meta(
        castMembers: List<MetaCastMember> = emptyList(),
        director: List<String> = emptyList(),
        writer: List<String> = emptyList(),
        cast: List<String> = emptyList()
    ) = Meta(
        id = "tt1", type = ContentType.MOVIE, name = "Tears of Steel", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), runtime = null, director = director, writer = writer, cast = cast,
        castMembers = castMembers, videos = emptyList(), country = null, awards = null, language = null,
        links = emptyList()
    )

    private fun preview(releaseInfo: String?) = MetaPreview(
        id = "m", type = ContentType.MOVIE, name = "m", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = releaseInfo, imdbRating = null,
        genres = emptyList()
    )

    private fun catalog(key: String, type: String, genres: List<String>) = DiscoverCatalog(
        key = key, addonId = "a", addonName = "A", addonBaseUrl = "https://a", catalogId = key, catalogName = key,
        type = type, genres = genres, supportsSkip = false, skipStep = 100
    )
}
