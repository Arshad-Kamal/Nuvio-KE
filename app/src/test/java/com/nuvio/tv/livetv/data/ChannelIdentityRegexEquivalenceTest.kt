package com.nuvio.tv.livetv.data

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 115 S8: [ChannelIdentity]'s hand-written scans against the regexes they replaced
 * ([Old], copied verbatim from 22dfeac81). The inputs keep to characters on which the desktop
 * regex and Android's ICU regex agree: ICU also ends lines at U+000B/U+000C and takes any
 * Unicode letter or mark as a `\b` word character, where JDK 21 does neither; those Android
 * rules are pinned by the last test instead.
 */
class ChannelIdentityRegexEquivalenceTest {

    @Test
    fun `real-world names, ids and urls give what the regexes gave`() {
        CORPUS.forEach(::assertSame)
    }

    @Test
    fun `generated names, ids and urls give what the regexes gave`() {
        val random = Random(115)
        val hits = IntArray(5)
        repeat(20_000) {
            val name = List(random.nextInt(1, 8)) { FRAGMENTS.random(random) }.joinToString("")
            assertSame(name)
            val url = url(random)
            assertSame(url)
            if (ChannelIdentity.looseId(name) != null) hits[0]++
            if (ChannelIdentity.tvgIdKeys(name).size == 2) hits[1]++
            if (ChannelIdentity.stripCountrySuffix(name) != name) hits[2]++
            if (QUALITY_WORDS.any { it in name.lowercase() } && ChannelIdentity.nameWords(name).none { it in QUALITY_WORDS }) hits[3]++
            if (ChannelIdentity.providerId(url) != null) hits[4]++
        }
        // The generator must reach every rule, or the comparison proves nothing.
        hits.forEachIndexed { i, n -> assertTrue("rule $i hit only $n times", n >= 200) }
    }

    @Test
    fun `Android regex rules the desktop regex lacks are kept`() {
        // ICU `$` and `.` also end lines at U+000B / U+000C.
        assertEquals("CNN\u000B", ChannelIdentity.stripCountrySuffix("CNN.us\u000B"))
        assertNull(ChannelIdentity.looseId("bbc\u000Cone.uk"))
        // ICU `\b` takes letters and marks as word characters: a quality token inside a word stays.
        assertEquals(listOf("téléhd"), ChannelIdentity.nameWords("TéléHD"))
        assertEquals(listOf("hd\u0301"), ChannelIdentity.nameWords("hd\u0301"))
        // ICU `\d` is any decimal digit, supplementary ones included.
        assertEquals("ntv.bd", ChannelIdentity.looseId("NTV.bd\u0663"))
        assertEquals("ntv.bd", ChannelIdentity.looseId("NTV.bd\uD835\uDFD9"))
    }

    private fun assertSame(input: String) {
        val m = "input <${input.escape()}>"
        assertEquals(m, Old.nameWords(input), ChannelIdentity.nameWords(input))
        assertEquals(m, Old.looseId(input), ChannelIdentity.looseId(input))
        assertEquals(m, Old.tvgIdKeys(input), ChannelIdentity.tvgIdKeys(input))
        assertEquals(m, Old.normalizeCountry(input), ChannelIdentity.normalizeCountry(input))
        assertEquals(m, Old.stripCountrySuffix(input), ChannelIdentity.stripCountrySuffix(input))
        assertEquals(m, Old.providerId(input), ChannelIdentity.providerId(input))
    }

