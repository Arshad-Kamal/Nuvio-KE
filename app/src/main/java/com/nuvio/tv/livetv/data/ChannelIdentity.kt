package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uPlaylist
import java.text.Normalizer
import java.util.Locale

/**
 * Channel identity rules (Spec 34 D9): stable keys and the normalisers the EPG match chain uses.
 * The name normaliser is the one the 2026-10-17 derisk match study measured (`match.py`):
 * NFKC, lower case, bracketed parts dropped, `&`/`+` spelled out, quality tokens dropped, then
 * every non-letter/digit removed (spaces too: "Star Jalsha HD" and "STAR JALSHA" both give
 * "starjalsha").
 *
 * No `java.util.regex` here (Spec 115 S8, D7): on Android every `Regex` match allocates an ICU
 * matcher whose native memory only a GC plus the cleaner thread frees, and these functions run for
 * every channel and guide id on every match pass; at 41k channels that churn got the app killed on
 * a 1 GB TV. The hand-written scans keep the old patterns' Android (ICU) behaviour: `.` and `$`
 * honour ICU's line terminators (U+000A-000D, U+0085, U+2028, U+2029, CR LF) and `\d` any
 * decimal digit. ICU's quality-token `\b` is approximated by whole words (see [nameWords]); it
 * differs only when an exotic character touches a token: a combining mark or format character
 * before it, a connector other than `_`, a surviving No number, or an Alphabetic symbol.
 */
object ChannelIdentity {

    /** tvg-id normalised for matching and keys: trimmed, `@feed` dropped, lower case; null when blank. */
    fun normalizeTvgId(raw: String?): String? =
        raw?.trim()?.substringBefore('@')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

    /**
     * The ids a tvg-id matches guide ids by, most specific first (Spec 55 S3): the stem joined to
     * its feed when it has a country tag and a letters-or-digits feed (`9Gem.au@Sydney` ->
     * `9gemsydney.au`, open-epg / epgshare01 key regional feeds that way), then [normalizeTvgId].
     */
    fun tvgIdKeys(raw: String?): List<String> {
        val plain = normalizeTvgId(raw) ?: return emptyList()
        val feed = raw.orEmpty().substringAfter('@', "").trim()
        val (stem, country) = countryTagged(plain, digit = false) ?: return listOf(plain)
        if (!isFeed(feed)) return listOf(plain)
        return listOf("$stem${feed.lowercase(Locale.ROOT)}.$country", plain)
    }

    /**
     * An id's loose form (Spec 55 S3; guide ids and tvg-id keys alike): the name-normalised stem,
     * a dot, then the country tag with a trailing digit dropped, so epgshare01's `BBC.Two.HD.uk` /
     * `CNN.HD.us2` meet iptv-org's `BBCTwo.uk` / `CNN.us`. Bracketed words stay (only the brackets
     * go), so `HBO.(Pacific).us2` keys as `hbopacific.us`, apart from `HBO.us2`. Null without a
     * country tag or when the stem normalises to nothing. The country stays in the key, so `NTV.bd`
     * never meets `NTV.in`.
     */
    fun looseId(raw: String?): String? {
        val (stem, country) = countryTagged(normalizeTvgId(raw) ?: return null, digit = true) ?: return null
        val name = normalizeName(blankBrackets(stem)).ifEmpty { return null }
        return "$name.$country"
    }

    fun normalizeName(raw: String?): String = nameWords(raw).joinToString("")

    /**
     * [normalizeName]'s words before they are joined ("Al Jazeera English" -> al, jazeera, english):
     * a closed `[..]`, `(..)` or `{..}` span is dropped (its neighbours join), `&`/`+` become the
     * words "and"/"plus", words are runs of letters, numbers, marks and `_`, and a word that is a
     * quality token (`hd`, `4k`, `1080p`...) is dropped.
     */
    fun nameWords(raw: String?): List<String> {
        val s = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        val word = StringBuilder()
        fun endWord() {
            if (word.isEmpty()) return
            val w = word.toString()
            if (w !in QUALITY) words += w
            word.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val close = when (s[i]) { '[' -> ']'; '(' -> ')'; '{' -> '}'; else -> null }
            val closeAt = if (close != null) s.indexOf(close, i + 1) else -1
            if (closeAt >= 0) {
                i = closeAt + 1
                continue
            }
            val cp = s.codePointAt(i)
            when {
                cp == '&'.code -> { endWord(); words += "and" }
                cp == '+'.code -> { endWord(); words += "plus" }
                isWordChar(cp) -> word.appendCodePoint(cp)
                else -> endWord()
            }
            i += Character.charCount(cp)
        }
        endWord()
        return words
    }

