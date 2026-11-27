package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.contentfilter.ActiveFilter
import com.nuvio.tv.core.contentfilter.TagPriority
import com.nuvio.tv.core.contentfilter.TagState
import com.nuvio.tv.core.contentfilter.TitleRef
import com.nuvio.tv.core.contentfilter.TitleTagLookup
import com.nuvio.tv.domain.contentfilter.ContentFilter
import com.nuvio.tv.domain.contentfilter.FilterPack
import com.nuvio.tv.domain.contentfilter.PackWord
import com.nuvio.tv.domain.contentfilter.TitleTags
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 117 S8b (P8, M1 stages 7, 7b): what Detail's content-filter gate decides for a title. */
class DetailContentFilterGateTest {
    private val filter = ContentFilter(
        listOf(
            FilterPack("p5", words = listOf(PackWord("zombie"))),
            FilterPack("own:1", words = listOf(PackWord("night")))
        )
    )
    private val own = mapOf("own:1" to "Grandma's list")
    private var state: TagState = TagState.WordsOnly
    private val waits = mutableListOf<Triple<List<String>, TagPriority, Long>>()
    // A wait that ends with [afterWait] as the title's state (its tags arrived), or as it was (timeout).
    private var afterWait: TagState? = null
    private val lookup = mockk<TitleTagLookup> {
        // As the real lookup: an id TMDB has no namespace for is words-only (TitleTagLookupTest).
        every { state(any()) } answers { if (firstArg<TitleRef>().id.startsWith("kitsu:")) TagState.WordsOnly else state }
        every { tmdbKey(any()) } returns null
        every { retry(any()) } answers { retried += firstArg<TitleRef>().id }
        coEvery { awaitDecided(any(), any(), any()) } coAnswers {
            waits += Triple(firstArg<Collection<TitleRef>>().map { it.id }, secondArg(), thirdArg())
            afterWait?.let { state = it }
        }
    }

    private val retried = mutableListOf<String>()

    private fun gate(preview: Boolean = false) = DetailFilterGate.of(ActiveFilter(filter, preview, own), "Zayn")

    @Test
    fun `a words hit is blocked at once, naming the packs known without waiting for tags`() = runTest {
        state = TagState.Undecided
        assertEquals(
            DetailFilterBlock.Hidden("Zombie Night", "Zayn", listOf("p5", "own:1"), own),
            gate().judge(meta("Zombie Night"), lookup)
        )
        assertEquals("a hit by words decides without waiting", emptyList<Any>(), waits)
    }

    @Test
    fun `a clean title opens`() = runTest {
        assertNull(gate().judge(meta("Spring"), lookup))
    }

    @Test
    fun `previewing, a hit opens normally (122 V2), without waiting for tags that cannot change that`() = runTest {
        state = TagState.Undecided
        assertNull(gate(preview = true).judge(meta("Zombie Night"), lookup))
        assertEquals(emptyList<Any>(), waits)
    }

    @Test
    fun `tags that arrive during the wait and hit are the block`() = runTest {
        state = TagState.Undecided
        afterWait = TagState.Tagged(TitleTags(emptySet(), setOf(1), listOf("zombie"), "en", adult = false))
        assertEquals(listOf("p5"), (gate().judge(meta("Spring"), lookup) as DetailFilterBlock.Hidden).packIds)
    }

    @Test
    fun `every open forgets an untagged title's failed-fetch backoff before it waits`() = runTest {
        state = TagState.Undecided
        gate().judge(meta("Spring"), lookup)
        assertEquals(listOf("tt1"), retried)
        coVerifyOrder {
            lookup.retry(match { it.id == "tt1" })
            lookup.awaitDecided(any(), TagPriority.NOW, any())
        }
    }

    @Test
    fun `an allowlisted pack's words hit waits for the title's TMDB id, then counts if none exempts it`() = runTest {
        val allow = ContentFilter(listOf(FilterPack("p11", words = listOf(PackWord("war")), allowTitles = setOf("movie:1"))))
        state = TagState.Undecided
        val block = DetailFilterGate.of(ActiveFilter(allow, preview = false), "Zayn").judge(meta("War Games"), lookup)
        assertEquals(DetailFilterBlock.Unchecked("War Games", "Zayn"), block)
        assertEquals(1, waits.size)
    }

    @Test
    fun `a title with no TMDB namespace is judged on its words at once`() = runTest {
        state = TagState.Undecided
        assertEquals(listOf("p5"), (gate().judge(meta("Zombie Hunt", id = "kitsu:9"), lookup) as DetailFilterBlock.Hidden).packIds)
        assertNull(gate().judge(meta("Spring", id = "kitsu:9"), lookup))
        assertEquals(emptyList<Any>(), waits)
    }

    @Test
    fun `an untagged title waits first in line for 8 s, then opens when its tags come clean`() = runTest {
        state = TagState.Undecided
        afterWait = TagState.WordsOnly
        assertNull(gate().judge(meta("Spring"), lookup))
        assertEquals(listOf(Triple(listOf("tt1"), TagPriority.NOW, DetailFilterGate.BUDGET_MS)), waits)
    }

    @Test
    fun `still untagged after the wait is Couldn't check, previewing too (122 N7)`() = runTest {
        state = TagState.Undecided
        assertEquals(DetailFilterBlock.Unchecked("Spring", "Zayn"), gate().judge(meta("Spring"), lookup))
        assertEquals(DetailFilterBlock.Unchecked("Spring", "Zayn"), gate(preview = true).judge(meta("Spring"), lookup))
    }

    @Test
    fun `no filter opens everything, a broken pack file checks nothing`() = runTest {
        assertNull(DetailFilterGate.of(null, "Zayn").judge(meta("Zombie Night"), lookup))
        assertNull(DetailFilterGate.of(ActiveFilter(null, preview = true), "Zayn").judge(meta("Zombie Night"), lookup))
        assertEquals(DetailFilterBlock.Unchecked("Spring", null), DetailFilterGate.broken(null).judge(meta("Spring"), lookup))
    }

    private fun meta(name: String, id: String = "tt1") = Meta(
        id = id, type = ContentType.MOVIE, name = name, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), runtime = null, director = emptyList(), writer = emptyList(), cast = emptyList(),
        castMembers = emptyList(), videos = emptyList(), country = null, awards = null, language = null,
        links = emptyList()
    )
}
