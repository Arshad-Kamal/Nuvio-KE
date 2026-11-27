package com.nuvio.tv.data.simkl

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends Simkl calls one at a time (Simkl: one request in flight per token; uncached endpoints must not run in
 * parallel), paced at 10 GET/s and 1 POST/s, and retries only what Simkl says is worth retrying: a per-second
 * `429 rate_limit` (after ~1 s), 500/502/503 (1, 2, 4 s plus jitter) and, for reads only, a dropped connection. A
 * write that may have landed is never resent on a dropped connection. Everything else throws at once
 * ([SimklApiException]): 400/401/403/404/409/412 and the daily `429 user_limit_exceeded`. One lock serves every
 * profile; a TV runs one profile at a time, so that costs nothing in practice.
 */
class SimklClient(
    private val engine: SimklHttpEngine,
    private val configuration: SimklApiConfiguration,
    /** Monotonic milliseconds for pacing: a wall-clock step must not stall or burst calls. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterMs: () -> Long = { Random.nextLong(MAX_JITTER_MS + 1) }
) {
    private val lock = Mutex()
    private var nextGetAt: Long? = null
    private var nextPostAt: Long? = null

    /**
     * [accessToken] null = an anonymous call (catalog endpoints, `/oauth2/`). [clientId] null = this TV's app id (none
     * set up: [SimklSignedOutException], nothing sent); a caller whose body names the id passes the same one. The hooks
     * run under the lock, so the next queued call sees their effect: [beforeSend] right before the first attempt (the
     * session's quota check), [onAnswer] with the reply or the [SimklApiException] Simkl answered with (the session's
     * quota record).
     */
    suspend fun execute(
        request: SimklRequest,
        accessToken: String?,
        clientId: String? = null,
        beforeSend: () -> Unit = {},
        onAnswer: (SimklResponse?, SimklApiException?) -> Unit = { _, _ -> }
    ): SimklResponse {
        val id = if (clientId == null) configuration.requireClientId() else clientId.ifBlank { throw SimklSignedOutException() }
        return lock.withLock { executeLocked(request, accessToken, id, beforeSend, onAnswer) }
    }

    private suspend fun executeLocked(
        request: SimklRequest,
        accessToken: String?,
        clientId: String,
        beforeSend: () -> Unit,
        onAnswer: (SimklResponse?, SimklApiException?) -> Unit
    ): SimklResponse {
        beforeSend()
        val response = try {
            send(request, accessToken, clientId)
        } catch (failure: SimklApiException) {
            onAnswer(null, failure)
            throw failure
        }
        onAnswer(response, null)
        return response
    }

    private suspend fun send(request: SimklRequest, accessToken: String?, clientId: String): SimklResponse {
        val url = buildSimklUrl(configuration, request, clientId)
        val headers = simklHeaders(configuration, accessToken, hasBody = request.jsonBody != null)
        var attempt = 0
        while (true) {
            val raw = try {
                paced(request.method) { engine.execute(request.method, url, headers, request.jsonBody) }
            } catch (failure: SimklException) {
                throw failure
            } catch (failure: IOException) {
                if (request.method != SimklHttpMethod.GET || attempt >= MAX_RETRIES) throw SimklException("Simkl unreachable", failure)
                sleep(backoffMs(attempt++))
                continue
            }
            if (raw.status in 200..299 || (request.redirectIsAnswer && raw.status in 300..399)) {
                return SimklResponse(raw.status, raw.body, raw.headers)
            }
            val error = simklErrorCode(raw.body)
            val retryDelay = when {
                attempt >= MAX_RETRIES -> null
                raw.status == 429 && error == SIMKL_ERROR_RATE_LIMIT -> RATE_LIMIT_RETRY_MS
                raw.status == 500 || raw.status == 502 || raw.status == 503 -> backoffMs(attempt)
                else -> null
            } ?: throw SimklApiException(
                status = raw.status,
                error = error,
                retryAfterSeconds = raw.headers["retry-after"]?.trim()?.toLongOrNull(),
                rateLimitLimit = raw.headers.intHeader("x-ratelimit-limit")
            )
            attempt++
            sleep(retryDelay)
        }
    }

    private suspend fun <T> paced(method: SimklHttpMethod, block: suspend () -> T): T {
        val readyAt = if (method == SimklHttpMethod.GET) nextGetAt else nextPostAt
        val wait = readyAt?.let { it - monotonicMs() } ?: 0L
        if (wait > 0) sleep(wait)
        try {
            return block()
        } finally {
            val done = monotonicMs()
            if (method == SimklHttpMethod.GET) nextGetAt = done + GET_SPACING_MS else nextPostAt = done + POST_SPACING_MS
        }
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl attempt) + jitterMs().coerceIn(0L, MAX_JITTER_MS)

    private companion object {
        const val MAX_RETRIES = 3
        const val GET_SPACING_MS = 100L
        const val POST_SPACING_MS = 1_000L
        const val RATE_LIMIT_RETRY_MS = 1_100L
        const val MAX_JITTER_MS = 1_000L
    }
}