    /** Country code from an iptv-org style tvg-id (`StarJalsha.in@SD` -> `in`); null otherwise. */
    fun countryOfTvgId(raw: String?): String? =
        normalizeCountry(normalizeTvgId(raw)?.substringAfterLast('.', ""))

    /** The one country form compared anywhere: two lower-case letters (`IN` -> `in`); null otherwise. */
    fun normalizeCountry(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length == 2 && it[0] in 'a'..'z' && it[1] in 'a'..'z' }

    /** `Star Jalsha HD.in` -> `Star Jalsha HD`, `CNN.HD.us2` -> `CNN.HD` (guide ids double as names). */
    fun stripCountrySuffix(raw: String): String {
        // The suffix ends at the end, or before one final line terminator (regex `$`).
        val end = raw.length - finalTerminatorLength(raw)
        val dot = countryTagStart(raw, end, digit = true, lowerOnly = false) ?: return raw
        return raw.removeRange(dot, end)
    }

    /**
     * The provider channel ids in [ch]'s stream URLs, lower case, in stream order: the jmp2.uk
     * redirector carries the id that i.mjh.nz's per-service XMLTV uses as its channel id (Pluto,
     * Spec 38 S1; Plex and Samsung TV Plus, Spec 55 S2). The shapes cannot collide across services.
     */
    fun providerIds(ch: Channel): List<String> =
        ch.streams.mapNotNull { stream -> providerId(stream.url)?.second }.distinct()

    /** The services whose jmp2.uk ids [ch]'s stream URLs carry. */
    fun services(ch: Channel): Set<GuideService> =
        ch.streams.mapNotNullTo(HashSet()) { stream -> providerId(stream.url)?.first }

    /**
     * A stream URL's service and lower-case provider id: `jmp2.uk/plu-<24 hex>`,
     * `jmp2.uk/plex-<24 hex>-<24 hex>`, `jmp2.uk/stvp-<id>` or `jmp2.uk/sam-<id>` (Samsung: 2
     * letters then 6-14 letters or digits); the id must end the path segment. Null for anything
     * else: playlist URLs are untrusted, so only these anchored, bounded shapes count.
     */
    internal fun providerId(url: String): Pair<GuideService, String>? {
        val p = when {
            url.startsWith("http://jmp2.uk/", ignoreCase = true) -> 15
            url.startsWith("https://jmp2.uk/", ignoreCase = true) -> 16
            else -> return null
        }
        fun id(service: GuideService, start: Int, length: Int): Pair<GuideService, String>? =
            if (endsSegment(url, start + length)) {
                service to url.substring(start, start + length).lowercase(Locale.ROOT)
            } else {
                null
            }
        fun at(prefix: String) = url.startsWith(prefix, p, ignoreCase = true)
        return when {
            at("plu-") -> if (isHex(url, p + 4, 24)) id(GuideService.PLUTO, p + 4, 24) else null
            at("plex-") ->
                if (isHex(url, p + 5, 24) && url.getOrNull(p + 29) == '-' && isHex(url, p + 30, 24)) {
                    id(GuideService.PLEX, p + 5, 49)
                } else {
                    null
                }
            at("sam-") || at("stvp-") -> {
                val start = p + if (at("sam-")) 4 else 5
                if (!isAsciiLetter(url.getOrNull(start)) || !isAsciiLetter(url.getOrNull(start + 1))) return null
                var run = 0
                while (isAsciiLetterOrDigit(url.getOrNull(start + 2 + run))) run++
                if (run in 6..14) id(GuideService.SAMSUNG, start, 2 + run) else null
            }
            else -> null
        }
    }

    /**
     * Stable key of an M3U item: the normalised tvg-id when it has one (feeds of one channel share
     * it), else the stream URL. Scoped by [sourceId] so two lists never collide.
     */
    fun m3uKey(sourceId: String, tvgId: String?, url: String): String =
        normalizeTvgId(tvgId)?.let { "$sourceId#id:$it" } ?: "$sourceId#url:$url"

    /** Stable key of a Stremio `tv` meta: id plus name, since addons reuse ids (RTVE `tve:RNE.TV`). */
    fun stremioKey(sourceId: String, id: String, name: String): String = "$sourceId#st:$id|${name.trim()}"

