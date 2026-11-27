package com.nuvio.tv.domain.contentfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 117 S1: the shipped pack file (P2). */
class PackFileTest {

    private val packs = BuiltInPacks.parse(File(ASSET).readText())

    @Test
    fun `eighteen packs with unique ids`() {
        assertEquals(18, packs.size)
        assertEquals(18, packs.map { it.id }.toSet().size)
    }

    @Test
    fun `every pack has a name and a description string`() {
        val strings = File(STRINGS).readText()
        packs.forEach { pack ->
            assertTrue(pack.id, "name=\"content_filter_pack_${pack.id}\"" in strings)
            assertTrue(pack.id, "name=\"content_filter_pack_${pack.id}_desc\"" in strings)
        }
    }

    @Test
    fun `words are lowercase, already tokenised, and listed once per pack`() {
        packs.forEach { pack ->
            val texts = pack.words.map { it.text }
            assertEquals(pack.id, texts.size, texts.toSet().size)
            texts.forEach { word ->
                assertEquals("${pack.id}: $word", word.lowercase(), word)
                assertEquals("${pack.id}: $word", ContentFilter.tokenize(word).joinToString(" "), word)
            }
        }
    }

    @Test
    fun `every pack can hit something`() {
        packs.forEach { pack ->
            assertTrue(
                pack.id,
                pack.words.isNotEmpty() || pack.genreIds.isNotEmpty() || pack.keywordIds.isNotEmpty() || pack.adult
            )
        }
    }

    @Test
    fun `every keyword id is listed once per pack and has a name for the pack's page (S7a)`() {
        assertEquals(116, packs.sumOf { it.keywordIds.size })
        packs.forEach { pack ->
            assertEquals(pack.id, pack.keywordIds.size, pack.keywordNames.size)
            pack.keywordNames.forEach { assertTrue("${pack.id}: '$it'", it.isNotBlank() && it == it.trim()) }
        }
        assertEquals(listOf(267122, 281741), packs.first { it.id == "sexual_content" }.keywordIds.take(2))
        assertEquals(listOf("sex", "nudity"), packs.first { it.id == "sexual_content" }.keywordNames.take(2))
    }

    @Test
    fun `a pack with TMDB genre ids names them, so its page shows its genres (P10)`() {
        packs.filter { it.genreIds.isNotEmpty() }.forEach { assertTrue(it.id, it.genreNames.size >= it.genreIds.size) }
    }

    @Test
    fun `the D30, D32 and D33 allowlists ship their titles`() {
        val byId = packs.associateBy { it.id }
        assertEquals(setOf("movie:8587"), byId.getValue("crime").allowTitles)
        assertEquals(setOf("movie:1381071", "movie:238"), byId.getValue("war").allowTitles)
        assertEquals(setOf("movie:28288"), byId.getValue("lgbtq").allowTitles)
        assertEquals(setOf("movie:238"), byId.getValue("religion").allowTitles)
        assertEquals(listOf("The Lion King (1994)"), byId.getValue("crime").allowTitleNames)
        assertEquals(2, byId.getValue("war").allowTitleNames.size)
    }

    @Test
    fun `decided word scopes hold (D23, D29, R9-M2, R10-m5)`() {
        fun word(pack: String, text: String) = packs.first { it.id == pack }.words.firstOrNull { it.text == text }
        assertEquals(PackWord("sex", textToo = true, tagNameToo = false), word("sexual_content", "sex"))
        packs.forEach { assertEquals(it.id, null, word(it.id, "sexual")) }
        assertEquals(PackWord("sexual abuse"), word("abuse", "sexual abuse"))
        assertEquals(PackWord("dating", textToo = false), word("romance", "dating"))
        assertEquals(PackWord("campaign", textToo = false, tagNameToo = true), word("politics", "campaign"))
        assertEquals(PackWord("monster", textToo = false, tagNameToo = false), word("scary_kids", "monster"))
        assertEquals(null, word("scary_kids", "monsters"))
        assertEquals(PackWord("murder"), word("crime", "murder"))
        assertEquals(PackWord("war"), word("war", "war"))
        assertEquals(null, word("religion", "christian"))
    }

    @Test
    fun `the shipped packs pass the E3 cases and the allowlist exempts by TMDB id`() {
        val filter = ContentFilter(packs)
        fun hits(name: String, description: String? = null, key: String? = null) =
            filter.hits(FilterSubject(name, description = description, tmdbKey = key))
        assertEquals(emptyList<String>(), hits("Essex Boys", "Two friends in Sussex find a sextant."))
        assertEquals(emptyList<String>(), hits("Godzilla"))
        assertEquals(emptyList<String>(), hits("Christopher Robin"))
        assertEquals(emptyList<String>(), hits("Star Wars"))
        assertEquals(emptyList<String>(), hits("Treasure", "A legend dating back to 1600, a haunting song."))
        assertEquals(listOf("crime"), hits("The Lion King", "A murder."))
        assertEquals(emptyList<String>(), hits("The Lion King", "A murder.", key = "movie:8587"))
        // D32, D33: allowlisted for religion and war; still hidden by crime
        assertEquals(listOf("religion", "war", "crime"), hits("The Godfather", "A mafia war, a church and a murder."))
        assertEquals(listOf("crime"), hits("The Godfather", "A mafia war, a church and a murder.", key = "movie:238"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an allowlist entry of an unknown kind is rejected`() {
        BuiltInPacks.parse(
            """{"packs":[{"id":"x","words":["a"],"allowTitles":[{"kind":"film","tmdbId":1,"name":"X"}]}]}"""
        )
    }

    companion object {
        // Unit tests run with the module directory as the working directory.
        const val ASSET = "src/main/assets/content_filter_packs.json"
        const val STRINGS = "src/main/res/values/strings.xml"
    }
}
