package com.nuvio.tv.core.contentfilter

import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.contentfilter.TitleTags
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 122 V2: one title's verdict from its own text and its cached tags. */
class TitleVerdictTest {
    private val crime = FilterPack("p12", words = listOf(PackWord("mafia")), allowTitles = setOf("movie:238"))
    private val war = FilterPack("p11", words = listOf(PackWord("war")))
    private val filter = ContentFilter(listOf(war, crime))

    private val states = HashMap<String, TagState>()
    private val aliases = HashMap<String, String>()
    private val lookup = mockk<TitleTagLookup> {
        every { state(any()) } answers { states[firstArg<TitleRef>().id] ?: TagState.WordsOnly }
        every { tmdbKey(any()) } answers {
            val ref = firstArg<TitleRef>()
            if (ref.id.startsWith("tmdb:")) "${ref.kind.key}:${ref.id.removePrefix("tmdb:")}" else aliases[ref.id]
        }
    }

    private fun item(id: String, name: String, type: ContentType = ContentType.MOVIE, genres: List<String> = emptyList()) =
        MetaPreview(
            id = id, type = type, name = name, poster = null, posterShape = PosterShape.POSTER, background = null,
            logo = null, description = null, releaseInfo = null, imdbRating = null, genres = genres
        )

    private fun tagged(vararg keywords: String) = TagState.Tagged(
        TitleTags(emptySet(), keywords.map { 9_000 + KEYWORD_IDS.indexOf(it) }.toSet(), keywords.toList(), "en", false)
    )

    @Test
    fun `a clean decided title is clean`() {
        assertEquals(TitleVerdict.Clean, lookup.verdictOf(filter, item("tt1", "Paddington")))
    }

    @Test
    fun `a clean title still waiting for tags is undecided`() {
        states["tt1"] = TagState.Undecided
        assertEquals(TitleVerdict.Undecided, lookup.verdictOf(filter, item("tt1", "Paddington")))
    }

    @Test
    fun `a word hit decides before the tags come`() {
        states["tt1"] = TagState.Undecided
        assertEquals(TitleVerdict.Hit(listOf("p11")), lookup.verdictOf(filter, item("tt1", "War Horse")))
    }

    @Test
    fun `every hitting pack is listed in pack order, tags included`() {
        states["tt1"] = tagged("mafia")
        assertEquals(TitleVerdict.Hit(listOf("p11", "p12")), lookup.verdictOf(filter, item("tt1", "War Games")))
    }

    @Test
    fun `an allowlisted pack's word hit waits while the IMDb id is unresolved`() {
        states["tt0068646"] = TagState.Undecided
        assertEquals(TitleVerdict.Undecided, lookup.verdictOf(filter, item("tt0068646", "Mafia Story")))
    }

    @Test
    fun `a tmdb id never waits for an alias`() {
        states["tmdb:500"] = TagState.Undecided
        assertEquals(TitleVerdict.Hit(listOf("p12")), lookup.verdictOf(filter, item("tmdb:500", "Mafia Story")))
    }

    @Test
    fun `a hit from a pack without an allowlist decides even while the alias is unresolved`() {
        states["tt0068646"] = TagState.Undecided
        assertEquals(
            TitleVerdict.Hit(listOf("p11", "p12")),
            lookup.verdictOf(filter, item("tt0068646", "Mafia War"))
        )
    }

    @Test
    fun `an allowlisted title is exempt from that pack only`() {
        aliases["tt0068646"] = "movie:238"
        states["tt0068646"] = tagged("mafia", "war")
        assertEquals(TitleVerdict.Hit(listOf("p11")), lookup.verdictOf(filter, item("tt0068646", "The Godfather")))
        states["tmdb:238"] = tagged("mafia")
        assertEquals(TitleVerdict.Clean, lookup.verdictOf(filter, item("tmdb:238", "The Godfather")))
    }

    @Test
    fun `a type with no TMDB namespace is judged on words and genres`() {
        states["ch1"] = TagState.Undecided // never read: a channel has no TitleRef
        assertEquals(TitleVerdict.Clean, lookup.verdictOf(filter, item("ch1", "Bluey", type = ContentType.CHANNEL)))
        assertEquals(
            TitleVerdict.Hit(listOf("p11")),
            lookup.verdictOf(filter, item("ch2", "War Diaries", type = ContentType.CHANNEL))
        )
    }

    private companion object {
        // One id per keyword name, as TMDB has: the matcher caches a keyword's name by its id.
        val KEYWORD_IDS = listOf("family", "zombie", "mafia", "war")
    }
}
