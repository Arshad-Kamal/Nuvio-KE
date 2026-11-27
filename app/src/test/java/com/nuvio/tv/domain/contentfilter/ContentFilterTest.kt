package com.nuvio.tv.domain.contentfilter

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 117 S1: the token matcher (P3). */
class ContentFilterTest {

    private fun tags(
        genres: Set<Int> = emptySet(),
        keywords: List<Pair<Int, String>> = emptyList(),
        language: String? = null,
        adult: Boolean = false
    ) = TitleTags(genres, keywords.map { it.first }.toCollection(LinkedHashSet()), keywords.map { it.second }, language, adult)

    private val sex = FilterPack("sexual_content", words = listOf(PackWord("sex", tagNameToo = false), PackWord("porn")), adult = true)
    private val lgbt = FilterPack("lgbtq", words = listOf(PackWord("gay"), PackWord("drag queen")))
    private val religion = FilterPack("religion", words = listOf(PackWord("christ"), PackWord("faith", textToo = false)), keywordIds = setOf(186))
    private val war = FilterPack("war", words = listOf(PackWord("war")), genreIds = setOf(10752), genreNames = setOf("War"))
    private val scary = FilterPack("scary_kids", words = listOf(PackWord("monster", textToo = false, tagNameToo = false)))
    private val crime = FilterPack("crime", words = listOf(PackWord("murder")), allowTitles = setOf("movie:8587"))
    private val anime = FilterPack("anime", japaneseAnimation = true, genreNames = setOf("Anime"))
    private val selfHarm = FilterPack("suicide", words = listOf(PackWord("self harm")))

    private val filter = ContentFilter(listOf(sex, lgbt, religion, war, scary, crime, anime, selfHarm))

    private fun hits(subject: FilterSubject) = filter.hits(subject)

    @Test
    fun `tokens split on spaces, hyphens, apostrophes and punctuation, in any case`() {
        assertEquals(listOf("self", "harm", "isn", "t", "ok", "l", "église", "2"), ContentFilter.tokenize("SELF-harm isn't OK; L'Église 2"))
        assertEquals(listOf("東京の夜"), ContentFilter.tokenize("東京の夜"))
    }

    @Test
    fun `a non-ASCII word matches whatever its case`() {
        val church = ContentFilter(listOf(FilterPack("religion", words = listOf(PackWord("église")))))
        assertEquals("religion", church.firstHit(FilterSubject("L'ÉGLISE du village")))
    }

    @Test
    fun `words match whole words only`() {
        assertEquals(emptyList<String>(), hits(FilterSubject("Essex Boys", description = "Christopher's sextant")))
        assertEquals(listOf("sexual_content"), hits(FilterSubject("Sex Education")))
        assertEquals(listOf("religion"), hits(FilterSubject("The Passion of the CHRIST")))
    }

    @Test
    fun `phrases match token by token across hyphens and spaces`() {
        assertEquals(listOf("suicide"), hits(FilterSubject("x", description = "A story of self-harm.")))
        assertEquals(listOf("lgbtq"), hits(FilterSubject("x", description = "A drag   queen's life")))
        assertEquals(emptyList<String>(), hits(FilterSubject("x", description = "The queen, in drag? No: harm to self")))
        assertEquals(emptyList<String>(), hits(FilterSubject("x", description = "ends with self")))
    }

    @Test
    fun `original title and description are searched, the text capped`() {
        assertEquals(listOf("lgbtq"), hits(FilterSubject("Plain", originalTitle = "Gay")))
        // "Plain " is 6 chars: over the cap, a word ending at 1,996 is kept, one starting at 2,006 dropped.
        assertEquals(listOf("lgbtq"), hits(FilterSubject("Plain", description = "a".repeat(1_986) + " gay " + "c".repeat(50))))
        assertEquals(emptyList<String>(), hits(FilterSubject("Plain", description = "a ".repeat(1_000) + "gay")))
        // A word cut by the cap is dropped whole, never matched as its stub.
        assertEquals(emptyList<String>(), hits(FilterSubject("Plain", description = "b".repeat(1_990) + " sextant")))
    }

