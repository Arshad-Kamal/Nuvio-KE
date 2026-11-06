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
 */
object ChannelIdentity {

    /** tvg-id normalised for matching and keys: trimmed, `@feed` dropped, lower case; null when blank. */
    fun normalizeTvgId(raw: String?): String? =
        raw?.trim()?.substringBefore('@')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

    fun normalizeName(raw: String?): String = nameWords(raw).joinToString("")

    /** [normalizeName]'s words before they are joined ("Al Jazeera English" -> al, jazeera, english). */
    fun nameWords(raw: String?): List<String> {
        var s = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        s = BRACKETED.replace(s, "")
        s = s.replace("&", " and ").replace("+", " plus ")
        s = QUALITY.replace(s, " ")
        return s.split(NON_WORD).filter { it.isNotEmpty() }
    }

    /** Country code from an iptv-org style tvg-id (`StarJalsha.in@SD` -> `in`); null otherwise. */
    fun countryOfTvgId(raw: String?): String? =
        normalizeCountry(normalizeTvgId(raw)?.substringAfterLast('.', ""))

    /** The one country form compared anywhere: two lower-case letters (`IN` -> `in`); null otherwise. */
    fun normalizeCountry(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { COUNTRY.matches(it) }

    /** `Star Jalsha HD.in` -> `Star Jalsha HD` (free-epg.de ids double as names). */
    fun stripCountrySuffix(raw: String): String = COUNTRY_SUFFIX.replace(raw, "")

    /**
     * The Pluto TV channel ids in [ch]'s stream URLs, lower case, in stream order: the `jmp2.uk/plu-<id>`
     * redirector carries the id that i.mjh.nz's Pluto XMLTV uses as its channel id (Spec 38 S1).
     */
    fun plutoIds(ch: Channel): List<String> =
        ch.streams.mapNotNull { PLUTO_URL.find(it.url)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }.distinct()

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
     * file order (duplicate URLs dropped); name, logo, groups and number come from the first item.
     */
    fun channelsFromM3u(sourceId: String, playlist: M3uPlaylist): List<Channel> {
        val byKey = LinkedHashMap<String, Channel>()
        for (e in playlist.entries) {
            val key = m3uKey(sourceId, e.tvgId, e.url)
            val stream = LiveStream(e.url, e.headers, e.drm)
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
                    streams = listOf(stream)
                )
                existing.streams.any { it.url == e.url } -> existing
                else -> existing.copy(streams = existing.streams + stream)
            }
        }
        return byKey.values.toList()
    }

    // The trailing `}` must stay escaped: Android 11's ICU-backed java.util.regex rejects a bare
    // `}` outside a class (PatternSyntaxException at first use, a crash on Add broadcasters);
    // the desktop JVM accepts both, so unit tests cannot catch a regression here.
    private val BRACKETED = Regex("""\[[^\]]*]|\([^)]*\)|\{[^}]*\}""")
    private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|1080p|720p|576p|480p|360p)\b""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}\p{M}_]+""")
    private val COUNTRY = Regex("[a-z]{2}")
    private val COUNTRY_SUFFIX = Regex("""\.[a-zA-Z]{2}$""")
    private val PLUTO_URL = Regex("""^https?://jmp2\.uk/plu-([0-9a-f]{24})(?:[./?#]|$)""", RegexOption.IGNORE_CASE)
}
