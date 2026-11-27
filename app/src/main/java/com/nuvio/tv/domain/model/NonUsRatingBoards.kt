package com.nuvio.tv.domain.model

import com.nuvio.tv.domain.model.ContentRatingCap.ADULTS_ONLY
import com.nuvio.tv.domain.model.ContentRatingCap.ALL_AGES
import com.nuvio.tv.domain.model.ContentRatingCap.MATURE
import com.nuvio.tv.domain.model.ContentRatingCap.OLDER_KIDS
import com.nuvio.tv.domain.model.ContentRatingCap.TEENS
import java.util.Locale

/**
 * Spec 102: a title's non-US age ratings (the addon's `app_extras.certifications`, country -> code)
 * read onto the Spec 64 ladder, for a title whose US rating is unknown. Fail closed: anything this
 * cannot place returns null, and a null tier is hidden under every cap (64/D1).
 *
 * - R2 [BOARDS]: each country's codes by the board's own minimum or advisory age (0 -> 1, 1-9 -> 2,
 *   10-14 -> 3, 15-17 -> 4, 18+ -> 5; age-less parental guidance -> 2), from TMDB's certification
 *   lists. A code is read by its own country's row only; one missing from that row is not guessed.
 * - R3: a tier needs known codes from [MIN_FAMILIES] board families (boards that often copy one
 *   decision count once, [FAMILY]); the tier is the strictest entry. Fewer leak too often (Spec 102).
 * - R4: an entry no row knows can only raise the tier, never set it or count as a board: the
 *   strictest of a number in its text by the age rule (when that gives 3+), a lettered code from
 *   [RAISE_CODES], and a US-ladder code (PR, VI, a US-style code under any key, an unreadable US).
 *   R1 reads a known US code before this ([ContentRatingCap.effectiveTierOf]).
 * - R5: never below [OLDER_KIDS], so Little kids sees no title rated only this way.
 * - R6: unrated markers (`NR`, `Unrated`, `Exempt`...) are in no row and carry no digits: ignored.
 */
object NonUsRatingBoards {

    /** The ladder tier of [ratings] (R2-R6), or null when it cannot be placed. A US entry only raises (R4). */
    fun tierOf(ratings: Map<String, String>): ContentRatingCap? {
        var strictest = OLDER_KIDS
        val families = HashSet<String>()
        for ((rawCountry, code) in ratings) {
            val country = countryKey(rawCountry)
            val entry = entryOf(country, code) ?: continue
            if (entry.known && country != US) families += FAMILY[country] ?: country
            if (entry.tier > strictest) strictest = entry.tier
        }
        return strictest.takeIf { families.size >= MIN_FAMILIES }
    }

    /** The strictest known US-ladder code among [ratings]' US entries (R1), or null. */
    fun usTierOf(ratings: Map<String, String>): ContentRatingCap? = usCodeOf(ratings)?.let(ContentRatingCap::tierOf)

    /** [usTierOf]'s code, as [ContentRatingCap.usRatingCode] spells it; for display and merging. */
    fun usCodeOf(ratings: Map<String, String>): String? = ratings.entries
        .filter { countryKey(it.key) == US }
        .mapNotNull { ContentRatingCap.usRatingCode(it.value) }
        .maxByOrNull { ContentRatingCap.tierOf(it)?.ordinal ?: -1 }

    /**
     * Both maps as one, for two copies of one title (Spec 102 R8, duplicate merge): per country the
     * code with the higher entry tier; on a tie the code a row knows (for US, a US-ladder code); else
     * [first]'s. Keys come back normalised (trimmed, uppercase).
     */
    fun union(first: Map<String, String>, second: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for ((rawCountry, code) in first.entries + second.entries) {
            val country = countryKey(rawCountry)
            val kept = merged[country]
            if (kept == null || outranks(country, code, kept)) merged[country] = code
        }
        return merged
    }

    /** The tier a row ([BOARDS]) gives [code] for [country], or null; for tests and diagnostics. */
    internal fun boardTier(country: String, code: String): ContentRatingCap? =
        BOARDS[countryKey(country)]?.get(normalise(code))

    /** How many codes [BOARDS] holds, so a test can prove the table has nothing the spec lacks. */
    internal val boardCodeCount: Int get() = BOARDS.values.sumOf { it.size }

    private class Entry(val tier: ContentRatingCap, val known: Boolean)

    private fun outranks(country: String, candidate: String, kept: String): Boolean {
        val new = entryOf(country, candidate)
        val old = entryOf(country, kept)
        return when {
            new == null -> false
            old == null -> true
            new.tier != old.tier -> new.tier > old.tier
            else -> new.known && !old.known
        }
    }

