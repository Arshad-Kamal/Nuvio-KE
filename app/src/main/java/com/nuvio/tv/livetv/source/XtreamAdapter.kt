package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.livetv.data.ChannelIdentity
import com.nuvio.tv.livetv.model.Channel
import com.nuvio.tv.livetv.model.LiveStream
import com.nuvio.tv.livetv.parse.M3uParser
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Xtream Codes logins as a Live TV source (Spec 109 S1): signs in through `player_api.php`, reads
 * the account, the live categories and the live streams, and lists the streams as channels keyed by
 * `stream_id` ([ChannelIdentity.xtreamKey]) with the server's numbers, logos, `epg_channel_id` (as
 * tvg-id) and category names (as groups, which feed the Category filter as `group-title` does).
 * Stream URLs are the account's `live/<user>/<pass>/<id>.<ext>`, the same shape a `get.php` list
 * gives, so playback, the panel-refusal handling (Spec 90 L5) and catch-up treat them alike.
 *
 * Every answer is untrusted: fetched through [SourceFetcher]'s caps, nesting-, string- and
 * element-capped ([JsonBoundedInputStream], KT-JSON-03; Spec 109 D8), lists decoded one element at a time and cut at
 * [MAX_CHANNELS] / [MAX_CATEGORIES], scalar fields decoded strictly, and a decode failure surfaces
 * as a fixed reason (KT-JSON-04). The request URLs carry the password: they are
 * never logged, and no reason built here quotes a URL, a username or a password.
 */
@Singleton
class XtreamAdapter @Inject constructor(private val fetcher: SourceFetcher) {

    /** The server says the account expired; [expiresAtMs] = the date it gave, null when none (no credential here). */
    class Expired(val expiresAtMs: Long?) : SourceFetchException(EXPIRED)

    /** What one sign-in and listing gave. */
    data class Listing(val account: XtreamAccountInfo, val channels: List<Channel>)

    /**
     * Signs in with [login] and lists its live channels for source [sourceId]; [nowMs] judges the
     * expiry. Throws [SourceFetchException] with a user-facing reason (refused, expired, disabled,
     * not an Xtream server, unreadable), or IOException on network failure.
     */
    suspend fun load(sourceId: String, login: XtreamLogin, nowMs: Long): Listing {
        val base = login.server.toHttpUrlOrNull() ?: throw SourceFetchException(NOT_A_SERVER)
        val user = signIn(base, login)
        val account = accountOf(user, nowMs)
        val ext = outputExtension(user.allowedOutputFormats.orEmpty().mapNotNull { it.text()?.lowercase() })
        val categories = callList(base, login, "get_live_categories", CategoryDto.serializer()) { items ->
            val out = HashMap<String, String>()
            for (c in items) {
                if (out.size >= MAX_CATEGORIES) break
                val id = c.id.text() ?: continue
                out.putIfAbsent(id, c.name.text() ?: id)
            }
            out
        }
        // The playlist cap (Spec 137): a large account's list passes 32 MiB; memory is bounded per element and by what is kept.
        val channels = callList(base, login, "get_live_streams", StreamDto.serializer(), streamed = true) { items ->
            channelsOf(sourceId, items, categories) { id -> liveUrl(base, login, id, ext) }
        }
        return Listing(account.copy(categories = categories.size), channels)
    }

    /** The account facts of a signed-in [user]; throws the refusal when it is refused, expired or disabled. */
    private fun accountOf(user: UserInfoDto, nowMs: Long): XtreamAccountInfo {
        val auth = user.auth.text()
        if (auth != "1" && auth != "true") throw SourceFetchException(LOGIN_REFUSED)
        // Seconds; a value past what milliseconds can hold is no date (it would overflow into a false expiry).
        val expiresAtMs = user.expDate.text()?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.let { it * 1000 }
        val status = user.status.text()?.lowercase()
        when {
            status == "expired" || (expiresAtMs != null && expiresAtMs <= nowMs) -> throw Expired(expiresAtMs)
            status == "banned" || status == "disabled" -> throw SourceFetchException(DISABLED)
        }
        return XtreamAccountInfo(
            expiresAtMs = expiresAtMs,
            maxConnections = user.maxConnections.text()?.toIntOrNull()?.takeIf { it > 0 },
            trial = user.isTrial.text().let { it == "1" || it == "true" }
        )
    }

