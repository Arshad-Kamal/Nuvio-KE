package com.nuvio.tv.core.posters

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a card's title is, as rating-poster services spell it in `{type}` and RPDB's typed ids. */
enum class PosterContentType(val apiName: String) { MOVIE("movie"), SERIES("series") }

/**
 * A title's ids, each kept only when it has its expected shape (P0-5): they come from third-party addon
 * metadata, so anything else counts as missing and the card keeps its addon art.
 */
class PosterContentIds private constructor(
    /** The raw meta id (`tt0137523`, `tmdb:1396`, `kitsu:7442`), or "" when it has no known shape. */
    val id: String,
    val imdbId: String?,
    val tmdbId: String?,
    val tvdbId: String?,
    val kitsuId: String?,
    val anilistId: String?,
    val malId: String?,
    val anidbId: String?,
) {
    /** Whether a template could fill an id for this title: a shaped meta id or an IMDb id (P0-4's pending rule). */
    val hasAnyId: Boolean get() = id.isNotEmpty() || imdbId != null

    companion object {
        private val IMDB = Regex("tt\\d{1,10}")
        private val NUMBER = Regex("\\d{1,10}")
        private val PREFIXED = Regex("([a-z]{2,10}):(\\d{1,10})")

        fun from(metaId: String, explicitImdbId: String? = null): PosterContentIds {
            val prefixed = PREFIXED.matchEntire(metaId)
            fun numeric(namespace: String): String? =
                prefixed?.takeIf { it.groupValues[1] == namespace }?.groupValues?.get(2)
            val imdb = explicitImdbId?.takeIf { IMDB.matches(it) } ?: metaId.takeIf { IMDB.matches(it) }
            return PosterContentIds(
                id = if (IMDB.matches(metaId) || prefixed != null) metaId else "",
                imdbId = imdb,
                tmdbId = numeric("tmdb"),
                tvdbId = numeric("tvdb"),
                kitsuId = numeric("kitsu"),
                anilistId = numeric("anilist"),
                malId = numeric("mal"),
                anidbId = numeric("anidb"),
            )
        }

        /** Test and save-check affordance: explicit ids, shape-checked the same way. */
        internal fun of(
            id: String,
            imdbId: String? = null,
            tmdbId: String? = null,
            tvdbId: String? = null,
        ): PosterContentIds = PosterContentIds(
            id = id.takeIf { IMDB.matches(it) || PREFIXED.matches(it) }.orEmpty(),
            imdbId = imdbId?.takeIf { IMDB.matches(it) },
            tmdbId = tmdbId?.takeIf { NUMBER.matches(it) },
            tvdbId = tvdbId?.takeIf { NUMBER.matches(it) },
            kitsuId = null,
            anilistId = null,
            malId = null,
            anidbId = null,
        )
    }
}

/**
 * Spec 114 P0-5: fills a [RatingPosterTemplate] for one title. Ported from official NuvioTV's
 * `CustomPosterUrlResolver` (skoruppa, 34ce0df0b, GPL-3.0) with the same placeholders, `{x?}` and
 * `{a|b}`, changed so untrusted ids can't reshape the URL: values are shape-checked and percent-encoded,
 * the RPDB-family fallback swaps placeholders instead of inserting ids, and every result must keep the
 * template's scheme, host and port. Returns null when the card should keep its addon art.
 */
object RatingPosterResolver {

    fun resolve(
        template: RatingPosterTemplate,
        ids: PosterContentIds,
        type: PosterContentType,
        shape: String = "poster",
    ): String? {
        fill(template, template.pattern, template.tokens, ids, type, shape)?.let { return it }
        if (!isRpdbFamily(template.host)) return null
        for (variant in rpdbVariants(template.pattern, template.tokens, ids, type)) {
            fill(template, variant, scanTokens(variant), ids, type, shape)?.let { return it }
        }
        return null
    }

    private fun fill(
        template: RatingPosterTemplate,
        pattern: String,
        tokens: List<PlaceholderToken>,
        ids: PosterContentIds,
        type: PosterContentType,
        shape: String,
    ): String? {
        val values = Values(ids, type, shape)
        val fragmentStart = pattern.indexOf('#').let { if (it < 0) pattern.length else it }
        val url = StringBuilder(pattern.length + 16)
        var cursor = 0
        var filledId = false
        for (token in tokens) {
            url.append(pattern, cursor, token.start)
            cursor = token.end
            // A pipe takes its first key with a value; a single key may be empty only when optional.
            val key = if (token.keys.size > 1) {
                token.keys.firstOrNull { !values.lookup(it).isNullOrEmpty() } ?: return null
            } else {
                token.keys[0]
            }
            val value = values.lookup(key)
            if (value == null) {
                url.append(pattern, token.start, token.end) // not ours: left as written
                continue
            }
            if (value.isEmpty() && !token.optional) return null
            url.appendEncoded(value)
            if (value.isNotEmpty() && key in RatingPosterTemplate.ID_KEYS && token.start < fragmentStart) filledId = true
        }
        url.append(pattern, cursor, pattern.length)
        // An id-less fill (all-optional templates such as Posters+') would ask for a poster of nothing.
        if (!filledId || url.length > RatingPosterTemplate.MAX_LENGTH) return null
        val parsed = url.toString().toHttpUrlOrNull() ?: return null
        if (parsed.scheme != template.scheme || parsed.host != template.host || parsed.port != template.port) return null
        // The URL that passed the pin, as OkHttp will send it, so no other parser can read another host.
        return parsed.toString()
    }