    private fun entryOf(country: String, code: String): Entry? {
        val normalised = normalise(code)
        if (normalised.isEmpty()) return null
        BOARDS[country]?.get(normalised)?.let { return Entry(it, known = true) }
        val usLadder = ContentRatingCap.tierOf(code)
        if (country == US && usLadder != null) return Entry(usLadder, known = true)
        val raise = listOfNotNull(
            RAISE_CODES[country]?.get(normalised),
            usLadder,
            largestAge(code)?.let(::ageTier)?.takeIf { it >= TEENS }
        ).maxOrNull() ?: return null
        return Entry(raise, known = false)
    }

    /** R2's age rule. */
    private fun ageTier(age: Int): ContentRatingCap = when {
        age == 0 -> ALL_AGES
        age <= 9 -> OLDER_KIDS
        age <= 14 -> TEENS
        age <= 17 -> MATURE
        else -> ADULTS_ONLY
    }

    /**
     * The largest number up to [MAX_AGE] written in [text] (any Unicode decimal digits), or null. Read
     * before whitespace is stripped, so `15 18` is two numbers, not 1518.
     */
    private fun largestAge(text: String): Int? {
        var largest: Int? = null
        var run = -1
        for (char in text + ' ') {
            if (char.isDigit()) {
                val digit = char.digitToInt()
                run = if (run < 0) digit else (run * 10 + digit).coerceAtMost(MAX_AGE + 1)
            } else if (run >= 0) {
                if (run <= MAX_AGE) largest = maxOf(largest ?: run, run)
                run = -1
            }
        }
        return largest
    }

