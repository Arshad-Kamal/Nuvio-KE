package com.nuvio.tv.livetv.ui.search

import com.nuvio.tv.livetv.ui.search.LiveChannelSearch.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 111 S1: name matching (case/accent-insensitive) and ranking prefix > word start > substring. */
class LiveChannelSearchTest {

    private fun hit(name: String, query: String) =
        byName(listOf(name), query).singleOrNull()

    /** Names only (no numbers). */
    private fun byName(names: List<String>, query: String) =
        LiveChannelSearch.search(names, query, LiveChannelSearch::prepare) { null }

    /** "number name" pairs, searched by both. */
    private fun byBoth(vararg channels: Pair<String, String>, query: String): List<String> {
        val prepared = channels.associate { it to LiveChannelSearch.prepare(it.second) }
        return LiveChannelSearch.search(channels.toList(), query, { prepared.getValue(it) }, { it.first })
            .map { "${it.item.first} ${it.tier}" }
    }

    private fun tier(name: String, query: String): Tier? = hit(name, query)?.tier

    /** The original text a match highlights. */
    private fun highlighted(name: String, query: String): String? =
        hit(name, query)?.let { name.substring(LiveChannelSearch.highlight(name, it)) }

    @Test
    fun `case is ignored`() {
        assertEquals(Tier.PREFIX, tier("TV9 Bangla", "tv9"))
        assertEquals(Tier.WORD_START, tier("tv9 bangla", "BANG"))
    }

    @Test
    fun `accents are ignored both ways`() {
        assertEquals(Tier.PREFIX, tier("Télé-Québec", "tele"))
        assertEquals(Tier.WORD_START, tier("Tele Quebec", "québec"))
        assertEquals(Tier.PREFIX, tier("Ñuble TV", "nuble"))
    }

    @Test
    fun `decomposed accents stay with their letter in the highlight`() {
        // "é" written as e + U+0301 (as some playlists carry it).
        assertEquals("Te\u0301le\u0301", highlighted("Te\u0301le\u0301 Matin", "tele"))
    }

    @Test
    fun `dotted capital I folds to i`() {
        assertEquals("İstanbul", highlighted("İstanbul TV", "istanbul"))
    }

    @Test
    fun `compatibility forms fold`() {
        assertEquals("ᴴᴰ", highlighted("Sky ᴴᴰ", "hd"))
        assertEquals("ＣＮＮ", highlighted("ＣＮＮ", "cnn"))
    }

    @Test
    fun `tiers prefix then word start then substring`() {
        assertEquals(Tier.PREFIX, tier("News 24", "news"))
        assertEquals(Tier.WORD_START, tier("CBS News 24/7", "news"))
        assertEquals(Tier.WORD_START, tier("Zee-News", "news"))
        assertEquals(Tier.SUBSTRING, tier("Newsmax", "max"))
    }

    @Test
    fun `a later word start beats an earlier substring`() {
        assertEquals("Range", highlighted("Strange Range", "range"))
        assertEquals(Tier.WORD_START, tier("Strange Range", "range"))
        assertEquals(7..<10, hit("Banana Ana", "ana")?.let { LiveChannelSearch.highlight("Banana Ana", it) })
    }

    @Test
    fun `a word start is judged by code point`() {
        // An emoji (surrogate pair) before the word is not a letter: word start.
        assertEquals(Tier.WORD_START, tier("\uD83D\uDCFA News", "news"))
        assertEquals(Tier.WORD_START, tier("\uD83D\uDCFANews", "news"))
        // A spacing vowel sign (Devanagari Mc) belongs to the word before: substring.
        assertEquals(Tier.SUBSTRING, tier("आजतक", "तक"))
    }

    @Test
    fun `whitespace runs and non-breaking spaces count as one space`() {
        assertEquals("Zee  24\tTaas", highlighted("Zee  24\tTaas", " zee   24 taas "))
        assertEquals(Tier.PREFIX, tier("Zee\u00A024 Taas", "zee 24"))
        assertEquals(Tier.WORD_START, tier("  Zee 24 Taas  ", "taas"))
    }