    /**
     * The streams with a numeric id as channels, in server order, the first of a repeated id kept,
     * until [MAX_CHANNELS] channels or [M3uParser.MAX_KEPT_CHARS] chars of kept text (as a playlist's
     * parse ends); [urlOf] builds a stream's URL from its id.
     */
    private fun channelsOf(
        sourceId: String,
        items: Sequence<StreamDto>,
        categories: Map<String, String>,
        urlOf: (String) -> String
    ): List<Channel> {
        val channels = LinkedHashMap<String, Channel>()
        var keptChars = 0L
        for (s in items) {
            if (channels.size >= MAX_CHANNELS) break
            val id = s.streamId.text()?.takeIf(::isStreamId) ?: continue
            val key = ChannelIdentity.xtreamKey(sourceId, id)
            if (key in channels) continue
            val categoryIds = s.categoryIds?.mapNotNull { it.text() }?.ifEmpty { null } ?: listOfNotNull(s.categoryId.text())
            val tvgId = s.epgChannelId.text()
            val name = s.name.text() ?: id
            val logo = s.icon.text()
            val number = s.num.text()
            val groups = categoryIds.mapNotNull { categories[it] }.distinct()
            val url = urlOf(id)
            keptChars += key.length + name.length + (tvgId?.length ?: 0) + (logo?.length ?: 0) + (number?.length ?: 0) +
                groups.sumOf { it.length } + url.length
            if (keptChars > M3uParser.MAX_KEPT_CHARS) break
            channels[key] = Channel(
                key = key,
                sourceId = sourceId,
                name = name,
                tvgId = tvgId,
                country = ChannelIdentity.countryOfTvgId(tvgId),
                logo = logo,
                groups = groups,
                channelNumber = number,
                streams = listOf(LiveStream(url))
            )
        }
        return channels.values.toList()
    }

    /** The account part of `player_api.php`; a 401/403 is a refused login, a 404 or other content no Xtream server. */
    private suspend fun signIn(base: HttpUrl, login: XtreamLogin): UserInfoDto {
        val answer = try {
            fetcher.fetch(apiUrl(base, login, action = null)) { _, body ->
                decoding { json.decodeFromStream(ApiDto.serializer(), bounded(body)) }
            }
        } catch (e: SourceFetchException) {
            throw when (e.httpCode) {
                401, 403 -> SourceFetchException(LOGIN_REFUSED)
                404 -> SourceFetchException(NOT_XTREAM)
                else -> if (e.message == UNREADABLE) SourceFetchException(NOT_XTREAM) else e
            }
        }
        return answer.userInfo ?: throw SourceFetchException(NOT_XTREAM)
    }

