package com.nuvio.tv.livetv.parse

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.net.URLDecoder

/** One playlist item as written in the file; identity and EPG matching happen later (Spec 34 L4). */
data class M3uEntry(
    val title: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val groups: List<String> = emptyList(),
    val channelNumber: String? = null,
    /** Request headers from `#EXTVLCOPT`, `#KODIPROP` stream headers and a `|`-suffix on the URL. */
    val headers: Map<String, String> = emptyMap(),
    /** True when a `#KODIPROP` names a DRM licence (such channels are out of v1 scope). */
    val drm: Boolean = false
)

data class M3uPlaylist(
    /** EPG URLs from the header's `x-tvg-url` / `url-tvg` (comma-separated lists split). */
    val epgUrls: List<String>,
    val entries: List<M3uEntry>
)

/**
 * Single-pass M3U/M3U8 playlist parser (Spec 34 D10). Input is untrusted: unknown directives
 * and attributes are ignored, a missing `#EXTM3U` header is tolerated, URLs are not validated
 * (some hold unfilled placeholders such as `[DEVICE_ID]`), and at most [MAX_ENTRIES] entries and
 * [MAX_EPG_URLS] header EPG URLs are kept. The caller caps the download size.
 */
object M3uParser {
    const val MAX_ENTRIES = 100_000
    const val MAX_EPG_URLS = 5

    fun parse(text: String, maxEntries: Int = MAX_ENTRIES): M3uPlaylist =
        parse(StringReader(text), maxEntries)

    fun parse(reader: Reader, maxEntries: Int = MAX_ENTRIES): M3uPlaylist {
        val epgUrls = LinkedHashSet<String>()
        val entries = ArrayList<M3uEntry>()
        var pending = Pending()
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        var first = true
        while (entries.size < maxEntries) {
            var line = buffered.readLine() ?: break
            if (first) {
                line = line.removePrefix("\uFEFF")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substring("#EXTM3U".length))
                    listOfNotNull(attrs["x-tvg-url"], attrs["url-tvg"])
                        .flatMap { it.split(',') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { if (epgUrls.size < MAX_EPG_URLS) epgUrls += it }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // A new #EXTINF starts a new item; directives seen before it (KODIPROP) stay bound,
                    // unless an earlier #EXTINF never got its URL: then its directives are dropped.
                    pending = if (pending.extinf != null) Pending(extinf = parseExtinf(line))
                    else pending.copy(extinf = parseExtinf(line))
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pending = pending.copy(extGroup = line.substring("#EXTGRP:".length).trim().ifEmpty { null })
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) ->
                    vlcOptHeader(line.substring("#EXTVLCOPT:".length))?.let { (k, v) ->
                        pending = pending.copy(headers = pending.headers + (k to v))
                    }
                line.startsWith("#KODIPROP:", ignoreCase = true) ->
                    pending = applyKodiProp(pending, line.substring("#KODIPROP:".length))
                line.startsWith("#") -> Unit
                else -> {
                    entries += pending.toEntry(line)
                    pending = Pending()
                }
            }
        }
        return M3uPlaylist(epgUrls.toList(), entries)
    }

    private data class Extinf(val title: String, val attrs: Map<String, String>)

    private data class Pending(
        val extinf: Extinf? = null,
        val extGroup: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val drm: Boolean = false
    ) {
        fun toEntry(rawUrl: String): M3uEntry {
            val (url, pipeHeaders) = splitPipeHeaders(rawUrl)
            val attrs = extinf?.attrs.orEmpty()
            val groups = attrs["group-title"]
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(extGroup)
            val tvgName = attrs["tvg-name"]?.ifBlank { null }
            val title = extinf?.title?.ifBlank { null } ?: tvgName ?: url
            return M3uEntry(
                title = title,
                url = url,
                tvgId = attrs["tvg-id"]?.ifBlank { null },
                tvgName = tvgName,
                tvgLogo = attrs["tvg-logo"]?.ifBlank { null },
                groups = groups,
                channelNumber = attrs["tvg-chno"]?.ifBlank { null },
                headers = headers + pipeHeaders,
                drm = drm
            )
        }
    }

    /** `#EXTINF:-1 key="v" key2=v2,Title`: the title starts after the first comma outside quotes. */
    private fun parseExtinf(line: String): Extinf {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var commaAt = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) {
                commaAt = i
                break
            }
        }
        val head = if (commaAt >= 0) body.substring(0, commaAt) else body
        val title = if (commaAt >= 0) body.substring(commaAt + 1).trim() else ""
        return Extinf(title, parseAttributes(head))
    }

    /** `key="quoted value"` or `key=unquoted`; keys lower-cased; a leading duration token is skipped. */
    internal fun parseAttributes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && text[i] != '=' && !text[i].isWhitespace()) i++
            if (i >= n || text[i] != '=') continue
            val key = text.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < n && text[i] == '"') {
                val end = text.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = text.substring(i + 1, end)
                i = (end + 1).coerceAtMost(n)
            } else {
                val start = i
                while (i < n && !text[i].isWhitespace()) i++
                value = text.substring(start, i)
            }
            if (key.isNotEmpty()) out[key] = value.trim()
        }
        return out
    }

    private fun vlcOptHeader(option: String): Pair<String, String>? {
        val key = option.substringBefore('=').trim().lowercase()
        val value = option.substringAfter('=', "").trim().ifEmpty { return null }
        return when (key) {
            "http-user-agent" -> "User-Agent" to value
            "http-referrer", "http-referer" -> "Referer" to value
            else -> null
        }
    }

    private fun applyKodiProp(pending: Pending, prop: String): Pending {
        val key = prop.substringBefore('=').trim().lowercase()
        val value = prop.substringAfter('=', "").trim()
        return when (key) {
            // license_* up to Kodi 20; drm_legacy / drm from Kodi 21.
            "inputstream.adaptive.license_type", "inputstream.adaptive.license_key",
            "inputstream.adaptive.drm_legacy", "inputstream.adaptive.drm" ->
                if (value.isNotEmpty()) pending.copy(drm = true) else pending
            "inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers" ->
                pending.copy(headers = pending.headers + parseHeaderPairs(value))
            else -> pending
        }
    }

    /** Kodi-style `url|User-Agent=x&Referer=y`; values URL-decoded. */
    private fun splitPipeHeaders(raw: String): Pair<String, Map<String, String>> {
        val bar = raw.indexOf('|')
        if (bar < 0) return raw to emptyMap()
        return raw.substring(0, bar).trim() to parseHeaderPairs(raw.substring(bar + 1))
    }

    private fun parseHeaderPairs(text: String): Map<String, String> =
        text.split('&')
            .mapNotNull { pair ->
                val k = pair.substringBefore('=').trim()
                val v = pair.substringAfter('=', "").trim()
                if (k.isEmpty() || v.isEmpty()) null else canonicalHeader(decode(k)) to decode(v)
            }
            .toMap()

    private fun canonicalHeader(name: String): String = when (name.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        else -> name
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
}
