package com.nuvio.tv.domain.model

import com.nuvio.tv.domain.model.ContentRatingCap.ADULTS_ONLY
import com.nuvio.tv.domain.model.ContentRatingCap.ALL_AGES
import com.nuvio.tv.domain.model.ContentRatingCap.MATURE
import com.nuvio.tv.domain.model.ContentRatingCap.OLDER_KIDS
import com.nuvio.tv.domain.model.ContentRatingCap.TEENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 102 S1: the non-US board table (R2) and its rules (R1, R3-R6), plus the map union (R8). */
class NonUsRatingBoardsTest {

    /** Three tier-1 boards from three families, so a test entry is the only thing that can raise the tier. */
    private val baseline = mapOf("GB" to "U", "KR" to "ALL", "BR" to "L")

    private fun tierWith(vararg extra: Pair<String, String>) = NonUsRatingBoards.tierOf(baseline + extra)

    @Test
    fun `every code in the spec's R2 table sits on its tier`() {
        // Spec 102 "Table (tier: codes)", copied from the spec; "AT, CH" and "BE as NL + 1 KT/EA" expanded.
        val table = """
            GB 1 U; 2 PG; 3 12A 12; 4 15; 5 18 R18
            IE 1 G; 2 PG; 3 12A 12; 4 15A 15 16; 5 18
            DE 1 0; 2 6; 3 12; 4 16; 5 18
            AT 1 0; 2 6 8; 3 10 12 14; 4 16; 5 18
            CH 1 0; 2 6 8; 3 10 12 14; 4 16; 5 18
            NL 1 AL; 2 6 9; 3 12 14; 4 16; 5 18
            BE 1 AL KT/EA; 2 6 9; 3 12 14; 4 16; 5 18
            KR 1 ALL; 2 7; 3 12; 4 15; 5 18 19
            BR 1 L; 3 10 12 14; 4 16; 5 18
            ES 1 A AI APTA TP ERI; 2 7 7I; 3 10 12 13; 4 16; 5 18 X
            FR 1 TP; 3 10 12; 4 16; 5 18
            AU 1 P C G; 2 PG; 4 M MA15+; 5 R18+ X18+
            NZ 1 G; 2 PG; 3 R13 RP13 13; 4 M R15 R16 RP16 16; 5 R18 RP18 R 18
            CA 1 G C; 2 C8 PG; 3 14A 14+; 5 18A 18+ R A
            SG 1 G; 2 PG; 3 PG13; 4 NC16; 5 M18 R21
            HU 1 KN; 2 6; 3 12; 4 16; 5 18 X
            PL 1 AL 0; 2 7; 3 12; 4 16; 5 18
            CZ 1 U 0+; 2 6+ 7+ 9+; 3 12+ 12; 4 15+ 15; 5 18+ 18
            SK 1 U; 2 7; 3 12; 4 15; 5 18
            RU 1 0+; 2 6+; 3 12+; 4 16+; 5 18+
            JP 1 G; 3 PG12; 4 R15+; 5 R18+
            IN 1 U; 2 U/A_7+; 3 UA U/A_13+; 4 U/A_16+; 5 A
            IT 1 T; 2 6+ BA; 3 10+ 14+ VM12 VM14; 5 18+ VM18
            RO 1 AG; 2 AP; 3 AP-12 12; 4 15; 5 18 18+
            SE 1 BTL; 2 7 FRÅN_7_ÅR; 3 11 FRÅN_11_ÅR; 4 15 FRÅN_15_ÅR
            DK 1 A; 2 7; 3 11; 4 15
            NO 1 A; 2 6 9; 3 12; 4 15; 5 18
            FI 1 S; 2 K-7 K7; 3 K-12 K12; 4 K-16 K16; 5 K-18 K18
            PT 2 M/3 M/6; 3 M/12 M/14; 4 M/16; 5 M/18
            MX 1 AA A; 3 B; 4 B-15; 5 C D
        """.trimIndent()
        var checked = 0
        table.lines().forEach { line ->
            val country = line.substringBefore(' ')
            line.substringAfter(' ').split("; ").forEach { group ->
                val parts = group.split(' ')
                val tier = ContentRatingCap.entries[parts[0].toInt() - 1]
                parts.drop(1).forEach { code ->
                    assertEquals("$country $code", tier, NonUsRatingBoards.boardTier(country, code.replace('_', ' ')))
                    checked++
                }
            }
        }
        assertEquals(216, checked)
        assertEquals(checked, NonUsRatingBoards.boardCodeCount)
    }