    /**
     * The channels of one playlist: items sharing a key become one channel whose streams keep
     * file order (duplicate URLs dropped); name, logo, groups, number and attributes come from the
     * first item. Empty maps are stored as null (no cache bytes for plain lists).
     */
    fun channelsFromM3u(sourceId: String, playlist: M3uPlaylist): List<Channel> {
        val byKey = LinkedHashMap<String, Channel>()
        for (e in playlist.entries) {
            val key = m3uKey(sourceId, e.tvgId, e.url)
            val stream = LiveStream(e.url, e.headers, e.drm, e.kodiProps.ifEmpty { null }, e.tags.ifEmpty { null })
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> Channel(
                    key = key,
                    sourceId = sourceId,
                    name = e.title,
                    tvgId = e.tvgId,
                    country = countryOfTvgId(e.tvgId),
                    logo = e.tvgLogo,
                    groups = e.groups,
                    channelNumber = e.channelNumber,
                    altNames = listOfNotNull(e.tvgName?.takeIf { it != e.title }),
                    streams = listOf(stream),
                    attrs = e.attrs.ifEmpty { null }
                )
                existing.streams.any { it.url == e.url } -> existing
                else -> existing.copy(streams = existing.streams + stream)
            }
        }
        return byKey.values.toList()
    }

    /**
     * `(.+)\.([a-z]{2})` matched against the whole of [s] (`\d?` after it when [digit]): the stem
     * (no line terminator) and the country tag, or null.
     */
    private fun countryTagged(s: String, digit: Boolean): Pair<String, String>? {
        val dot = countryTagStart(s, s.length, digit, lowerOnly = true) ?: return null
        if (dot == 0 || (0 until dot).any { isLineTerminator(s[it]) }) return null
        return s.substring(0, dot) to s.substring(dot + 1, dot + 3)
    }

    /**
     * Where `\.[a-z]{2}` (`[a-zA-Z]` unless [lowerOnly]; then `\d?` when [digit]) ending at [end]
     * starts in [s], or null. A trailing decimal digit must be the `\d`: letters cannot take it.
     */
    private fun countryTagStart(s: String, end: Int, digit: Boolean, lowerOnly: Boolean): Int? {
        var e = end
        if (digit && e > 0) {
            val cp = s.codePointBefore(e)
            if (Character.isDigit(cp)) e -= Character.charCount(cp)
        }
        if (e < 3 || s[e - 3] != '.') return null
        val ok = if (lowerOnly) {
            s[e - 2] in 'a'..'z' && s[e - 1] in 'a'..'z'
        } else {
            isAsciiLetter(s[e - 2]) && isAsciiLetter(s[e - 1])
        }
        return if (ok) e - 3 else null
    }

    /** `[A-Za-z0-9]{1,32}`. */
    private fun isFeed(s: String): Boolean = s.length in 1..32 && s.all { isAsciiLetterOrDigit(it) }

    private fun blankBrackets(s: String): String {
        val chars = s.toCharArray()
        for (i in chars.indices) if (chars[i] in "()[]{}") chars[i] = ' '
        return String(chars)
    }

    /** `[\p{L}\p{N}\p{M}_]`. */
    private fun isWordChar(cp: Int): Boolean = cp == '_'.code || when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** A provider id ends its path segment: end of the URL (or a final line terminator), or `.`, `/`, `?`, `#`. */
    private fun endsSegment(s: String, i: Int): Boolean =
        i == s.length || s[i] in "./?#" || finalTerminatorLength(s) == s.length - i

    private fun isHex(s: String, start: Int, length: Int): Boolean =
        start + length <= s.length &&
            (start until start + length).all { s[it] in '0'..'9' || s[it] in 'a'..'f' || s[it] in 'A'..'F' }

    private fun isAsciiLetter(c: Char?): Boolean = c != null && (c in 'a'..'z' || c in 'A'..'Z')

    private fun isAsciiLetterOrDigit(c: Char?): Boolean = isAsciiLetter(c) || (c != null && c in '0'..'9')

    private fun isLineTerminator(c: Char): Boolean = c in '\n'..'\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    /** Length of [s]'s final line terminator (CR LF counts as one): 0, 1 or 2. */
    private fun finalTerminatorLength(s: String): Int = when {
        s.endsWith("\r\n") -> 2
        s.isNotEmpty() && isLineTerminator(s.last()) -> 1
        else -> 0
    }

    private val QUALITY = setOf(
        "uhd", "fhd", "hd", "sd", "4k", "8k", "hevc", "h265", "1080p", "720p", "576p", "480p", "360p"
    )
}

/** A FAST service whose i.mjh.nz guide keys channels by the id in its jmp2.uk stream URL. */
enum class GuideService { PLUTO, PLEX, SAMSUNG }
