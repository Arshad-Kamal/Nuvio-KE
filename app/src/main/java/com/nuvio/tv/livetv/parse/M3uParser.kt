package com.nuvio.tv.livetv.parse

import com.nuvio.tv.core.di.JsonNestingDepthCounter
import com.nuvio.tv.core.di.UNTRUSTED_JSON_MAX_DEPTH
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /**
     * Request headers, merged in this order (later wins): `#EXTVLCOPT`, header attributes of the
     * `#EXTINF`, `#EXTHTTP`, `#KODIPROP` common_headers, stream_headers / manifest_headers, `|` suffix.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * True when a `#KODIPROP` names a licence key system other than `none` (Spec 74 S1):
     * `license_type`, `drm_legacy` or `drm`. `license_key` alone (HLS AES-128 key settings) is not DRM.
     */
    val drm: Boolean = false,
    /** Every `#KODIPROP` as written: key lower-cased, last line wins (licence details live here). */
    val kodiProps: Map<String, String> = emptyMap(),
    /** The `#EXTINF` attributes not mapped above or into [headers], as written (catchup-*, tvg-shift, ...). */
    val attrs: Map<String, String> = emptyMap(),
    /** The entry's other directives as written: `vlc:<option>` for non-header `#EXTVLCOPT`, `exthttp` for an unread `#EXTHTTP`. */
    val tags: Map<String, String> = emptyMap()
)

data class M3uPlaylist(
    /** EPG URLs from the header's `x-tvg-url` / `url-tvg` (comma-separated lists split). */
    val epgUrls: List<String>,
    val entries: List<M3uEntry>,
    /** The other `#EXTM3U` attributes as written (catchup defaults, tvg-shift, ...); later header lines win. */
    val attrs: Map<String, String> = emptyMap()
)

/**
 * Single-pass M3U/M3U8 playlist parser (Spec 34 D10). Input is untrusted. It deletes nothing a
 * stream may need to play (Spec 74 D1): every `#EXTINF` attribute, `#KODIPROP`, `#EXTVLCOPT` and
 * `#EXTHTTP` is kept on the entry, and `#EXTM3U` attributes on the playlist. Other `#` lines
 * (comments, `#PLAYLIST`, `#EXTALB`/`#EXTART`/`#EXTGENRE`/`#EXTIMG`, `#EXT-X-*` HLS tags) cannot
 * affect a request and are ignored; `#EXTATTRFROMURL` is deferred (Spec 74). Nothing parsed is
 * logged: values can hold licence keys and credentials (KT-SEC-02). A missing `#EXTM3U` header is tolerated, URLs are not validated
 * (some hold unfilled placeholders such as `[DEVICE_ID]`), Xtream movie and series items are skipped
 * ([XtreamUrls.isVod], Spec 78 D1), and at most [MAX_ENTRIES] entries and
 * [MAX_EPG_URLS] header EPG URLs are kept. The caller caps the download size (512 MiB, far more than
 * memory could hold), so memory is bounded here (Spec 137): [MAX_LINE_CHARS] per line,
 * [MAX_ITEM_CHARS] per item, [MAX_HEADER_CHARS] over the `#EXTM3U` lines and [MAX_KEPT_CHARS] over
 * the kept items; movie and series items cost only their own item while read.
 */
object M3uParser {
    const val MAX_ENTRIES = 100_000
    /** Bounds untrusted input only: Free-TV's header names 101 guides (Spec 55 S1 picks among them). */
    const val MAX_EPG_URLS = 200
    /** Spec 137 D2: chars kept of one line (real lines are ~0.1-4 KiB); a longer line drops its item. */
    const val MAX_LINE_CHARS = 64 * 1024
    /** Spec 137 D7: chars of one item's lines (`#EXTINF`, directives, URL); past it the item is dropped. */
    const val MAX_ITEM_CHARS = 256 * 1024
    /** Spec 137 D8: chars of the `#EXTM3U` lines read (Free-TV's 101-guide header is ~10 KiB); later ones are ignored. */
    const val MAX_HEADER_CHARS = 64 * 1024
    /**
     * Spec 137 D3: chars of the kept items' lines; the item that passes it ends the parse with what was kept, as
     * [MAX_ENTRIES] does. A UTF-8 file under the old 32 MiB download cap never reaches it.
     */
    const val MAX_KEPT_CHARS = 32L * 1024 * 1024

