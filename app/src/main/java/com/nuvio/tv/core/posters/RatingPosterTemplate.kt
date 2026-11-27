package com.nuvio.tv.core.posters

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spec 114: a rating-poster URL template the user pasted (Posters+, RPDB, TOP Posters style), checked
 * once (P0-5, P0-9). It holds the user's key, so nothing here ever prints it: [toString] names the host,
 * and only [displayHost] and [keyTail] are meant for the UI.
 */
class RatingPosterTemplate private constructor(
    /** The template as saved; only the store reads it. */
    internal val raw: String,
    /** [raw] with `%7B…%7D` placeholders decoded; literal parts (the key) keep their bytes. */
    internal val pattern: String,
    internal val tokens: List<PlaceholderToken>,
    /** Where the `#fragment` starts in [pattern] (its length when none): ids after it are never sent. */
    internal val fragmentStart: Int,
    val scheme: String,
    val host: String,
    val port: Int,
    /** Last 4 characters of the key when one can be told apart and has 12+ characters (P0-7), else null. */
    val keyTail: String?,
    /**
     * Identifies the template within this process (key-rejected record, cache keys): SHA-256 of a per-process
     * random salt and [raw], truncated, so even a fingerprint that leaked could not be brute-forced to the key.
     */
    val fingerprint: String,
) {
    val isHttp: Boolean get() = scheme == "http"

    /** The host, plus its port when that isn't the scheme's default. */
    val displayHost: String
        get() {
            val shown = if (':' in host) "[$host]" else host
            return if (port == HttpUrl.defaultPort(scheme)) shown else "$shown:$port"
        }

    override fun equals(other: Any?): Boolean = other is RatingPosterTemplate && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = "RatingPosterTemplate(host=$displayHost)"

    sealed interface Parsed {
        class Valid(val template: RatingPosterTemplate) : Parsed
        data class Refused(val reason: Refusal) : Parsed
    }

    enum class Refusal {
        EMPTY,
        TOO_LONG,
        NOT_A_URL,
        USERINFO,
        /** A placeholder in the scheme, host or port: ids must never pick where the key goes. */
        PLACEHOLDER_BEFORE_PATH,

        /** http, but the host isn't a home-network IP literal (P0-9). */
        HTTP_NOT_HOME_NETWORK,
        NO_ID_PLACEHOLDER,
    }

    companion object {
        const val MAX_LENGTH = 2048

        fun parse(input: String): Parsed {
            val raw = input.trim()
            if (raw.isEmpty()) return Parsed.Refused(Refusal.EMPTY)
            if (raw.length > MAX_LENGTH) return Parsed.Refused(Refusal.TOO_LONG)
            val pattern = decodePlaceholders(raw)
            val tokens = scanTokens(pattern)

            // Anchored to the real scheme, read as OkHttp reads it: exactly `scheme://`, no extra `/` or `\`
            // (OkHttp skips those), and `\` ends the authority like `/`.
            val schemeEnd = pattern.indexOf(':')
            val scheme = if (schemeEnd > 0) pattern.substring(0, schemeEnd).lowercase() else ""
            if (scheme != "https" && scheme != "http") return Parsed.Refused(Refusal.NOT_A_URL)
            val authorityStart = schemeEnd + 3
            if (!pattern.startsWith("//", schemeEnd + 1) || pattern.getOrNull(authorityStart).let { it == '/' || it == '\\' }) {
                return Parsed.Refused(Refusal.NOT_A_URL)
            }
            val authorityEnd = pattern.indexOfAny(charArrayOf('/', '?', '#', '\\'), authorityStart)
                .let { if (it < 0) pattern.length else it }
            val authority = pattern.substring(authorityStart, authorityEnd)
            if ('{' in authority || '}' in authority || tokens.any { it.start < authorityEnd }) {
                return Parsed.Refused(Refusal.PLACEHOLDER_BEFORE_PATH)
            }
            val fragmentStart = pattern.indexOf('#').let { if (it < 0) pattern.length else it }
            // Placeholders out: a structural read of the template only.
            val url = blankTokens(pattern, tokens).toHttpUrlOrNull()
                ?: return Parsed.Refused(Refusal.NOT_A_URL)
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || '@' in authority) {
                return Parsed.Refused(Refusal.USERINFO)
            }
            // HttpUrl reads only http and https.
            if (url.scheme == "http" && !isHomeNetworkLiteral(url.host)) {
                return Parsed.Refused(Refusal.HTTP_NOT_HOME_NETWORK)
            }
            // An id only in the #fragment is never sent: the service would be asked for a poster of nothing.
            if (tokens.none { token -> token.start < fragmentStart && token.keys.any { it in ID_KEYS } }) {
                return Parsed.Refused(Refusal.NO_ID_PLACEHOLDER)
            }
            return Parsed.Valid(
                RatingPosterTemplate(
                    raw = raw,
                    pattern = pattern,
                    tokens = tokens,
                    fragmentStart = fragmentStart,
                    scheme = url.scheme,
                    host = url.host,
                    port = url.port,
                    keyTail = keyOf(url)?.takeIf { it.length >= MIN_KEY_FOR_TAIL }?.takeLast(4),
                    fingerprint = fingerprintOf(raw),
                )
            )
        }

        /** Placeholders that carry a title's id; a template needs one, a filled URL needs one non-empty (P0-5). */
        internal val ID_KEYS = setOf(
            "id", "typed_id", "imdb_id", "tmdb_id", "tvdb_id", "kitsu_id", "anilist_id", "mal_id", "anidb_id",
        )

        /** A shorter key would show more than a third of itself in its last 4 characters. */
        private const val MIN_KEY_FOR_TAIL = 12

        private val KEY_PARAMS = setOf("access_key", "api_key", "apikey", "key", "token")
        private val KEY_SEGMENT = Regex("[A-Za-z0-9_-]{16,}")

        // `%7B name %7D` with `%7C` pipes and an optional `?`, as iOS pastes braces. Only these spans are
        // decoded, so a key whose bytes include %7C reaches the service unchanged (round 1 m2).
        private val ENCODED_TOKEN = Regex("%7[Bb]([a-z_]+(?:(?:\\||%7[Cc])[a-z_]+)*\\??)%7[Dd]")

        private fun decodePlaceholders(raw: String): String =
            if ('%' !in raw) raw else ENCODED_TOKEN.replace(raw) { match ->
                "{" + match.groupValues[1].replace("%7C", "|").replace("%7c", "|") + "}"
            }

        private fun blankTokens(pattern: String, tokens: List<PlaceholderToken>): String {
            if (tokens.isEmpty()) return pattern
            val out = StringBuilder(pattern.length)
            var cursor = 0
            for (token in tokens) {
                out.append(pattern, cursor, token.start)
                cursor = token.end
            }
            return out.append(pattern, cursor, pattern.length).toString()
        }

        private fun keyOf(url: HttpUrl): String? {
            for (i in 0 until url.querySize) {
                if (url.queryParameterName(i).lowercase() in KEY_PARAMS) {
                    url.queryParameterValue(i)?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return url.pathSegments.firstOrNull { KEY_SEGMENT.matches(it) }
        }

        private val fingerprintSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }

        private fun fingerprintOf(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(fingerprintSalt)
            return digest.digest(raw.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/** One `{key}`, `{key?}` or `{a|b}` placeholder at [start, end) of a template's pattern. */
internal class PlaceholderToken(val start: Int, val end: Int, val optional: Boolean, val keys: List<String>)

/** Left-to-right scan, as upstream's resolver: names are `[a-z_]`, `|` separates choices, a trailing `?` is optional. */
internal fun scanTokens(pattern: String): List<PlaceholderToken> {
    if ('{' !in pattern) return emptyList()
    val tokens = ArrayList<PlaceholderToken>(4)
    var index = 0
    while (index < pattern.length) {
        val open = pattern.indexOf('{', index)
        if (open < 0) break
        val close = pattern.indexOf('}', open + 1)
        if (close < 0) break
        val token = parseToken(pattern, open, close)
        index = if (token != null) {
            tokens += token
            close + 1
        } else {
            open + 1
        }
    }
    return tokens
}

private fun parseToken(pattern: String, open: Int, close: Int): PlaceholderToken? {
    val optional = close - 1 > open && pattern[close - 1] == '?'
    val nameEnd = if (optional) close - 1 else close
    if (nameEnd <= open + 1) return null
    val keys = pattern.substring(open + 1, nameEnd).split('|')
    if (keys.any { key -> key.isEmpty() || !key.all { it in 'a'..'z' || it == '_' } }) return null
    return PlaceholderToken(open, close + 1, optional, keys)
}
