package com.nuvio.tv.livetv.ui.search

import java.text.Normalizer
import java.util.Locale

/**
 * Spec 111 S1: live channel search by number and name. Case, accents and compatibility forms
 * (superscript or full-width letters) are ignored, and every whitespace run counts as one space.
 * Ranking (D9, mockup stage 3): a digits-only query first finds the channel with exactly that number,
 * then numbers starting with it; then, for any query, names that start with it, then a word in the
 * name that does, then the query anywhere in the name. Ties keep the order of the list searched
 * (number order). Which channels are searched (D8) is [LiveChannelSearchIndex]'s.
 */
object LiveChannelSearch {

    /** How a channel matched, best first; [onNumber] tiers matched its number, the rest its name. */
    enum class Tier(val onNumber: Boolean) {
        NUMBER_EXACT(true), NUMBER_PREFIX(true), PREFIX(false), WORD_START(false), SUBSTRING(false)
    }

    /**
     * One result: [item], its [tier], and where the query sits: in the number from 0 for a number
     * tier, else in the folded name ([foldedStart], [foldedLength]; [highlight] maps that to the
     * original name's chars for the rows shown).
     */
    data class Hit<T>(val item: T, val tier: Tier, val foldedStart: Int, val foldedLength: Int)

    /**
     * A name folded once per lineup (folding every name per key would redo the Unicode work for
     * every channel). Only the folded text is kept; [highlight] re-folds the few names shown.
     */
    class PreparedName internal constructor(internal val folded: String)

    fun prepare(name: String): PreparedName = PreparedName(fold(name, map = null))

    /**
     * [items] whose [number] or [name] matches [query], best tier first (a channel counts once, at
     * its best tier); within a tier, in [items]' order.
     */
    fun <T> search(items: List<T>, query: String, name: (T) -> PreparedName, number: (T) -> String?): List<Hit<T>> {
        val folded = fold(query, map = null)
        if (folded.isEmpty()) return emptyList()
        // Only a digits-only query matches numbers ("tv9", "70 6" do not).
        val numeric = folded.all { it in '0'..'9' }
        // One bucket per tier, filled in list order: stable without a sort.
        val buckets = Tier.entries.map { ArrayList<Hit<T>>() }
        for (item in items) {
            val numberTier = if (numeric) numberTier(number(item), folded) else null
            if (numberTier != null) {
                buckets[numberTier.ordinal] += Hit(item, numberTier, 0, folded.length)
                continue
            }
            val (tier, at) = match(name(item).folded, folded) ?: continue
            buckets[tier.ordinal] += Hit(item, tier, at, folded.length)
        }
        return buckets.flatten()
    }

    /** Plain string compare: a number as the lineup words it ("05", "5.1", "101a" from tvg-chno). */
    private fun numberTier(number: String?, digits: String): Tier? = when {
        number == null -> null
        number == digits -> Tier.NUMBER_EXACT
        number.startsWith(digits) -> Tier.NUMBER_PREFIX
        else -> null
    }

    /**
     * The original chars of [name] that a name-tier [hit] matched (whole code points, accents
     * included); a number tier's span is 0 until [Hit.foldedLength] in the number as shown.
     */
    fun highlight(name: String, hit: Hit<*>): IntRange {
        require(!hit.tier.onNumber) { "a number match highlights the number" }
        val map = IntArray(2 * MAX_FOLD * name.length)
        fold(name, map)
        val last = hit.foldedStart + hit.foldedLength - 1
        return map[2 * hit.foldedStart]..<map[2 * last + 1]
    }

    /** The tier and folded start of [foldedQuery] in [folded]; null when it does not occur or is blank. */
    internal fun match(folded: String, foldedQuery: String): Pair<Tier, Int>? {
        if (foldedQuery.isEmpty()) return null
        var at = folded.indexOf(foldedQuery)
        if (at < 0) return null
        if (at == 0) return Tier.PREFIX to 0
        val first = at
        while (at >= 0) {
            if (!isWordChar(folded.codePointBefore(at))) return Tier.WORD_START to at
            at = folded.indexOf(foldedQuery, at + 1)
        }
        return Tier.SUBSTRING to first
    }

    /**
     * Folds a name or query. [map], when given, gets each folded char's original start and end at
     * 2i and 2i+1 (size at least 2 * [MAX_FOLD] * [text] length).
     */
    internal fun fold(text: String, map: IntArray?): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            val start = out.length
            when {
                // One space per run, none leading; a trailing one is dropped below.
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) ->
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                cp < ASCII_END -> out.append(Character.toLowerCase(cp.toChar()))
                else -> {
                    val folded = foldCodePoint(text.substring(i, next))
                    // A lone combining mark folds to nothing: it joins the char before, so a
                    // highlight never splits a letter from its accent.
                    if (folded.isEmpty() && start > 0 && map != null) map[2 * start - 1] = next
                    out.append(folded)
                }
            }
            if (map != null) {
                for (k in start..<out.length) {
                    map[2 * k] = i
                    map[2 * k + 1] = next
                }
            }
            i = next
        }
        if (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    /** Letters, digits and spacing vowel signs (Indic Mc) belong to a word. */
    private fun isWordChar(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt()

    /**
     * One non-ASCII code point: compatibility-decomposed, lower-cased, combining marks dropped
     * ("É" -> "e", "İ" -> "i", "ᴴᴰ" -> "hd"). Dropping Mn also drops Indic vowel signs and anusvara,
     * so such queries can over-match (Spec 111 S1: accepted, search errs towards finding).
     */
    private fun foldCodePoint(codePoint: String): String =
        Normalizer.normalize(codePoint, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private const val ASCII_END = 0x80

    /** Most folded chars one source char can give (NFKD of U+FDFA is 18 chars). */
    private const val MAX_FOLD = 18
}
