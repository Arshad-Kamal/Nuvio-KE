package com.nuvio.tv.domain.contentfilter

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Spec 117 S1 stop bars (a), (b), (d) on real TMDB data: the F1 sample, fetched by TMDB id into a local
 * fixture that never enters the repo (TMDB terms; it carries overviews). Runs only when the environment
 * variable SPEC117_F1_FIXTURE names that file; elsewhere it is skipped.
 */
class F1SampleTest {

    @Serializable
    private data class Sample(
        val exp: String,
        val kind: String,
        val tmdbId: Int,
        val title: String,
        val originalTitle: String? = null,
        val overview: String = "",
        val genreIds: List<Int> = emptyList(),
        val genreNames: List<String> = emptyList(),
        val language: String? = null,
        val adult: Boolean = false,
        val keywords: List<Keyword> = emptyList(),
        val year: String = ""
    )

    @Serializable
    private data class Keyword(val id: Int, val name: String)

    private val packIds = listOf(
        "sexual_content", "lgbtq", "religion", "occult", "horror", "violence", "drugs", "suicide", "abuse",
        "politics", "war", "crime", "romance", "reality_tv", "anime", "sports_awards", "gambling", "scary_kids"
    )

    private fun expectedPack(exp: String): String? =
        if (exp == "-") null else packIds[exp.substringBefore(' ').removePrefix("P").toInt() - 1]

    @Test
    fun `the F1 sample meets the S1 bars`() {
        val path = System.getenv("SPEC117_F1_FIXTURE")
        assumeTrue("SPEC117_F1_FIXTURE not set", !path.isNullOrBlank() && File(path).exists())
        val samples = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<Sample>>(File(path!!).readText())
        val filter = ContentFilter(BuiltInPacks.parse(File(PackFileTest.ASSET).readText()))
        val newData = setOf("Silence", "Room") // F1's wrong matches, re-run as new data outside bar (a)
        val family = setOf(
            "Christopher Robin", "Toy Story 5", "Frozen", "Paddington", "Bluey", "Inside Out", "Moana",
            "Finding Nemo", "Encanto", "Kung Fu Panda", "Minions & Monsters", "The Lion King"
        )
        // The Godfather: "war" (D32, keyword "mafia war") and "religion" (D33, keyword "religion") allowlisted; stays a P12 Crime hit.
        val fixed = mapOf(
            "Fifty Shades of Grey" to setOf("religion"), "The Godfather" to setOf("politics", "war", "religion"),
            "Spotlight" to setOf("sexual_content"), "13 Reasons Why" to setOf("sexual_content"),
            "Minions & Monsters" to setOf("scary_kids"), "The Lion King" to setOf("crime"),
            "Godzilla Minus Zero" to setOf("war"), "The Gay Divorcee" to setOf("lgbtq")
        )
        val titles = samples.map { it.title }.toSet()
        val missing = (family + fixed.keys + newData) - titles
        assertTrue("fixture lacks $missing", missing.isEmpty())
        val failures = mutableListOf<String>()
        val report = StringBuilder()
        samples.forEach { s ->
            val ids = s.keywords.map { it.id }
            val tags = TitleTags(
                genreIds = s.genreIds.toSet(),
                keywordIds = ids.toCollection(LinkedHashSet()),
                keywordNames = s.keywords.map { it.name.lowercase() },
                originalLanguage = s.language,
                adult = s.adult
            )
            val subject = FilterSubject(
                name = s.title,
                originalTitle = s.originalTitle,
                description = s.overview,
                genreNames = s.genreNames,
                tags = tags,
                tmdbKey = "${s.kind}:${s.tmdbId}"
            )
            val hits = filter.hits(subject)
            report.append("${s.exp.substringBefore(' ')} | ${s.title} (${s.year}) -> $hits\n")
            val expected = expectedPack(s.exp)
            if (expected != null && expected !in hits) {
                if (s.title in newData) report.append("   new data, outside bar (a): misses $expected\n")
                else failures += "(a) ${s.title} misses $expected"
            }
            if (s.title in family && hits.isNotEmpty()) failures += "(b) ${s.title} hits $hits"
            fixed[s.title]?.forEach { pack -> if (pack in hits) failures += "(d) ${s.title} still hits $pack" }
        }
        File("build/reports/spec117-f1-sample.txt").apply { parentFile?.mkdirs() }.writeText(report.toString())
        println(report)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