    @Test
    fun `textToo false words skip the text but match keyword names`() {
        assertEquals(emptyList<String>(), hits(FilterSubject("A leap of faith")))
        assertEquals(listOf("religion"), hits(FilterSubject("x", tags = tags(keywords = listOf(1 to "loss of faith")))))
    }

    @Test
    fun `tagNameToo false words skip keyword-name tokens but still match a keyword with that whole name`() {
        assertEquals(emptyList<String>(), hits(FilterSubject("x", tags = tags(keywords = listOf(2 to "sex scandal")))))
        assertEquals(listOf("sexual_content"), hits(FilterSubject("x", tags = tags(keywords = listOf(3 to "sex")))))
    }

    @Test
    fun `a whole-tag-only word matches only a keyword of exactly that name`() {
        assertEquals(emptyList<String>(), hits(FilterSubject("Monster House", description = "a monster")))
        assertEquals(emptyList<String>(), hits(FilterSubject("x", tags = tags(keywords = listOf(4 to "giant monster")))))
        assertEquals(emptyList<String>(), hits(FilterSubject("x", tags = tags(keywords = listOf(5 to "monsters")))))
        assertEquals(listOf("scary_kids"), hits(FilterSubject("x", tags = tags(keywords = listOf(1299 to "Monster")))))
    }

    @Test
    fun `keyword names match pack words inside them`() {
        assertEquals(listOf("war"), hits(FilterSubject("x", tags = tags(keywords = listOf(6 to "civil war")))))
        assertEquals(listOf("crime"), hits(FilterSubject("x", tags = tags(keywords = listOf(7 to "murder investigation")))))
    }

    @Test
    fun `keyword ids, genre ids and addon genre names match`() {
        assertEquals(listOf("religion"), hits(FilterSubject("x", tags = tags(keywords = listOf(186 to "christianity")))))
        assertEquals(listOf("war"), hits(FilterSubject("x", tags = tags(genres = setOf(10752)))))
        assertEquals(listOf("war"), hits(FilterSubject("x", genreIds = setOf(10752))))
        assertEquals(listOf("war"), hits(FilterSubject("x", genreNames = listOf(" war "))))
    }

    @Test
    fun `adult flag and Japanese animation`() {
        assertEquals(listOf("sexual_content"), hits(FilterSubject("x", tags = tags(adult = true))))
        assertEquals(listOf("anime"), hits(FilterSubject("x", tags = tags(genres = setOf(16), language = "ja"))))
        assertEquals(emptyList<String>(), hits(FilterSubject("x", tags = tags(genres = setOf(16), language = "en"))))
        assertEquals(listOf("anime"), hits(FilterSubject("x", genreNames = listOf("Anime"))))
    }

    @Test
    fun `an allowlisted title is exempt from that pack only`() {
        val lionKing = FilterSubject(
            "The Lion King",
            description = "A murder, and a war.",
            tags = tags(keywords = listOf(9826 to "murder")),
            tmdbKey = "movie:8587"
        )
        assertEquals(listOf("war"), hits(lionKing))
        assertEquals(listOf("war", "crime"), hits(lionKing.copy(tmdbKey = "tv:8587")))
        assertEquals(listOf("war", "crime"), hits(lionKing.copy(tmdbKey = null)))
    }

    @Test
    fun `firstHit follows pack order, null when clean`() {
        val subject = FilterSubject("Gay porn")
        assertEquals("sexual_content", filter.firstHit(subject))
        assertEquals(null, filter.firstHit(FilterSubject("Paddington", description = "A bear in London.")))
    }

    @Test
    fun `my words are a pack like any other`() {
        val mine = ContentFilter(listOf(FilterPack("my_words", words = listOf(PackWord("dragon ball"), PackWord("wwe")))))
        assertEquals("my_words", mine.firstHit(FilterSubject("Dragon Ball Super: Broly")))
        assertEquals("my_words", mine.firstHit(FilterSubject("WWE SmackDown")))
        assertEquals(null, mine.firstHit(FilterSubject("The Wrestler")))
    }
}
