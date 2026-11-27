package com.nuvio.tv.data.mdblist

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends MDBList calls one at a time and retries only reads: 500/502/503/504 (1, 2, 4 s plus jitter), the 5-minute `429`
 * when MDBList asks for a short wait (`Retry-After`, at most [MAX_RETRY_AFTER_TOTAL_SECONDS] summed per call) and a
 * dropped connection. A write is sent once: it may have landed, so it is never resent (the engine's OkHttp client has
 * `retryOnConnectionFailure` off for the same reason). Everything else throws at once ([MdbListApiException]):
 * 400/401/403/404 and the daily `429`, which the session turns into a pause (Spec 105 D5). The 5-minute windows (1,000
 * reads, 300 writes) are far above what one TV sends, so there is no pacing.
 */
class MdbListClient(
    private val engine: MdbListHttpEngine,
    private val configuration: MdbListApiConfiguration,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterMs: () -> Long = { Random.nextLong(MAX_JITTER_MS + 1) }
) {
    private val lock = Mutex()

    /**
     * Everything runs under the lock, so the next queued call sees its effect: [apiKey] is read right before the first
     * attempt (a key disconnected or replaced while the call waited is never sent; it may throw to send nothing, e.g.
     * the session's quota check), [onAnswer] gets the key used and the reply or the [MdbListApiException] MDBList
     * answered with.
     */
    suspend fun execute(
        request: MdbListRequest,
        apiKey: suspend () -> String,
        onAnswer: suspend (key: String, MdbListResponse?, MdbListApiException?) -> Unit = { _, _, _ -> }
    ): MdbListResponse = lock.withLock {
        val key = apiKey()
        val response = try {
            send(request, key)
        } catch (failure: MdbListApiException) {
            onAnswer(key, null, failure)
            throw failure
        }
        onAnswer(key, response, null)
        response
    }

    private suspend fun send(request: MdbListRequest, apiKey: String): MdbListResponse {
        val url = buildMdbListUrl(configuration, request, apiKey)
        val headers = mdbListHeaders(configuration, hasBody = request.jsonBody != null)
        val isRead = request.method == MdbListHttpMethod.GET
        var attempt = 0
        var waitedSeconds = 0L
        while (true) {
            val raw = try {
                engine.execute(request.method, url, headers, request.jsonBody)
            } catch (failure: MdbListException) {
                throw failure
            } catch (failure: IOException) {
                // The cause is not kept: an OkHttp message can carry the URL, and the URL carries the key.
                if (!isRead || attempt >= MAX_RETRIES) throw MdbListException("MDBList unreachable")
                sleep(backoffMs(attempt++))
                continue
            }
            if (raw.status in 200..299) return MdbListResponse(raw.status, raw.body, raw.headers)
            val retryAfter = raw.headers.longHeader("retry-after")
            val daily = raw.status == 429 && isDailyLimitError(raw.body)
            val retryDelay = when {
                !isRead || attempt >= MAX_RETRIES -> null
                raw.status == 429 && !daily && retryAfter != null && retryAfter >= 0 &&
                    waitedSeconds + retryAfter <= MAX_RETRY_AFTER_TOTAL_SECONDS -> {
                    waitedSeconds += retryAfter
                    retryAfter * 1_000L
                }
                raw.status in RETRYABLE_SERVER_ERRORS -> backoffMs(attempt)
                else -> null
            } ?: throw MdbListApiException(
                status = raw.status,
                dailyLimit = daily,
                retryAfterSeconds = retryAfter,
                rateLimitLimit = raw.headers.intHeader("x-ratelimit-limit"),
                rateLimitResetEpochSeconds = raw.headers.longHeader("x-ratelimit-reset")
            )
            attempt++
            sleep(retryDelay)
        }
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl attempt) + jitterMs().coerceIn(0L, MAX_JITTER_MS)

    private companion object {
        const val MAX_RETRIES = 3
        const val MAX_JITTER_MS = 1_000L

        /** Longer is a closed window: the call fails instead of holding every queued call behind it. */
        const val MAX_RETRY_AFTER_TOTAL_SECONDS = 60L
        val RETRYABLE_SERVER_ERRORS = setOf(500, 502, 503, 504)
    }
}