    fun parse(text: String, maxEntries: Int = MAX_ENTRIES): M3uPlaylist =
        parse(StringReader(text), maxEntries)

    fun parse(reader: Reader, maxEntries: Int = MAX_ENTRIES, maxKeptChars: Long = MAX_KEPT_CHARS): M3uPlaylist {
        val epgUrls = LinkedHashSet<String>()
        val playlistAttrs = LinkedHashMap<String, String>()
        val entries = ArrayList<M3uEntry>()
        var pending = Pending()
        // Chars of the current item's lines so far, and of every kept item.
        var itemChars = 0
        var keptChars = 0L
        var headerChars = 0
        // Spec 137 D7: an item was dropped; ignore lines until the end of its URL line, or until the
        // next #EXTINF when the dropped item had its own; [extinfOwed]: it had not, so the next is its own.
        var skipping = false
        var extinfOwed = false
        val lines = LineReader(reader, MAX_LINE_CHARS)
        var first = true
        while (entries.size < maxEntries) {
            var line = lines.next() ?: break
            if (first) {
                line = line.removePrefix("\uFEFF")
                first = false
            }
            line = line.trim()
            val header = line.startsWith("#EXTM3U", ignoreCase = true)
            val extinf = line.startsWith("#EXTINF", ignoreCase = true)
            val url = line.isNotEmpty() && !line.startsWith("#")
            val directive = if (url || extinf) null else ITEM_DIRECTIVES.firstOrNull { line.startsWith(it, ignoreCase = true) }
            // Spec 137 D7: an over-long line that is blank as far as it was kept is of no known kind: it drops the item.
            val overlongBlank = line.isEmpty() && lines.overlong
            // Blank lines, comments and other ignored # lines hold nothing, so only header and item lines are charged.
            if (header) {
                if (!lines.overlong && headerChars + line.length <= MAX_HEADER_CHARS) {
                    headerChars += line.length
                    readHeader(line, epgUrls, playlistAttrs)
                }
                continue
            }
            if (!url && !extinf && directive == null && (!overlongBlank || itemChars == 0)) continue
            if (skipping) {
                when {
                    url -> {
                        skipping = false
                        extinfOwed = false
                        continue
                    }
                    extinf && extinfOwed -> {
                        extinfOwed = false
                        continue
                    }
                    extinf -> skipping = false
                    else -> continue
                }
            }
            // A new #EXTINF starts a new item; directives seen before it (KODIPROP) stay bound,
            // unless an earlier #EXTINF never got its URL: then its directives are dropped.
            if (extinf && pending.extinf != null) {
                pending = Pending()
                itemChars = 0
            }
            itemChars += line.length
            if (lines.overlong || itemChars > MAX_ITEM_CHARS) {
                // Dropped at its URL line, the item is over; else its rest, URL included, is skipped.
                skipping = !url
                extinfOwed = !url && !extinf && pending.extinf == null
                pending = Pending()
                itemChars = 0
                continue
            }
            when {
                extinf -> pending.extinf = parseExtinf(line)
                directive != null -> {
                    val value = line.substring(directive.length)
                    when (directive) {
                        EXTGRP -> pending.extGroup = value.trim().ifEmpty { null }
                        EXTVLCOPT -> pending.addVlcOpt(value)
                        KODIPROP -> pending.addKodiProp(value)
                        else -> pending.addExtHttp(value.trim()) // EXTHTTP
                    }
                }
                else -> {
                    val (streamUrl, pipeHeaders) = splitPipeHeaders(line)
                    // Spec 78 D1: an Xtream movie or series item is not a channel; it never counts toward maxEntries.
                    if (!XtreamUrls.isVod(streamUrl)) {
                        keptChars += itemChars
                        if (keptChars > maxKeptChars) break
                        entries += pending.toEntry(streamUrl, pipeHeaders)
                    }
                    pending = Pending()
                    itemChars = 0
                }
            }
        }
        return M3uPlaylist(epgUrls.toList(), entries, playlistAttrs)
    }