    private fun url(random: Random): String = buildString {
        append(listOf("http://", "https://", "HTTPS://", "ftp://", "").random(random))
        append(listOf("jmp2.uk/", "JMP2.UK/", "jmp2.uk", "jmp2-uk/", "x.jmp2.uk/").random(random))
        val service = listOf("plu-", "PLU-", "plex-", "Plex-", "sam-", "SAM-", "stvp-", "stv-", "").random(random)
        append(service)
        if (random.nextBoolean()) {
            // A well-formed id for the prefix, so the random tail decides the match.
            append(
                when (service.lowercase()) {
                    "plu-" -> hex(random, 24)
                    "plex-" -> hex(random, 24) + "-" + hex(random, 24)
                    else -> alnum(random, 2).filter { it.isLetter() }.padEnd(2, 'g') + alnum(random, random.nextInt(5, 16))
                }
            )
        }
        repeat(random.nextInt(0, 4)) {
            append(
                when (random.nextInt(5)) {
                    0 -> hex(random, listOf(23, 24, 25).random(random))
                    1 -> alnum(random, random.nextInt(4, 18))
                    2 -> listOf("-", "/", ".", "?", "#", "_", ".m3u8", "?a=1", "\n", "\r\n", "\u2028", "\u0085")
                        .random(random)
                    3 -> listOf("us", "GB", "ca", "1a").random(random)
                    else -> hex(random, 24) + "-" + hex(random, listOf(23, 24).random(random))
                }
            )
        }
    }

    private fun hex(random: Random, n: Int) = String(CharArray(n) { "0123456789abcdefABCDEF".random(random) })

    private fun alnum(random: Random, n: Int) =
        String(CharArray(n) { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random(random) })

    private fun String.escape() = map { if (it.code < 0x20 || it.code > 0x7E) "\\u%04x".format(it.code) else "$it" }
        .joinToString("")

    /** The 22dfeac81 regex implementation, verbatim apart from the object name. */
    private object Old {
        fun normalizeTvgId(raw: String?): String? =
            raw?.trim()?.substringBefore('@')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

        fun tvgIdKeys(raw: String?): List<String> {
            val plain = normalizeTvgId(raw) ?: return emptyList()
            val feed = raw.orEmpty().substringAfter('@', "").trim()
            val tagged = FEED_STEM.matchEntire(plain)
            if (tagged == null || !FEED.matches(feed)) return listOf(plain)
            val (stem, country) = tagged.destructured
            return listOf("$stem${feed.lowercase(Locale.ROOT)}.$country", plain)
        }

        fun looseId(raw: String?): String? {
            val m = LOOSE_ID.matchEntire(normalizeTvgId(raw) ?: return null) ?: return null
            val stem = normalizeName(BRACKET_CHARS.replace(m.groupValues[1], " ")).ifEmpty { return null }
            return "$stem.${m.groupValues[2]}"
        }

        fun normalizeName(raw: String?): String = nameWords(raw).joinToString("")

        fun nameWords(raw: String?): List<String> {
            var s = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            s = BRACKETED.replace(s, "")
            s = s.replace("&", " and ").replace("+", " plus ")
            s = QUALITY.replace(s, " ")
            return s.split(NON_WORD).filter { it.isNotEmpty() }
        }

        fun normalizeCountry(raw: String?): String? =
            raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { COUNTRY.matches(it) }

        fun stripCountrySuffix(raw: String): String = COUNTRY_SUFFIX.replace(raw, "")

        fun providerId(url: String): Pair<GuideService, String>? {
            for ((service, pattern) in PROVIDER_URLS) {
                pattern.find(url)?.let { return service to it.groupValues[1].lowercase(Locale.ROOT) }
            }
            return null
        }

