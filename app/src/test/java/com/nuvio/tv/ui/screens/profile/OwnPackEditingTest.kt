package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.domain.contentfilter.ContentFilterSettings
import com.nuvio.tv.domain.contentfilter.OwnPack
import com.nuvio.tv.domain.contentfilter.OwnTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 117 S7b: My words and own-pack editing rules (P9, M1 stages 3, 4, 4b, 4c). */
class OwnPackEditingTest {

    private val grandma = OwnPack(id = "own:g", name = "Grandma's list")

    @Test
    fun `commas add several words, trimmed, spaces collapsed, in order`() {
        assertEquals(listOf("wwe", "dragon ball", "big brother"), withAddedWords(listOf("wwe"), " dragon   ball ,big brother,"))
    }

    @Test
    fun `a word already listed or typed twice is dropped, case-insensitively`() {
        assertEquals(listOf("WWE", "bluey"), withAddedWords(listOf("WWE"), "wwe, bluey, Bluey"))
    }

    @Test
    fun `a part with no letter or digit never matches, so it is not added`() {
        assertEquals(emptyList<String>(), withAddedWords(emptyList(), " , !!, -- "))
    }

    @Test
    fun `a word is cut at the keyboard's limit`() {
        val added = withAddedWords(emptyList(), "a".repeat(FILTER_WORD_MAX + 5))
        assertEquals(listOf("a".repeat(FILTER_WORD_MAX)), added)
    }

    @Test
    fun `My words add and remove through the settings`() {
        val settings = ContentFilterSettings().addMyWords("wwe, dragon ball").removeMyWord("wwe")
        assertEquals(listOf("dragon ball"), settings.myWords)
    }

    @Test
    fun `a new own pack is listed last, switched on, under the own-pack prefix`() {
        val start = ContentFilterSettings(ownPacks = listOf(grandma), enabledPacks = setOf("horror"))
        val (next, id) = start.withNewOwnPack("  Wrestling  ", newId = "w")
        assertEquals("own:w", id)
        assertEquals(listOf("Grandma's list", "Wrestling"), next.ownPacks.map { it.name })
        assertEquals(setOf("horror", "own:w"), next.enabledPacks)
        assertTrue(next.ownPacks.last().hasOwnPackId)
    }

    @Test
    fun `a blank name makes no pack`() {
        val start = ContentFilterSettings()
        val (next, id) = start.withNewOwnPack("   ", newId = "x")
        assertNull(id)
        assertSame(start, next)
    }

    @Test
    fun `a pack name is cut at its limit`() {
        val (next, _) = ContentFilterSettings().withNewOwnPack("n".repeat(OWN_PACK_NAME_MAX + 3), newId = "x")
        assertEquals(OWN_PACK_NAME_MAX, next.ownPacks.single().name.length)
    }

    @Test
    fun `delete removes the pack and its switch, nothing else`() {
        val other = OwnPack(id = "own:o", name = "Other")
        val start = ContentFilterSettings(ownPacks = listOf(grandma, other), enabledPacks = setOf("own:g", "own:o", "horror"))
        val next = start.deleteOwnPack("own:g")
        assertEquals(listOf(other), next.ownPacks)
        assertEquals(setOf("own:o", "horror"), next.enabledPacks)
    }

    @Test
    fun `update changes only the named pack`() {
        val other = OwnPack(id = "own:o", name = "Other")
        val next = ContentFilterSettings(ownPacks = listOf(grandma, other)).updateOwnPack("own:o") { it.withWords("prank") }
        assertEquals(listOf(emptyList(), listOf("prank")), next.ownPacks.map { it.words })
    }

    @Test
    fun `a blank rename keeps the name`() {
        assertEquals("Grandma's list", grandma.renamed("  ").name)
        assertEquals("Nana", grandma.renamed(" Nana ").name)
    }

    @Test
    fun `words and tags add and remove, a tag held already is not added twice`() {
        val pack = grandma.withWords("prank, teen drama").withoutWord("prank")
            .withTag(OwnTag(1, "dating show")).withTag(OwnTag(1, "dating show")).withTag(OwnTag(2, "reality show"))
            .withoutTag(2)
        assertEquals(listOf("teen drama"), pack.words)
        assertEquals(listOf(OwnTag(1, "dating show")), pack.tags)
    }

    @Test
    fun `a genre flips on with its ids and names, and off again`() {
        val on = grandma.withGenreToggled(OwnPackGenre.REALITY)
        assertEquals(listOf(10764), on.genreIds)
        assertEquals(listOf("Reality", "Reality-TV"), on.genreNames)
        assertEquals(listOf(OwnPackGenre.REALITY), on.pickedGenres())
        val off = on.withGenreToggled(OwnPackGenre.REALITY)
        assertEquals(emptyList<Int>(), off.genreIds)
        assertEquals(emptyList<String>(), off.genreNames)
    }

    @Test
    fun `dropping one genre keeps the id and name another picked genre shares`() {
        val both = grandma.withGenreToggled(OwnPackGenre.ACTION).withGenreToggled(OwnPackGenre.ADVENTURE)
        val adventure = both.withGenreToggled(OwnPackGenre.ACTION)
        assertEquals(listOf(OwnPackGenre.ADVENTURE), adventure.pickedGenres())
        assertTrue(10759 in adventure.genreIds)
        assertTrue("Action & Adventure" in adventure.genreNames)
        assertFalse(28 in adventure.genreIds)
    }

    @Test
    fun `every genre has its own first id`() {
        val firsts = OwnPackGenre.entries.map { it.ids.first() }
        assertEquals(firsts.size, firsts.toSet().size)
    }

    @Test
    fun `a picked genre matches through the content filter`() {
        val pack = grandma.withGenreToggled(OwnPackGenre.SOAP).toFilterPack()
        assertEquals(setOf(10766), pack.genreIds)
        assertEquals(setOf("Soap"), pack.genreNames)
    }

    @Test
    fun `counts give words, picked genres and tags`() {
        val pack = grandma.withWords("a1, b2").withGenreToggled(OwnPackGenre.TALK).withTag(OwnTag(3, "talk show"))
        assertEquals(OwnPackCounts(words = 2, genres = 1, tags = 1), pack.counts())
    }

    @Test
    fun `tag matches keep TMDB's order, drop held ids, repeats and blank names, and stop at the limit`() {
        val pack = grandma.withTag(OwnTag(2, "reality show"))
        val results = listOf(OwnTag(1, "reality tv"), OwnTag(2, "reality show"), OwnTag(1, "reality tv"), OwnTag(4, " "), OwnTag(5, "virtual reality"))
        assertEquals(listOf(OwnTag(1, "reality tv"), OwnTag(5, "virtual reality")), tagMatches(results, pack))
        assertEquals(listOf(OwnTag(1, "reality tv")), tagMatches(results, pack, limit = 1))
    }
}