    /** One `#EXTM3U` line into [epgUrls] (`x-tvg-url` / `url-tvg`, comma lists split) and [playlistAttrs] (the rest). */
    private fun readHeader(line: String, epgUrls: MutableSet<String>, playlistAttrs: MutableMap<String, String>) {
        val attrs = parseAttributes(line.substring("#EXTM3U".length))
        listOfNotNull(attrs["x-tvg-url"], attrs["url-tvg"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { if (epgUrls.size < MAX_EPG_URLS) epgUrls += it }
        playlistAttrs += attrs - EPG_ATTRS
    }

    /**
     * Lines of [reader] split as [BufferedReader.readLine] splits them (`\n`, `\r\n`, `\r`), read in bulk
     * (Spec 137 D2). At most [maxChars] of a line are kept; the rest is read and dropped, and [overlong]
     * says so for the line [next] just returned.
     */
    internal class LineReader(private val reader: Reader, private val maxChars: Int) {
        private val buf = CharArray(16 * 1024)
        private var pos = 0
        private var end = 0
        /** The last line ended in `\r`: a `\n` that follows belongs to it. */
        private var skipLf = false
        private val line = StringBuilder()
        var overlong = false
            private set

        /** The next line without its terminator; null at the end of input. */
        fun next(): String? {
            line.setLength(0)
            overlong = false
            var read = false
            while (true) {
                if (pos == end) {
                    end = reader.read(buf, 0, buf.size)
                    pos = 0
                    if (end < 0) {
                        end = 0
                        return if (read) line.toString() else null
                    }
                    continue
                }
                if (skipLf) {
                    skipLf = false
                    if (buf[pos] == '\n') {
                        pos++
                        continue
                    }
                }
                val start = pos
                while (pos < end && buf[pos] != '\n' && buf[pos] != '\r') pos++
                if (pos > start) {
                    read = true
                    val room = maxChars - line.length
                    if (pos - start > room) overlong = true
                    line.append(buf, start, minOf(pos - start, room))
                }
                if (pos < end) {
                    skipLf = buf[pos] == '\r'
                    pos++
                    return line.toString()
                }
            }
        }
    }

    private data class Extinf(val title: String, val attrs: Map<String, String>)

    /**
     * One item's directives so far, mutable so each line costs O(1) however many an item holds
     * (immutable `map + pair` per line was quadratic on a flooded item). Each header source is
     * kept apart so [toEntry] merges them in a fixed order; a fresh instance starts each item.
     */
    private class Pending {
        var extinf: Extinf? = null
        var extGroup: String? = null
        private val vlcHeaders = LinkedHashMap<String, String>()
        /** Keyed by lower-cased name, so a later line wins whatever its spelling. */
        private val httpHeaders = LinkedHashMap<String, Pair<String, String>>()
        private val kodiProps = LinkedHashMap<String, String>()
        private val tags = LinkedHashMap<String, String>()

        /** A header option (`http-user-agent=...`) becomes a header; any other option is kept as a `vlc:` tag. */
        fun addVlcOpt(option: String) {
            val key = option.substringBefore('=').trim().lowercase()
            if (key.isEmpty()) return
            val value = option.substringAfter('=', "").trim()
            val header = HEADER_OPTIONS[key]
            when {
                header == null -> tags["vlc:$key"] = value
                value.isNotEmpty() -> vlcHeaders[header] = value
            }
        }

        fun addKodiProp(prop: String) {
            val key = prop.substringBefore('=').trim().lowercase()
            if (key.isEmpty()) return
            val value = prop.substringAfter('=', "").trim()
            // Re-inserted so map order is each key's LAST line: toEntry merges the header props in that order.
            kodiProps.remove(key)
            kodiProps[key] = value
        }

        /**
         * `#EXTHTTP:{"User-Agent":"x","Cookie":"y"}` (OTT Navigator / TiviMate): string members become
         * headers. A line that is not a flat JSON object, or holds a member that is not a usable
         * string header, is also kept as written under the `exthttp` tag.
         */
        fun addExtHttp(value: String) {
            val obj = jsonObjectOrNull(value, maxDepth = 1)
            if (obj == null) {
                tags["exthttp"] = value
                return
            }
            val headers = obj.mapNotNull { (k, v) ->
                val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (k.isBlank() || text.isNullOrEmpty()) null else canonicalHeader(k.trim()) to text
            }
            for (header in headers) httpHeaders[header.first.lowercase()] = header
            if (headers.size != obj.size) tags["exthttp"] = value
        }

        /** [url] and [pipeHeaders] = the URL line split by [splitPipeHeaders]. */
        fun toEntry(url: String, pipeHeaders: Map<String, String>): M3uEntry {
            val attrs = extinf?.attrs.orEmpty()
            val groups = attrs["group-title"]
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(extGroup)
            val tvgName = attrs["tvg-name"]?.ifBlank { null }
            val title = extinf?.title?.ifBlank { null } ?: tvgName ?: fallbackTitle(url)
            val attrHeaders = attrs.mapNotNull { (k, v) -> HEADER_OPTIONS[k]?.takeIf { v.isNotEmpty() }?.let { it to v } }
            // KODIPROP headers come from the kept props, so a repeated line replaces the earlier one (as in Kodi).
            val commonHeaders = parseHeaderPairs(kodiProps[COMMON_HEADERS].orEmpty())
            val kodiHeaders = kodiProps.entries.filter { it.key in STREAM_HEADERS }.flatMap { parseHeaderPairs(it.value).toList() }
            val headers = mergeHeaders(
                vlcHeaders.toList(), attrHeaders, httpHeaders.values.toList(), commonHeaders.toList(), kodiHeaders, pipeHeaders.toList()
            )
            return M3uEntry(
                title = title,
                url = url,
                tvgId = attrs["tvg-id"]?.ifBlank { null },
                tvgName = tvgName,
                tvgLogo = attrs["tvg-logo"]?.ifBlank { null },
                groups = groups,
                channelNumber = attrs["tvg-chno"]?.ifBlank { null },
                headers = headers,
                drm = needsLicence(kodiProps),
                kodiProps = kodiProps.ifEmpty { emptyMap() },
                attrs = attrs.filterKeys { it !in MAPPED_ATTRS && it !in HEADER_OPTIONS },
                tags = tags.ifEmpty { emptyMap() }
            )
        }
    }

    /**
     * The name of an item with no title or tvg-name (Spec 78 Q7): `host · last path segment`
     * (`xt.example · 1003`), so an Xtream `user/pass` path or `user:pass@` never reaches the screen
     * (74/D3); the host alone when the last segment is empty. Split by hand, query and fragment
     * first, `\` read as `/` (as OkHttp does), `;` parameters dropped, so no parser's failure can
     * fall back to the whole URL. Without a host: the last segment after any `@`, else `-`.
     */
    internal fun fallbackTitle(url: String): String {
        val bare = url.replace('\\', '/').substringBefore('#').substringBefore('?')
        val schemeEnd = bare.indexOf("://")
        val rest = if (schemeEnd >= 0) bare.substring(schemeEnd + 3) else bare
        val authority = if (schemeEnd >= 0) rest.substringBefore('/').substringAfterLast('@') else ""
        val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val last = (if (schemeEnd >= 0) rest.substringAfter('/', "") else rest).substringAfterLast('/').substringBefore(';')
        return when {
            host.isEmpty() -> last.substringAfterLast('@').ifEmpty { "-" }
            last.isEmpty() -> host
            else -> "$host · $last"
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

    /**
     * [sources] in order, later wins; names compared case-insensitively (`cookie` from one source
     * and `Cookie` from another are one header), the winner's spelling kept.
     */
    private fun mergeHeaders(vararg sources: List<Pair<String, String>>): Map<String, String> {
        val byName = LinkedHashMap<String, Pair<String, String>>()
        for (source in sources) for (header in source) byName[header.first.lowercase()] = header
        return byName.values.toMap()
    }

    /**
     * Whether [props] name a licence key system other than `none` (Kodi inputstream.adaptive: old
     * `license_type`, Kodi 21 `drm_legacy` = `[key system]|...`, Kodi 22 `drm` = JSON keyed by key
     * system). `license_key` alone is HLS AES-128 key settings, and `none` is clear content: both
     * playable without a licence. An unreadable `drm` JSON names no key system.
     */
    internal fun needsLicence(props: Map<String, String>): Boolean =
        props["inputstream.adaptive.license_type"]?.let(::isKeySystem) == true ||
            props["inputstream.adaptive.drm_legacy"]?.substringBefore('|')?.let(::isKeySystem) == true ||
            props["inputstream.adaptive.drm"]
                ?.let { jsonObjectOrNull(it, UNTRUSTED_JSON_MAX_DEPTH) }
                ?.keys?.any(::isKeySystem) == true

    private fun isKeySystem(value: String): Boolean = value.trim().let { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }

    /** [text] as a JSON object; null when it is not one or nests past [maxDepth] (checked first, KT-JSON-03). */
    internal fun jsonObjectOrNull(text: String, maxDepth: Int): JsonObject? {
        if (!text.startsWith('{')) return null
        val depth = JsonNestingDepthCounter(maxDepth)
        if (!text.encodeToByteArray().all(depth::accept)) return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) { // SerializationException included; never logged (KT-JSON-04)
            null
        }
    }

    /** Kodi-style `url|User-Agent=x&Referer=y`; values URL-decoded. */
    private fun splitPipeHeaders(raw: String): Pair<String, Map<String, String>> {
        val bar = raw.indexOf('|')
        if (bar < 0) return raw to emptyMap()
        return raw.substring(0, bar).trim() to parseHeaderPairs(raw.substring(bar + 1))
    }

    internal fun parseHeaderPairs(text: String): Map<String, String> =
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

    /** Header-carrying option names, shared by `#EXTVLCOPT` and `#EXTINF` attributes. */
    private val HEADER_OPTIONS = mapOf(
        "http-user-agent" to "User-Agent",
        "http-referrer" to "Referer",
        "http-referer" to "Referer",
        "http-origin" to "Origin"
    )

    private const val COMMON_HEADERS = "inputstream.adaptive.common_headers"
    private val STREAM_HEADERS = setOf("inputstream.adaptive.stream_headers", "inputstream.adaptive.manifest_headers")

    /** `#EXTINF` attributes [M3uEntry] has fields for. */
    private val MAPPED_ATTRS = setOf("tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-chno")

    private const val EXTGRP = "#EXTGRP:"
    private const val EXTVLCOPT = "#EXTVLCOPT:"
    private const val KODIPROP = "#KODIPROP:"
    private const val EXTHTTP = "#EXTHTTP:"

    /** The directives [parse] binds to the next item (`#EXTINF` aside); any other `#` line is ignored. */
    private val ITEM_DIRECTIVES = listOf(EXTGRP, EXTVLCOPT, KODIPROP, EXTHTTP)

    /** `#EXTM3U` attributes kept as [M3uPlaylist.epgUrls]. */
    private val EPG_ATTRS = setOf("x-tvg-url", "url-tvg")
}