    @Test
    fun `odd names never throw and match nothing they should not`() {
        for (name in listOf("", "   ", "\u0301", "\u0301\u0301 ", "\uD83D\uDCFA")) {
            assertNull(name, hit(name, "a"))
        }
        // A leading lone mark has no letter to join: the highlight starts at the word.
        assertEquals("News", highlighted("\u0301News", "news"))
        assertEquals("\uD83D\uDCFA", highlighted("TV \uD83D\uDCFA", "\uD83D\uDCFA"))
    }

    @Test
    fun `highlight covers the original characters`() {
        assertEquals("Québec", highlighted("Télé-Québec", "quebec"))
        assertEquals("TV9", highlighted("TV9 Bangla", "tv9"))
        assertEquals("max", highlighted("Newsmax", "max"))
    }

    @Test
    fun `search ranks by tier and keeps list order within one`() {
        val names = listOf("Zeenews", "Newsmax", "CBS News", "News 24", "BBC News", "Newsroom", "WION")
        val hits = byName(names, "news")
        assertEquals(listOf("Newsmax", "News 24", "Newsroom", "CBS News", "BBC News", "Zeenews"), hits.map { it.item })
        assertEquals(
            listOf(Tier.PREFIX, Tier.PREFIX, Tier.PREFIX, Tier.WORD_START, Tier.WORD_START, Tier.SUBSTRING),
            hits.map { it.tier }
        )
    }

    @Test
    fun `no match and blank query give nothing`() {
        assertNull(hit("WION", "tv9"))
        assertTrue(byName(listOf("WION"), "").isEmpty())
        assertTrue(byName(listOf("WION"), " \u00A0 ").isEmpty())
    }

    @Test
    fun `digits find the exact number, then numbers starting with them, then names`() {
        val hits = byBoth(
            "7060" to "Zee Cinema", "24" to "Star 706 Plus", "706" to "Sony", "7069" to "WION", "9" to "706 Music",
            query = "706"
        )
        assertEquals(
            listOf("706 NUMBER_EXACT", "7060 NUMBER_PREFIX", "7069 NUMBER_PREFIX", "9 PREFIX", "24 WORD_START"),
            hits
        )
    }

    @Test
    fun `number prefixes keep list order, not numeric order`() {
        assertEquals(
            listOf("7069 NUMBER_PREFIX", "7060 NUMBER_PREFIX"),
            byBoth("7069" to "WION", "7060" to "Zee Cinema", query = "706")
        )
    }

    @Test
    fun `numbers compare as the lineup words them`() {
        // tvg-chno values: "5" is a prefix of "5.1" but not of "05"; "101a" starts with "101".
        assertEquals(listOf("5.1 NUMBER_PREFIX"), byBoth("05" to "Alpha", "5.1" to "Beta", query = "5"))
        assertEquals(listOf("05 NUMBER_EXACT"), byBoth("05" to "Alpha", "5" to "Beta", query = "05"))
        assertEquals(listOf("101a NUMBER_PREFIX"), byBoth("101a" to "Gamma", query = "101"))
    }

    @Test
    fun `digits with an inner space are not a number`() {
        assertEquals(listOf("24 PREFIX"), byBoth("706" to "Sony", "24" to "70 6 Live", query = "70 6"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highlight is for name matches only`() {
        val hit = LiveChannelSearch.search(listOf("WION"), "7069", LiveChannelSearch::prepare) { "7069" }.single()
        LiveChannelSearch.highlight("WION", hit)
    }

    @Test
    fun `a channel counts once at its best tier`() {
        // "24" is both its number and in its name: listed once, as the number.
        assertEquals(listOf("24 NUMBER_EXACT"), byBoth("24" to "News 24", query = "24"))
    }

    @Test
    fun `numbers are matched only for a digits-only query`() {
        // "tv9" holds a digit but is not a number: names only.
        assertEquals(listOf("6391 PREFIX"), byBoth("6391" to "TV9 Bangla", "9" to "WION", query = "tv9"))
        // Spaces around digits are trimmed; full-width digits fold to ASCII.
        assertEquals(listOf("9 NUMBER_EXACT"), byBoth("9" to "WION", query = " ９ "))
    }

    @Test
    fun `a channel without a number is still found by name`() {
        val prepared = LiveChannelSearch.prepare("706 Music")
        val hits = LiveChannelSearch.search(listOf("x"), "706", { prepared }) { null }
        assertEquals(listOf(Tier.PREFIX), hits.map { it.tier })
    }
}
