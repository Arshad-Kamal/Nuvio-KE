package com.nuvio.tv.domain.contentfilter

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One word or phrase of a pack (Spec 117 P2/P3). [textToo]: matches the title, other titles and
 * description. [tagNameToo]: matches inside TMDB keyword names. Either way, a keyword whose whole name
 * is this word matches (R10-M2).
 */
data class PackWord(val text: String, val textToo: Boolean = true, val tagNameToo: Boolean = true)

/** A built-in pack, the profile's My words, or one of its own packs: all match the same way. */
data class FilterPack(
    val id: String,
    val words: List<PackWord> = emptyList(),
    val genreIds: Set<Int> = emptySet(),
    /** Addon genre names, matched whole and case-insensitive. */
    val genreNames: Set<String> = emptySet(),
    val keywordIds: Set<Int> = emptySet(),
    /** The keywords' names in file order, for the pack's page (built-in packs; empty otherwise). */
    val keywordNames: List<String> = emptyList(),
    /** P1: TMDB's adult flag. */
    val adult: Boolean = false,
    /** P15: TMDB Animation (16) with original language Japanese. */
    val japaneseAnimation: Boolean = false,
    /** D30: titles this pack never hides, as `movie:<tmdbId>` / `tv:<tmdbId>`. */
    val allowTitles: Set<String> = emptySet(),
    /** Their display names, for the pack's page. */
    val allowTitleNames: List<String> = emptyList()
)

/** What a title shows the matcher: its own text and genres, plus its TMDB tags when known. */
data class FilterSubject(
    val name: String,
    val originalTitle: String? = null,
    val description: String? = null,
    val genreNames: List<String> = emptyList(),
    /** TMDB genre ids a list row carries (`genre_ids`) before any tag lookup. */
    val genreIds: Set<Int> = emptySet(),
    /** TMDB's adult flag a list row carries (`adult`) before any tag lookup. */
    val adult: Boolean = false,
    val tags: TitleTags? = null,
    /** `movie:<tmdbId>` or `tv:<tmdbId>` when known, for the allowlist; null never matches one. */
    val tmdbKey: String? = null
)

/**
 * Spec 117 P3: the compiled packs. Text is lowercased and split into tokens on anything that is not a
 * letter or number, so every match is whole-word; pack words are tokenised the same way, and phrases are
 * checked token by token. Linear in the text's length; built once per settings value; safe to share.
 */
class ContentFilter(val packs: List<FilterPack>) {

    private class Phrase(val pack: Int, val rest: List<String>)

    // first token -> phrases starting with it (single words have an empty rest)
    private val textIndex = HashMap<String, MutableList<Phrase>>()
    private val tagIndex = HashMap<String, MutableList<Phrase>>()

    // a keyword's whole (token-normalised) name -> packs with that word, any flags
    private val wholeNames = HashMap<String, MutableList<Int>>()
    private val keywordPacks = HashMap<Int, MutableList<Int>>()
    private val genrePacks = HashMap<Int, MutableList<Int>>()
    private val genreNamePacks = HashMap<String, MutableList<Int>>()

    // keyword id -> the packs its name hits, whole or by tag-name words (names never change for an id):
    // Home judges every drawn title on every pass, so a keyword is matched once, not per pass (S4b).
    private val keywordNameHits = ConcurrentHashMap<Int, IntArray>()

    init {
        packs.forEachIndexed { index, pack ->
            pack.words.forEach { word ->
                val tokens = tokenize(word.text)
                if (tokens.isEmpty()) return@forEach
                val phrase = Phrase(index, tokens.drop(1))
                if (word.textToo) textIndex.getOrPut(tokens[0]) { mutableListOf() } += phrase
                if (word.tagNameToo) tagIndex.getOrPut(tokens[0]) { mutableListOf() } += phrase
                wholeNames.getOrPut(tokens.joinToString(" ")) { mutableListOf() } += index
            }
            pack.keywordIds.forEach { keywordPacks.getOrPut(it) { mutableListOf() } += index }
            pack.genreIds.forEach { genrePacks.getOrPut(it) { mutableListOf() } += index }
            pack.genreNames.forEach { genreNamePacks.getOrPut(it.trim().lowercase(Locale.ROOT)) { mutableListOf() } += index }
        }
    }

    val isEmpty: Boolean get() = packs.isEmpty()