    @Test
    fun `codes match trimmed, case-insensitively and with any Unicode spacing`() {
        assertEquals(MATURE, NonUsRatingBoards.boardTier(" au ", "ma 15+"))
        assertEquals(MATURE, NonUsRatingBoards.boardTier("AU", "MA15+"))
        assertEquals(MATURE, NonUsRatingBoards.boardTier("SE", "Från\u00A015 år"))
        assertEquals(OLDER_KIDS, NonUsRatingBoards.boardTier("IN", "u/a  7+"))
        assertNull(NonUsRatingBoards.boardTier("GB", "PG-13"))
        assertNull(NonUsRatingBoards.boardTier("XX", "12"))
    }

    @Test
    fun `R3 a tier needs three board families and is the strictest entry`() {
        assertNull(NonUsRatingBoards.tierOf(emptyMap()))
        assertNull(NonUsRatingBoards.tierOf(mapOf("GB" to "12A", "DE" to "12")))
        // DE, AT and CH are one family; NL and BE another.
        assertNull(NonUsRatingBoards.tierOf(mapOf("DE" to "6", "AT" to "6", "CH" to "6")))
        assertNull(NonUsRatingBoards.tierOf(mapOf("DE" to "6", "AT" to "6", "NL" to "6", "BE" to "6")))
        assertEquals(OLDER_KIDS, NonUsRatingBoards.tierOf(mapOf("DE" to "6", "NL" to "6", "GB" to "PG")))
        assertEquals(MATURE, NonUsRatingBoards.tierOf(mapOf("DE" to "6", "NL" to "16", "GB" to "PG", "FR" to "12")))
        // The same family twice in one map ("de" and "DE") still counts once.
        assertNull(NonUsRatingBoards.tierOf(mapOf("DE" to "6", "de" to "6", "GB" to "PG")))
    }

    @Test
    fun `R5 the fallback never places a title on Little kids`() {
        assertEquals(OLDER_KIDS, NonUsRatingBoards.tierOf(baseline))
        assertEquals(OLDER_KIDS, NonUsRatingBoards.tierOf(baseline + ("DE" to "0") + ("FR" to "TP")))
    }

    @Test
    fun `R4 an unknown code can only raise, by age or adult marker, and never counts as a board`() {
        assertEquals(MATURE, tierWith("TH" to "น 15+"))
        assertEquals(TEENS, tierWith("TW" to "12+"))
        assertEquals(TEENS, tierWith("DE" to "12+"))
        assertEquals(OLDER_KIDS, tierWith("TW" to "6+"))
        assertEquals(ADULTS_ONLY, tierWith("GR" to "K-18"))
        assertEquals(MATURE, tierWith("EG" to "\u0661\u0665")) // Arabic-Indic 15
        assertEquals(OLDER_KIDS, tierWith("XX" to "2023")) // not an age
        assertEquals(ADULTS_ONLY, tierWith("XX" to "21"))
        assertEquals(OLDER_KIDS, tierWith("XX" to "22"))
        assertEquals(OLDER_KIDS, tierWith("ZA" to "7-9PG"))
        assertEquals(TEENS, tierWith("ZA" to "10-12PG"))
        assertEquals(TEENS, tierWith("PT" to "10AP"))
        // Numbers apart stay apart: "15 18" is 18, not 1518 (dropped as no age).
        assertEquals(ADULTS_ONLY, tierWith("XX" to "15 18"))
        // US-ladder codes raise under any key, PR and VI included.
        assertEquals(MATURE, tierWith("PR" to "TV-MA"))
        assertEquals(MATURE, tierWith("VI" to "R"))
        assertEquals(TEENS, tierWith("IE" to "PG-13"))
        // A US value R1 cannot read still raises.
        assertEquals(ADULTS_ONLY, tierWith("US" to "18+"))
        assertEquals(MATURE, tierWith("US" to "NC 17")) // not the ladder's NC-17; its 17 raises
    }

    @Test
    fun `R4 every lettered raise code reaches its tier`() {
        val codes = listOf(
            "KR" to "Restricted Screening" to ADULTS_ONLY, "AU" to "RC" to ADULTS_ONLY, "FI" to "KK" to ADULTS_ONLY,
            "RO" to "IC" to ADULTS_ONLY, "IN" to "S" to ADULTS_ONLY, "PT" to "P" to ADULTS_ONLY,
            "LT" to "S" to ADULTS_ONLY, "AR" to "C" to ADULTS_ONLY, "BG" to "C" to TEENS, "BG" to "D" to MATURE,
            "BG" to "X" to ADULTS_ONLY, "CL" to "R" to MATURE, "CL" to "A" to ADULTS_ONLY, "ID" to "R" to MATURE,
            "ID" to "D" to ADULTS_ONLY, "PH" to "X" to ADULTS_ONLY, "HK" to "III" to ADULTS_ONLY,
            "ZA" to "XX" to ADULTS_ONLY, "ZA" to "X18" to ADULTS_ONLY, "TH" to "Banned" to ADULTS_ONLY,
            "TH" to "-" to ADULTS_ONLY
        )
        codes.forEach { (entry, tier) ->
            val others = mapOf("GB" to "U", "DE" to "0", "BR" to "L", "NL" to "AL") - entry.first
            assertEquals(entry.toString(), tier, NonUsRatingBoards.tierOf(others + entry))
        }
        // Raises alone place nothing.
        assertNull(NonUsRatingBoards.tierOf(mapOf("TW" to "6+", "TH" to "-", "PR" to "TV-Y", "DE" to "12+")))
        // A raise never lowers: a known 18 stays 5 beside an unknown 12.
        assertEquals(ADULTS_ONLY, tierWith("DE" to "18", "TW" to "12+"))
    }

