package com.nuvio.tv.data.simkl

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Simkl AUTH V2 app identity (Spec 106 S1). [clientId] reads the "TV, devices & command line" registration (no secret)
 * the user pasted on this TV ([SimklAppIdStore], Spec 106 D6/D9), once per request: null or blank = not set up.
 * [appName] / [appVersion] go on every request as Simkl asks.
 */
data class SimklApiConfiguration(
    val clientId: suspend () -> String?,
    val appVersion: String,
    val appName: String = "nuvio-ke",
    val baseUrl: String = "https://api.simkl.com"
)

/** This TV's app id, or null when none is set up. */
internal suspend fun SimklApiConfiguration.clientIdOrNull(): String? = clientId()?.takeIf(String::isNotBlank)

/** This TV's app id, or [SimklSignedOutException] when none is set up: nothing is sent. */
internal suspend fun SimklApiConfiguration.requireClientId(): String = clientIdOrNull() ?: throw SimklSignedOutException()

enum class SimklHttpMethod { GET, POST, DELETE }

/**
 * One API call. [path] must have an allow-list shape (`/` + segments of letters, digits, `-`, `_`), so an id from an
 * addon placed in it can never become another endpoint (no dots, backslashes, escapes, query or fragment). [jsonBody]
 * is sent as `application/json` (every Simkl endpoint, `/oauth2/` included, accepts it); it may hold a token, so
 * [toString] leaves it out. Whether a
 * user token goes on is the caller's choice ([SimklClient.execute]): catalog endpoints must go without one, or the
 * edge cache is bypassed. [redirectIsAnswer]: a 3xx is the reply (`/redirect`, read through an engine that does not
 * follow redirects), not a failure.
 */
data class SimklRequest(
    val method: SimklHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val redirectIsAnswer: Boolean = false
) {
    init {
        require(SAFE_PATH.matches(path)) { "unsafe Simkl path" }
    }

    override fun toString(): String = "SimklRequest($method $path)"
}

/** A 2xx answer (or a 3xx one for [SimklRequest.redirectIsAnswer]). [headers] keys are lower-case. [body] may hold tokens (`/oauth2/token`): [toString] leaves it out. */
data class SimklResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>
) {
    /** `X-RateLimit-Remaining`: requests left today on this Simkl account (counted AUTH V2 calls only). */
    val rateLimitRemaining: Int? get() = headers.intHeader("x-ratelimit-remaining")

    /** `X-RateLimit-Limit`: the account's daily allowance (free 500, PRO 1,000, VIP 10,000). */
    val rateLimitLimit: Int? get() = headers.intHeader("x-ratelimit-limit")

    override fun toString(): String = "SimklResponse(status=$status)"
}

internal fun Map<String, String>.intHeader(name: String): Int? = this[name]?.trim()?.toIntOrNull()

/** Every Simkl failure is an [IOException]: callers treat them as "couldn't reach Simkl" unless they look closer. */
open class SimklException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Simkl answered with a non-2xx status. [error] is the body's machine-readable `error` (e.g. `user_limit_exceeded`,
 * `authorization_pending`, `invalid_grant`); the human `message` is never kept (it may echo input).
 */
class SimklApiException(
    val status: Int,
    val error: String?,
    val retryAfterSeconds: Long? = null,
    val rateLimitLimit: Int? = null
) : SimklException("Simkl HTTP $status${error?.let { " $it" } ?: ""}")

/** The account's daily allowance is spent (or was, and the reset has not come yet): no request was sent. */
class SimklQuotaExceededException(val pausedUntilEpochMs: Long) : SimklException("Simkl daily limit reached")

/** A reply over [SIMKL_MAX_BODY_BYTES]: not retried (it would only download the same reply again). */
class SimklReplyTooLargeException : SimklException("Simkl reply too large")

/** No usable Simkl sign-in on that profile (never signed in, signed out, or the service revoked the grant). */
class SimklSignedOutException : SimklException("Not signed in to Simkl")

internal const val SIMKL_ERROR_USER_LIMIT = "user_limit_exceeded"
internal const val SIMKL_ERROR_RATE_LIMIT = "rate_limit"

/** The one boundary instance for Simkl JSON (KT-JSON-02). */
internal val simklJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    coerceInputValues = false
}

internal fun buildSimklUrl(configuration: SimklApiConfiguration, request: SimklRequest, clientId: String): String {
    val builder = configuration.baseUrl.toHttpUrl().newBuilder().encodedPath(request.path)
    request.query.filterKeys { it !in REQUIRED_QUERY_KEYS }.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    builder.addQueryParameter("client_id", clientId)
    builder.addQueryParameter("app-name", configuration.appName)
    builder.addQueryParameter("app-version", configuration.appVersion)
    return builder.build().toString()
}

internal fun simklHeaders(configuration: SimklApiConfiguration, accessToken: String?, hasBody: Boolean): Map<String, String> =
    buildMap {
        put("User-Agent", "NuvioKE/${configuration.appVersion}")
        put("Accept", "application/json")
        accessToken?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
        if (hasBody) put("Content-Type", "application/json")
    }

/** The body's `error` field, or null when the body is not Simkl's error envelope. Never logs the body (KT-JSON-04). */
internal fun simklErrorCode(body: String): String? {
    if (body.isBlank() || body.length > MAX_ERROR_BODY_CHARS) return null
    return try {
        simklJson.decodeFromString(SimklErrorEnvelope.serializer(), body).error?.takeIf(String::isNotBlank)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class SimklErrorEnvelope(@SerialName("error") val error: String? = null)

private val SAFE_PATH = Regex("(/[A-Za-z0-9_-]+)+")
private val REQUIRED_QUERY_KEYS = setOf("client_id", "app-name", "app-version")
private const val MAX_ERROR_BODY_CHARS = 16 * 1024