    /** The id of the first pack (in [packs] order) that hides [subject], or null. */
    fun firstHit(subject: FilterSubject): String? {
        val hit = hitMask(subject)
        val index = hit.indexOfFirst { it }
        return if (index < 0) null else packs[index].id
    }

    /** Every pack that would hide [subject], in [packs] order (S10's preview). */
    fun hits(subject: FilterSubject): List<String> {
        val hit = hitMask(subject)
        return packs.filterIndexed { index, _ -> hit[index] }.map { it.id }
    }

    private fun hitMask(subject: FilterSubject): BooleanArray {
        val hit = BooleanArray(packs.size)
        fun mark(index: Int) {
            if (!hit[index] && subject.tmdbKey !in packs[index].allowTitles) hit[index] = true
        }

        val text = buildString {
            append(subject.name)
            subject.originalTitle?.let { append(' ').append(it) }
            subject.description?.let { append(' ').append(it) }
        }
        matchPhrases(tokenize(capAtWord(text)), textIndex, ::mark)

        subject.genreNames.forEach { name -> genreNamePacks[name.trim().lowercase(Locale.ROOT)]?.forEach(::mark) }
        subject.genreIds.forEach { id -> genrePacks[id]?.forEach(::mark) }
        if (subject.adult) packs.forEachIndexed { index, pack -> if (pack.adult) mark(index) }

        val tags = subject.tags
        if (tags != null) {
            tags.genreIds.forEach { id -> genrePacks[id]?.forEach(::mark) }
            var i = 0
            for (id in tags.keywordIds) {
                val name = tags.keywordNames.getOrNull(i++)
                keywordPacks[id]?.forEach(::mark)
                if (name.isNullOrBlank()) continue // a name restored later is tokenised then, not cached empty
                keywordNameHits.getOrPut(id) { nameHits(name) }.forEach(::mark)
            }
            val japaneseAnimation = ANIMATION_GENRE_ID in tags.genreIds && tags.originalLanguage == "ja"
            packs.forEachIndexed { index, pack ->
                if (pack.adult && tags.adult) mark(index)
                if (pack.japaneseAnimation && japaneseAnimation) mark(index)
            }
        }
        return hit
    }

    private fun nameHits(name: String): IntArray {
        val tokens = tokenize(name)
        val found = BooleanArray(packs.size)
        wholeNames[tokens.joinToString(" ")]?.forEach { found[it] = true }
        matchPhrases(tokens, tagIndex) { found[it] = true }
        return found.indices.filter { found[it] }.toIntArray()
    }

    private inline fun matchPhrases(
        tokens: List<String>,
        index: Map<String, List<Phrase>>,
        mark: (Int) -> Unit
    ) {
        for (i in tokens.indices) {
            val candidates = index[tokens[i]] ?: continue
            for (phrase in candidates) {
                val rest = phrase.rest
                if (i + rest.size > tokens.lastIndex) continue
                var ok = true
                for (k in rest.indices) {
                    if (tokens[i + 1 + k] != rest[k]) {
                        ok = false
                        break
                    }
                }
                if (ok) mark(phrase.pack)
            }
        }
    }

    companion object {
        const val MAX_TEXT_CHARS = 2_000

        /** The first [MAX_TEXT_CHARS] chars, not ending in a cut word ("sextant" cut to "sex" must not match). */
        internal fun capAtWord(text: String): String {
            if (text.length <= MAX_TEXT_CHARS) return text
            var end = MAX_TEXT_CHARS
            if (Character.isLowSurrogate(text[end])) end-- // never split a surrogate pair
            if (isWordChar(text.codePointAt(end))) {
                while (end > 0 && isWordChar(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
            }
            return text.substring(0, end)
        }
        const val ANIMATION_GENRE_ID = 16

        /**
         * Lowercase (Locale.ROOT) tokens split on every character that is not a letter or number, so
         * spaces, hyphens and apostrophes all separate. Scripts without spaces stay one token (R9-m4).
         */
        fun tokenize(text: String): List<String> {
            val tokens = ArrayList<String>()
            val lower = text.lowercase(Locale.ROOT)
            val current = StringBuilder()
            var i = 0
            while (i < lower.length) {
                val cp = lower.codePointAt(i)
                if (isWordChar(cp)) {
                    current.appendCodePoint(cp)
                } else if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.setLength(0)
                }
                i += Character.charCount(cp)
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }

        // \p{L} and \p{N}, as P3 states.
        private fun isWordChar(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }
}