    /**
     * [action]'s JSON array, handed to [read] one element at a time (never held whole: a list may
     * hold tens of thousands of streams); [read] runs while the body is open and may stop early.
     * [streamed] takes the fetcher's playlist cap instead of its text cap ([SourceFetcher.fetch]).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <E, T> callList(
        base: HttpUrl,
        login: XtreamLogin,
        action: String,
        element: KSerializer<E>,
        streamed: Boolean = false,
        read: (Sequence<E>) -> T
    ): T = fetcher.fetch(apiUrl(base, login, action), streamed = streamed) { _, body ->
        decoding { read(json.decodeToSequence(bounded(body), element, DecodeSequenceMode.ARRAY_WRAPPED)) }
    }

    private fun bounded(body: InputStream) = JsonBoundedInputStream(body, MAX_DEPTH, MAX_STRING_BYTES, MAX_ELEMENT_BYTES)

    // IllegalArgumentException covers SerializationException (its subclass), KT-JSON-04.
    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw SourceFetchException(UNREADABLE)
    }

    @Serializable
    private class ApiDto(@SerialName("user_info") val userInfo: UserInfoDto? = null)

    // Scalars as JsonPrimitive: panels send numbers as strings or numbers, and null or "" for none. A
    // JsonPrimitive field decodes the whole value before its type check, so the element cap bounds it.
    @Serializable
    private class UserInfoDto(
        val auth: JsonPrimitive? = null,
        val status: JsonPrimitive? = null,
        @SerialName("exp_date") val expDate: JsonPrimitive? = null,
        @SerialName("is_trial") val isTrial: JsonPrimitive? = null,
        @SerialName("max_connections") val maxConnections: JsonPrimitive? = null,
        @SerialName("allowed_output_formats") val allowedOutputFormats: List<JsonPrimitive>? = null
    )

    @Serializable
    private class CategoryDto(
        @SerialName("category_id") val id: JsonPrimitive? = null,
        @SerialName("category_name") val name: JsonPrimitive? = null
    )

    @Serializable
    private class StreamDto(
        val num: JsonPrimitive? = null,
        val name: JsonPrimitive? = null,
        @SerialName("stream_id") val streamId: JsonPrimitive? = null,
        @SerialName("stream_icon") val icon: JsonPrimitive? = null,
        @SerialName("epg_channel_id") val epgChannelId: JsonPrimitive? = null,
        @SerialName("category_id") val categoryId: JsonPrimitive? = null,
        @SerialName("category_ids") val categoryIds: List<JsonPrimitive>? = null
    )

    companion object {
        const val LOGIN_REFUSED = "The server turned down this username and password"
        const val EXPIRED = "This account has expired"
        const val DISABLED = "This account is disabled"
        const val NOT_XTREAM = "This server didn't answer as an Xtream server"
        const val NOT_A_SERVER = "Not a server address"
        const val UNREADABLE = "Unreadable Xtream response"

        /** Deepest nesting an Xtream answer needs is 3 (list > stream > category_ids); room to spare. */
        internal const val MAX_DEPTH = 16

        /** Longest JSON string taken (KT-JSON-03); real names, logos and ids are well under 1 KiB. */
        internal const val MAX_STRING_BYTES = 64 * 1024

        /** Channels kept of one account, as a playlist keeps items (Spec 137: [M3uParser.MAX_ENTRIES]). */
        internal const val MAX_CHANNELS = M3uParser.MAX_ENTRIES

        /** Categories kept; panels have tens to a few hundred. */
        internal const val MAX_CATEGORIES = 10_000

        /**
         * Bytes of one element below the top level (a list item, `user_info`); real ones are under 1 KiB.
         * Bounds every value decoded from it, wrong-typed trees included (Spec 109 D8): at worst a
         * transient JsonElement tree of ~500k one-byte literals (tens of MB), dropped with its element.
         */
        internal const val MAX_ELEMENT_BYTES = 1024 * 1024

        private val json = NetworkModule.provideWireJson()

        /**
         * The server base a user typed, normalised: `http://` added when no scheme, credentials,
         * query, fragment and a trailing `*.php` (a pasted `player_api.php` or `get.php` link) dropped,
         * no trailing slash. Null when it is no web address.
         */
        fun serverBase(raw: String): String? {
            val t = raw.trim().ifEmpty { return null }
            val url = (if ("://" in t) t else "http://$t").toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
                .let { s -> if (s.lastOrNull()?.endsWith(".php", ignoreCase = true) == true) s.dropLast(1) else s }
            val b = url.newBuilder().username("").password("").query(null).fragment(null).encodedPath("/")
            segments.forEach { b.addPathSegment(it) }
            return b.build().toString().removeSuffix("/")
        }

        internal fun apiUrl(base: HttpUrl, login: XtreamLogin, action: String?): String =
            base.newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", login.username)
                .addQueryParameter("password", login.password)
                .apply { if (action != null) addQueryParameter("action", action) }
                .build().toString()

        /**
         * The account's guide, `xmltv.php` with the login in its query: built for one fetch, never
         * stored, logged or shown (the guide is cached under the source id).
         */
        internal fun xmltvUrl(login: XtreamLogin): String? =
            login.server.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("xmltv.php")
                ?.addQueryParameter("username", login.username)
                ?.addQueryParameter("password", login.password)
                ?.build()?.toString()