    /** Uppercase with every Unicode whitespace removed, so `MA 15+`, `MA15+` and `ma\u00A015+` match. */
    private fun normalise(code: String): String = code.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)

    private fun countryKey(country: String): String = country.trim().uppercase(Locale.ROOT)

    private fun board(vararg tiers: Pair<ContentRatingCap, String>): Map<String, ContentRatingCap> =
        tiers.flatMap { (tier, codes) -> codes.split('|').map { normalise(it) to tier } }.toMap()

    private const val US = "US"
    private const val MIN_FAMILIES = 3
    private const val MAX_AGE = 21

    /** Boards that often copy one decision (Spec 102 round 3): one family each for R3. */
    private val FAMILY = mapOf("AT" to "DE", "CH" to "DE", "BE" to "NL", "SK" to "CZ", "NZ" to "AU")

    /**
     * Lettered codes no row holds that mean 13+ or stricter, by TMDB's meanings (R4, raise-only): adult or
     * banned marks of table boards, and the strict codes of boards outside the table. TH `-` is "Banned".
     */
    private val RAISE_CODES: Map<String, Map<String, ContentRatingCap>> = mapOf(
        "KR" to board(ADULTS_ONLY to "Restricted Screening"),
        "AU" to board(ADULTS_ONLY to "RC"),
        "FI" to board(ADULTS_ONLY to "KK"),
        "RO" to board(ADULTS_ONLY to "IC"),
        "IN" to board(ADULTS_ONLY to "S"),
        "PT" to board(ADULTS_ONLY to "P"),
        "LT" to board(ADULTS_ONLY to "S"),
        "AR" to board(ADULTS_ONLY to "C"),
        "BG" to board(TEENS to "C", MATURE to "D", ADULTS_ONLY to "X"),
        // CL and ID "R" (12+, 13+) need no row: the US-ladder raise already reads R as 4.
        "CL" to board(ADULTS_ONLY to "A"),
        "ID" to board(ADULTS_ONLY to "D"),
        "PH" to board(ADULTS_ONLY to "X"),
        "HK" to board(ADULTS_ONLY to "III"),
        "ZA" to board(ADULTS_ONLY to "XX|X18"),
        "TH" to board(ADULTS_ONLY to "Banned|-")
    )

    /** Spec 102 R2 table (source: TMDB /3/certification/{movie,tv}/list, 2026-11-16, + TVDB spellings). */
    private val BOARDS: Map<String, Map<String, ContentRatingCap>> = mapOf(
        "GB" to board(ALL_AGES to "U", OLDER_KIDS to "PG", TEENS to "12A|12", MATURE to "15", ADULTS_ONLY to "18|R18"),
        "IE" to board(ALL_AGES to "G", OLDER_KIDS to "PG", TEENS to "12A|12", MATURE to "15A|15|16", ADULTS_ONLY to "18"),
        "DE" to board(ALL_AGES to "0", OLDER_KIDS to "6", TEENS to "12", MATURE to "16", ADULTS_ONLY to "18"),
        "AT" to board(ALL_AGES to "0", OLDER_KIDS to "6|8", TEENS to "10|12|14", MATURE to "16", ADULTS_ONLY to "18"),
        "CH" to board(ALL_AGES to "0", OLDER_KIDS to "6|8", TEENS to "10|12|14", MATURE to "16", ADULTS_ONLY to "18"),
        "NL" to board(ALL_AGES to "AL", OLDER_KIDS to "6|9", TEENS to "12|14", MATURE to "16", ADULTS_ONLY to "18"),
        "BE" to board(ALL_AGES to "AL|KT/EA", OLDER_KIDS to "6|9", TEENS to "12|14", MATURE to "16", ADULTS_ONLY to "18"),
        "KR" to board(ALL_AGES to "ALL", OLDER_KIDS to "7", TEENS to "12", MATURE to "15", ADULTS_ONLY to "18|19"),
        "BR" to board(ALL_AGES to "L", TEENS to "10|12|14", MATURE to "16", ADULTS_ONLY to "18"),
        "ES" to board(
            ALL_AGES to "A|AI|APTA|TP|ERI", OLDER_KIDS to "7|7I", TEENS to "10|12|13", MATURE to "16",
            ADULTS_ONLY to "18|X"
        ),
        "FR" to board(ALL_AGES to "TP", TEENS to "10|12", MATURE to "16", ADULTS_ONLY to "18"),
        "AU" to board(ALL_AGES to "P|C|G", OLDER_KIDS to "PG", MATURE to "M|MA15+", ADULTS_ONLY to "R18+|X18+"),
        "NZ" to board(
            ALL_AGES to "G", OLDER_KIDS to "PG", TEENS to "R13|RP13|13", MATURE to "M|R15|R16|RP16|16",
            ADULTS_ONLY to "R18|RP18|R|18"
        ),
        "CA" to board(ALL_AGES to "G|C", OLDER_KIDS to "C8|PG", TEENS to "14A|14+", ADULTS_ONLY to "18A|18+|R|A"),
        "SG" to board(ALL_AGES to "G", OLDER_KIDS to "PG", TEENS to "PG13", MATURE to "NC16", ADULTS_ONLY to "M18|R21"),
        "HU" to board(ALL_AGES to "KN", OLDER_KIDS to "6", TEENS to "12", MATURE to "16", ADULTS_ONLY to "18|X"),
        "PL" to board(ALL_AGES to "AL|0", OLDER_KIDS to "7", TEENS to "12", MATURE to "16", ADULTS_ONLY to "18"),
        "CZ" to board(
            ALL_AGES to "U|0+", OLDER_KIDS to "6+|7+|9+", TEENS to "12+|12", MATURE to "15+|15", ADULTS_ONLY to "18+|18"
        ),
        "SK" to board(ALL_AGES to "U", OLDER_KIDS to "7", TEENS to "12", MATURE to "15", ADULTS_ONLY to "18"),
        "RU" to board(ALL_AGES to "0+", OLDER_KIDS to "6+", TEENS to "12+", MATURE to "16+", ADULTS_ONLY to "18+"),
        "JP" to board(ALL_AGES to "G", TEENS to "PG12", MATURE to "R15+", ADULTS_ONLY to "R18+"),
        "IN" to board(ALL_AGES to "U", OLDER_KIDS to "U/A 7+", TEENS to "UA|U/A 13+", MATURE to "U/A 16+", ADULTS_ONLY to "A"),
        "IT" to board(
            ALL_AGES to "T", OLDER_KIDS to "6+|BA", TEENS to "10+|14+|VM12|VM14", ADULTS_ONLY to "18+|VM18"
        ),
        "RO" to board(ALL_AGES to "AG", OLDER_KIDS to "AP", TEENS to "AP-12|12", MATURE to "15", ADULTS_ONLY to "18|18+"),
        "SE" to board(
            ALL_AGES to "BTL", OLDER_KIDS to "7|FRÅN 7 ÅR", TEENS to "11|FRÅN 11 ÅR", MATURE to "15|FRÅN 15 ÅR"
        ),
        "DK" to board(ALL_AGES to "A", OLDER_KIDS to "7", TEENS to "11", MATURE to "15"),
        "NO" to board(ALL_AGES to "A", OLDER_KIDS to "6|9", TEENS to "12", MATURE to "15", ADULTS_ONLY to "18"),
        "FI" to board(
            ALL_AGES to "S", OLDER_KIDS to "K-7|K7", TEENS to "K-12|K12", MATURE to "K-16|K16", ADULTS_ONLY to "K-18|K18"
        ),
        "PT" to board(OLDER_KIDS to "M/3|M/6", TEENS to "M/12|M/14", MATURE to "M/16", ADULTS_ONLY to "M/18"),
        "MX" to board(ALL_AGES to "AA|A", TEENS to "B", MATURE to "B-15", ADULTS_ONLY to "C|D")
    )
}