        private val BRACKETED = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}""")
        private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|1080p|720p|576p|480p|360p)\b""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}\p{M}_]+""")
        private val COUNTRY = Regex("[a-z]{2}")
        private val COUNTRY_SUFFIX = Regex("""\.[a-zA-Z]{2}\d?$""")
        private val FEED_STEM = Regex("""(.+)\.([a-z]{2})""")
        private val FEED = Regex("""[A-Za-z0-9]{1,32}""")
        private val LOOSE_ID = Regex("""(.+)\.([a-z]{2})\d?""")
        private val BRACKET_CHARS = Regex("""[()\[\]\{\}]""")
        private val PROVIDER_URLS = listOf(
            GuideService.PLUTO to Regex("""^https?://jmp2\.uk/plu-([0-9a-f]{24})(?:[./?#]|$)""", RegexOption.IGNORE_CASE),
            GuideService.PLEX to
                Regex("""^https?://jmp2\.uk/plex-([0-9a-f]{24}-[0-9a-f]{24})(?:[./?#]|$)""", RegexOption.IGNORE_CASE),
            GuideService.SAMSUNG to
                Regex("""^https?://jmp2\.uk/(?:stvp|sam)-([a-z]{2}[a-z0-9]{6,14})(?:[./?#]|$)""", RegexOption.IGNORE_CASE)
        )
    }

    private companion object {
        val CORPUS = listOf(
            "", " ", "Star Jalsha HD", "STAR JALSHA", "BBC One (London)", "BBC One [Geo-blocked]", "CNN.HD.us2",
            "Al Jazeera English", "Al Jazeera (Arabic) {backup}", "9Gem.au@Sydney", " BBCOne.uk@London ",
            "BBCOne.uk@East HD", "HBO.(Pacific).us2", "Dave.+1.uk", "Dave.uk@Plus1", "ABC.TV.au", "NTV.bd", "NTV.in",
            "Sky Sports F1 UHD", "Zee TV 4K", "Canal+ Sport", "M6 & W9", "TF1 Séries Films", "ATN Bangla (720p) [Not 24/7]",
            "Россия 1 HD", "NHK総合", "Arirang TV.kr", "Star Jalsha HD.in", "CNN.us", "CNN.us\n", "CNN.us\r\n", "CNN.us2\r",
            "x.us\n\n", "Fox (1080p)", "fox 1080p", "fox_hd", "HD", "hd hd", "4k8k", "h265 hevc", "sd.", "A&E", "E!+",
            "[unclosed", "(open (nested) close)", "a]b[c", "{x}{y}z", "ESPN2.us", "espn.US", "espn.u", ".us", "a.", "IN",
            " in ", "In", "i", "abc", "Ü.de", "café.fr", "TV5Monde.fr@Europe", "BBCOne.uk@" + "a".repeat(32),
            "BBCOne.uk@" + "a".repeat(33), "https://jmp2.uk/plu-5ddbf866b1862a0009a0648e.m3u8",
            "http://JMP2.UK/PLU-5DDBF866B1862A0009A0648E", "https://jmp2.uk/plu-5ddbf866b1862a0009a0648ex",
            "https://jmp2.uk/plex-5e20b730f2f8d5003d739db7-5eea605674085f0040ddc7a6?x=1",
            "https://jmp2.uk/sam-GBBD8000016N.m3u8", "https://jmp2.uk/stvp-USBA300024TY", "https://jmp2.uk/sam-us12345",
            "https://jmp2.uk/sam-us123456789012345", "https://jmp2.uk/sam-1s1234567", "https://example.com/plu-x",
            "https://jmp2.uk/plu-5ddbf866b1862a0009a0648e\n", "https://jmp2.uk/sam-GBBD8000016N_x"
        )

        val QUALITY_WORDS = listOf(
            "uhd", "fhd", "hd", "sd", "4k", "8k", "hevc", "h265", "1080p", "720p", "576p", "480p", "360p"
        )

        // Non-ASCII words stay space-padded: next to a quality token JDK 21's ASCII `\b` and
        // ICU's Unicode one disagree, so an unpadded "é" would fail a correct implementation.

        val FRAGMENTS = QUALITY_WORDS + listOf(
            "HD", "4K", "Hevc", "480P",
            "star", "jalsha", "bbc", "one", "cnn", "tv", "news", "Plus", "and",
            " ", " ", " ", ".", "-", "_", "&", "+", "@", "(", ")", "[", "]", "{", "}", "/", "?", "#", ":", "!",
            ".us", ".uk", ".in", ".US", ".Uk", ".u", ".usa", "2", "1", "0", "24", "a", "z", "Q",
            ".us@East", ".in@SD", ".uk@Plus1", "@London", "@hd", ".au@" + "x".repeat(33),
            " café ", " জলসা ", " 日本 ", " Россия ", " TéLé ", " \uD800\uDF30\uD800\uDF31 ", " \uD83D\uDE00 ",
            "\n", "\r\n", "\r", "\u2028", "\u0085", "\t"
        )
    }
}