        /**
         * The login a pasted `get.php` link carries (Spec 109 stage 11, Q5: offered on paste only):
         * its server ([serverBase]) and its `username` / `password` query values (the username
         * trimmed, the password as given); null when [raw] is no `get.php` link with both. Reads a
         * scheme-less link as `http://`, but [LiveSourceManager.checkLink] passes only links the
         * playlist check reads (no sign-in for a link it would refuse).
         */
        fun linkLogin(raw: String): XtreamLogin? {
            val t = raw.trim()
            val url = (if ("://" in t) t else "http://$t").toHttpUrlOrNull() ?: return null
            if (!url.pathSegments.lastOrNull().equals("get.php", ignoreCase = true)) return null
            val user = url.queryParameter("username")?.trim()?.ifEmpty { null } ?: return null
            val pass = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            return XtreamLogin(serverBase(t) ?: return null, user, pass)
        }

        internal fun liveUrl(base: HttpUrl, login: XtreamLogin, streamId: String, ext: String): String =
            base.newBuilder()
                .addPathSegment("live")
                .addPathSegment(login.username)
                .addPathSegment(login.password)
                .addPathSegment("$streamId.$ext")
                .build().toString()

        /** `ts` unless the account allows only `m3u8` (an empty list allows both). */
        internal fun outputExtension(allowed: List<String>): String =
            if (allowed.isNotEmpty() && "ts" !in allowed && "m3u8" in allowed) "m3u8" else "ts"

        /** Stream ids are digits; anything else would not make a live URL the server serves. */
        private fun isStreamId(id: String): Boolean = id.length in 1..18 && id.all { it in '0'..'9' }

        /** A scalar's text, trimmed; null for absent, JSON null or blank. */
        private fun JsonPrimitive?.text(): String? =
            if (this == null || this is JsonNull) null else content.trim().ifEmpty { null }

    }
}

/**
 * Fails with [XtreamAdapter.UNREADABLE] once JSON nesting ([ and { outside strings) passes
 * [maxDepth], one string's content or unquoted literal passes [maxStringBytes] bytes, or one element
 * below the top level (the bytes read while nesting is 2 or deeper) passes [maxElementBytes]
 * (KT-JSON-03: the decoder has none of these guards, kotlinx.serialization #1703), before the decoder
 * builds the value.
 */
internal class JsonBoundedInputStream(
    input: InputStream,
    private val maxDepth: Int,
    private val maxStringBytes: Int,
    private val maxElementBytes: Int
) : FilterInputStream(input) {
    private var depth = 0
    private var inString = false
    private var escaped = false
    /** Bytes of the current string's content or unquoted literal. */
    private var runBytes = 0
    /** Bytes of the current element below the top level, its opening bracket included (its closing one resets). */
    private var elementBytes = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) scan(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in off until off + maxOf(n, 0)) scan(b[i].toInt() and 0xFF)
        return n
    }

    /** Reads what it skips, so skipped bytes are scanned too. */
    override fun skip(n: Long): Long {
        val buffer = ByteArray(minOf(n, SKIP_BUFFER.toLong()).toInt().coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    private fun scan(c: Int) {
        scanToken(c)
        if (depth < 2) {
            elementBytes = 0
        } else if (++elementBytes > maxElementBytes) {
            throw SourceFetchException(XtreamAdapter.UNREADABLE)
        }
    }

    private fun scanToken(c: Int) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\'.code -> escaped = true
                c == '"'.code -> {
                    inString = false
                    return
                }
            }
            if (++runBytes > maxStringBytes) throw SourceFetchException(XtreamAdapter.UNREADABLE)
            return
        }
        when (c) {
            '"'.code -> {
                inString = true
                runBytes = 0
            }
            '['.code, '{'.code -> {
                runBytes = 0
                if (++depth > maxDepth) throw SourceFetchException(XtreamAdapter.UNREADABLE)
            }
            ']'.code, '}'.code -> {
                runBytes = 0
                depth--
            }
            ','.code, ':'.code, ' '.code, '\n'.code, '\r'.code, '\t'.code -> runBytes = 0
            // An unquoted literal (number, true, null, or junk) is bounded like a string.
            else -> if (++runBytes > maxStringBytes) throw SourceFetchException(XtreamAdapter.UNREADABLE)
        }
    }

    private companion object {
        const val SKIP_BUFFER = 8 * 1024
    }
}