    @Test
    fun `R6 unrated markers are ignored`() {
        listOf("NR", "Unrated", "UR", "Exempt", "E", "", " ").forEach { code ->
            assertEquals(code, OLDER_KIDS, tierWith("HU" to code))
            assertNull(code, NonUsRatingBoards.tierOf(mapOf("GB" to "U", "KR" to "ALL", "HU" to code)))
        }
    }

    @Test
    fun `R1 a US code decides alone, from the rating or the map`() {
        val lenient = mapOf("GB" to "U", "KR" to "ALL", "BR" to "L")
        assertEquals(MATURE, ContentRatingCap.effectiveTierOf("R", lenient))
        assertEquals(MATURE, ContentRatingCap.effectiveTierOf(null, lenient + ("US" to "R")))
        assertEquals(MATURE, ContentRatingCap.effectiveTierOf("NR", lenient + (" us " to "R")))
        // Two spellings of the US key: the stricter code.
        assertEquals(MATURE, NonUsRatingBoards.usTierOf(mapOf("US" to "PG", "us" to "R")))
        assertEquals(TEENS, ContentRatingCap.effectiveTierOf("PG-13", mapOf("DE" to "18", "GB" to "18", "FR" to "18")))
        assertEquals(OLDER_KIDS, ContentRatingCap.effectiveTierOf(null, lenient))
        assertEquals(OLDER_KIDS, ContentRatingCap.effectiveTierOf(null, lenient + ("US" to "NR")))
        assertNull(ContentRatingCap.effectiveTierOf(null, emptyMap()))
    }

    @Test
    fun `isRatingAllowed reads the non-US boards only when the US rating is unknown`() {
        val older = mapOf("GB" to "PG", "DE" to "6", "KR" to "7")
        assertTrue(isRatingAllowed(null, OLDER_KIDS, older))
        assertFalse(isRatingAllowed(null, ALL_AGES, older))
        assertFalse(isRatingAllowed(null, ALL_AGES, mapOf("GB" to "U", "DE" to "0", "KR" to "ALL")))
        assertFalse(isRatingAllowed("R", TEENS, older))
        assertFalse(isRatingAllowed(null, TEENS, mapOf("GB" to "PG", "DE" to "6")))
    }

    @Test
    fun `union keeps per country the stricter code, a known one on a tie, else the first`() {
        // Known vs raise of a higher tier: the raise wins.
        assertEquals(mapOf("DE" to "16+"), NonUsRatingBoards.union(mapOf("DE" to "6"), mapOf("DE" to "16+")))
        // Tie: DE 18 (known) vs FSK 18 (raise): the known code, from either side.
        assertEquals(mapOf("DE" to "18"), NonUsRatingBoards.union(mapOf("DE" to "FSK 18"), mapOf("DE" to "18")))
        assertEquals(mapOf("DE" to "18"), NonUsRatingBoards.union(mapOf("DE" to "18"), mapOf("DE" to "FSK 18")))
        // Known vs unrated, unrated vs unknown.
        assertEquals(mapOf("DE" to "12"), NonUsRatingBoards.union(mapOf("DE" to "NR"), mapOf("DE" to "12")))
        assertEquals(mapOf("DE" to "NR"), NonUsRatingBoards.union(mapOf("DE" to "NR"), mapOf("de" to "Ab 6")))
        // US entries: the stricter US code.
        assertEquals(mapOf("US" to "R"), NonUsRatingBoards.union(mapOf("US" to "PG"), mapOf("US" to "R")))
        // A one-board copy still makes the union stricter (red team cbeb5c30 m1).
        val row = mapOf("DE" to "6", "NL" to "6", "GB" to "PG")
        val meta = mapOf("KR" to "19")
        assertEquals(ADULTS_ONLY, NonUsRatingBoards.tierOf(NonUsRatingBoards.union(row, meta)))
    }

    @Test
    fun `real sample maps get the oracle's tier`() {
        assertEquals(145, NonUsRatingBoardsSample.cases.size)
        NonUsRatingBoardsSample.cases.forEach { case ->
            assertEquals(case.ratings.toString(), case.tier, NonUsRatingBoards.tierOf(case.ratings))
        }
    }
}
