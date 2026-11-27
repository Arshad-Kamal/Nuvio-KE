package com.nuvio.tv.data.mdblist

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Where MDBList calls go (Spec 105 S1a). [appVersion] goes in the User-Agent. */
data class MdbListApiConfiguration(
    val appVersion: String,
    val baseUrl: String = "https://api.mdblist.com"
)

enum class MdbListHttpMethod { GET, POST, DELETE }

/**
 * One API call. [path] must have an allow-list shape (`/` + segments of letters, digits, `-`, `_`, an optional trailing
 * `/`), so an id from an addon placed in it can never become another endpoint. [jsonBody] goes as `application/json`.
 * The key is added by [buildMdbListUrl], never by the caller, so it cannot end up in [query] or a log.
 */
data class MdbListRequest(
    val method: MdbListHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val jsonBody: String? = null
) {
    init {
        require(SAFE_PATH.matches(path)) { "unsafe MDBList path" }
    }

    override fun toString(): String = "MdbListRequest($method $path)"
}

/** A 2xx answer. [headers] keys are lower-case. [body] is per-user data: [toString] leaves it out. */
data class MdbListResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>
) {
    /** `X-RateLimit-Limit`: the account's daily allowance (free 1,000). */
    val rateLimitLimit: Int? get() = headers.intHeader("x-ratelimit-limit")

    /** `X-RateLimit-Remaining`: requests left today, shared by every app on the account. */
    val rateLimitRemaining: Int? get() = headers.intHeader("x-ratelimit-remaining")

    /** `X-RateLimit-Reset`: when the daily allowance resets, Unix seconds (00:00 UTC). */
    val rateLimitResetEpochSeconds: Long? get() = headers.longHeader("x-ratelimit-reset")

    override fun toString(): String = "MdbListResponse(status=$status)"
}

internal fun Map<String, String>.intHeader(name: String): Int? = this[name]?.trim()?.toIntOrNull()

internal fun Map<String, String>.longHeader(name: String): Long? = this[name]?.trim()?.toLongOrNull()

/** Every MDBList failure is an [IOException]: callers treat them as "couldn't reach MDBList" unless they look closer. */
open class MdbListException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * MDBList answered with a non-2xx status. [dailyLimit] is true for the daily `429` ("Daily API limit exceeded!"), false
 * for every other status, the 5-minute `429` included. The body's human `error` text is never kept (it may echo input).
 */
class MdbListApiException(
    val status: Int,
    val dailyLimit: Boolean = false,
    val retryAfterSeconds: Long? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitResetEpochSeconds: Long? = null
) : MdbListException("MDBList HTTP $status${if (dailyLimit) " daily limit" else ""}")

/** Today's requests are used up (or were, and the reset has not come yet): no request was sent (Spec 105 D5). */
class MdbListQuotaExceededException(val pausedUntilEpochMs: Long) : MdbListException("MDBList daily limit reached")

/** A reply over [MDBLIST_MAX_BODY_BYTES]: not retried (it would only download the same reply again). */
class MdbListReplyTooLargeException : MdbListException("MDBList reply too large")

/** No usable MDBList key on that profile (never connected, disconnected, or MDBList refused the key). */
class MdbListSignedOutException : MdbListException("Not connected to MDBList")

/** The one boundary instance for MDBList JSON (KT-JSON-02). */
internal val mdbListJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    coerceInputValues = false
}

/** The request URL with the profile's key as `apikey` (MDBList accepts it on every endpoint the tracker uses, D6). */
internal fun buildMdbListUrl(configuration: MdbListApiConfiguration, request: MdbListRequest, apiKey: String): String {
    val builder = configuration.baseUrl.toHttpUrl().newBuilder().encodedPath(request.path)
    request.query.filterKeys { it != API_KEY_PARAM }.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    builder.addQueryParameter(API_KEY_PARAM, apiKey)
    return builder.build().toString()
}

internal fun mdbListHeaders(configuration: MdbListApiConfiguration, hasBody: Boolean): Map<String, String> = buildMap {
    put("User-Agent", "NuvioKE/${configuration.appVersion}")
    put("Accept", "application/json")
    if (hasBody) put("Content-Type", "application/json")
}

/** True when the body is MDBList's error envelope naming the daily limit. Never logs the body (KT-JSON-04). */
internal fun isDailyLimitError(body: String): Boolean {
    if (body.isBlank() || body.length > MAX_ERROR_BODY_CHARS) return false
    val error = try {
        mdbListJson.decodeFromString(MdbListErrorEnvelope.serializer(), body).error
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return error?.contains("daily", ignoreCase = true) == true
}

/** Decodes a 2xx body; a wrong shape is an [MdbListException] (never the raw message: it may echo the body). */
internal fun <T> decodeMdbList(serializer: kotlinx.serialization.KSerializer<T>, body: String): T = try {
    mdbListJson.decodeFromString(serializer, body)
} catch (_: SerializationException) {
    throw MdbListException("MDBList reply not understood")
} catch (_: IllegalArgumentException) {
    throw MdbListException("MDBList reply not understood")
}

@Serializable
private data class MdbListErrorEnvelope(@SerialName("error") val error: String? = null)

internal const val API_KEY_PARAM = "apikey"
private val SAFE_PATH = Regex("(/[A-Za-z0-9_-]+)+/?")
private const val MAX_ERROR_BODY_CHARS = 16 * 1024