    private class Values(private val ids: PosterContentIds, private val type: PosterContentType, private val shape: String) {
        private val idType: String = when {
            ids.id.isEmpty() -> ""
            ids.id.startsWith("tt") -> "imdb"
            else -> ids.id.substringBefore(':')
        }
        private val rawId: String = ids.id.substringAfter(':')

        /** RPDB's form: `tt…` as is, `movie-1396` / `series-81189` for tmdb and tvdb, the number otherwise. */
        private val typedId: String = when (idType) {
            "" -> ""
            "imdb" -> ids.id
            "tmdb", "tvdb" -> "${type.apiName}-$rawId"
            else -> rawId
        }

        /** null = not a placeholder this resolver knows. */
        fun lookup(key: String): String? = when (key) {
            "id" -> ids.id
            "id_type" -> idType
            "typed_id" -> typedId
            "type" -> type.apiName
            "shape" -> shape
            "imdb_id" -> ids.imdbId.orEmpty()
            "tmdb_id" -> ids.tmdbId.orEmpty()
            "tvdb_id" -> ids.tvdbId.orEmpty()
            "kitsu_id" -> ids.kitsuId.orEmpty()
            "anilist_id" -> ids.anilistId.orEmpty()
            "mal_id" -> ids.malId.orEmpty()
            "anidb_id" -> ids.anidbId.orEmpty()
            else -> null
        }
    }

    // -- RPDB family (upstream's list): when the direct fill fails, try the title's other ids --

    private val RPDB_DOMAINS = listOf("ratingposterdb.com", "aioratings.com", "top-posters.com", "btttr.cc")

    private fun isRpdbFamily(host: String): Boolean =
        RPDB_DOMAINS.any { host == it || host.endsWith(".$it") }

    private val SWAP_ORDER = mapOf(
        "imdb" to listOf("tmdb", "tvdb"),
        "tmdb" to listOf("imdb", "tvdb"),
        "tvdb" to listOf("imdb", "tmdb"),
    )
    private val TYPED_PREFIXES = listOf("movie-", "series-", "{type}-")

    /**
     * The template rewritten for each other id the title has: the id placeholder's key and the
     * `/imdb/` `/tmdb/` `/tvdb/` path segment swapped. RPDB wants `movie-1396` for tmdb/tvdb and a bare
     * `tt…` for imdb, so a typed prefix is added (only when the template doesn't already write one) or
     * dropped. Ids are never inserted here: the variant goes through [fill] like the template.
     */
    private fun rpdbVariants(
        pattern: String,
        tokens: List<PlaceholderToken>,
        ids: PosterContentIds,
        type: PosterContentType,
    ): List<String> {
        val token = tokens.firstOrNull { it.keys.size == 1 && it.keys[0].removeSuffix("_id") in SWAP_ORDER }
            ?: return emptyList()
        val source = token.keys[0].removeSuffix("_id")
        val prefix = TYPED_PREFIXES.firstOrNull { pattern.startsWith(it, token.start - it.length) }
        val have = mapOf("imdb" to ids.imdbId, "tmdb" to ids.tmdbId, "tvdb" to ids.tvdbId)
        return SWAP_ORDER.getValue(source).filter { !have[it].isNullOrEmpty() }.map { target ->
            val newPrefix = when {
                target == "imdb" -> ""
                prefix != null -> prefix
                else -> "${type.apiName}-"
            }
            val prefixStart = token.start - (prefix?.length ?: 0)
            val newToken = "{${target}_id${if (token.optional) "?" else ""}}"
            (pattern.substring(0, prefixStart) + newPrefix + newToken + pattern.substring(token.end))
                .replaceFirst("/$source/", "/$target/")
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** RFC 3986 unreserved characters and `:` (for `tmdb:1396`) stay; every other byte is percent-encoded. */
    private fun StringBuilder.appendEncoded(value: String) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~:") {
                append(ch)
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
    }
}
