package com.nuvio.tv.core.posters

import com.nuvio.tv.core.server.CappedBody
import com.nuvio.tv.core.server.INLINE_PAGE_CSP
import com.nuvio.tv.core.server.UnprocessableEntity
import com.nuvio.tv.core.server.drainDeclaredBody
import com.nuvio.tv.core.server.jsonResponse
import com.nuvio.tv.core.server.notFoundResponse
import com.nuvio.tv.core.server.newSessionToken
import com.nuvio.tv.core.server.pathAfterToken
import com.nuvio.tv.core.server.readCappedBytes
import com.nuvio.tv.core.server.rejectUntrustedRequest
import com.nuvio.tv.core.server.startOnFirstAvailablePort
import com.nuvio.tv.core.server.withTokenPathHeaders
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The LAN page behind the Rating posters panel's QR (Spec 114, sandbox `s114-rating-posters` stages 2-3
 * and 7). One instance is one panel session with its own 128-bit token (68/X3): every route lives under
 * `/r/<token>/`. Routes: `GET /r/<t>/` the page ([pageHtml]); `POST /r/<t>/address` the template as a text
 * body of at most [MAX_ADDRESS_BODY_BYTES], handed to [submit] (the save check, run on this handler
 * thread) so the phone shows the outcome.
 *
 * Answers are JSON and never carry the template (no route reads it back, P0-6): 200 `{"host","http"}`
 * saved; 422 `{"problem","host"?,"code"?}` not accepted ([problemOf]); 410 the panel closed; 404 another
 * session's token; 400/411/413 a body that isn't one.
 *
 * [close] never cuts an answer (TmdbKeyServer's rule): POSTs from then on answer 410 at once, and a daemon
 * thread stops the server once the POSTs in flight have answered, plus [stopGraceMs], after [stopWaitMs] at
 * most.
 */
class RatingPosterServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val submit: (RatingPosterServer, String) -> RatingPosterCheck,
    hostname: String?,
    port: Int,
    private val stopGraceMs: Long = STOP_GRACE_MS,
    private val stopWaitMs: Long = STOP_WAIT_MS,
) : NanoHTTPD(hostname, port) {

    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger()

    /** True once [close] ran: a send not yet checked is answered 410 without one. */
    val isClosed: Boolean get() = closed.get()

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    /** Ends the session and stops the server once nothing is in flight; safe to call more than once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "RatingPosterServer-stop") {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stopWaitMs)
            // A POST counted after this loop saw 0 reads closed and answers 410 at once: the grace covers it.
            while (inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            Thread.sleep(stopGraceMs.coerceAtMost(left).coerceAtLeast(0))
            stop()
        }
    }

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            // A send from a page whose panel has closed or reopened: drained, so its browser reads "expired".
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.POST && path == "address" -> receive(session)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", INLINE_PAGE_CSP)
        }

    private fun receive(session: IHTTPSession): Response {
        // Counted before reading closed: close() either waits for this POST or this POST sees closed.
        inFlight.incrementAndGet()
        try {
            if (closed.get()) return session.earlyAnswer(Response.Status.GONE)
            val bytes = when (val body = session.readCappedBytes(MAX_ADDRESS_BODY_BYTES)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return plain(Response.Status.LENGTH_REQUIRED)
                CappedBody.TooLarge -> return plain(Response.Status.PAYLOAD_TOO_LARGE)
                CappedBody.Short -> return plain(Response.Status.BAD_REQUEST)
            }
            val address = String(bytes, StandardCharsets.UTF_8).trim()
            if (address.isEmpty()) return plain(Response.Status.BAD_REQUEST)
            return answer(submit(this, address))
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun answer(check: RatingPosterCheck): Response {
        if (check is RatingPosterCheck.Saved) {
            return jsonResponse(
                Response.Status.OK,
                buildJsonObject {
                    put("host", check.host)
                    put("http", check.isHttp)
                }.toString()
            )
        }
        val problem = problemOf(check) ?: return plain(Response.Status.GONE)
        return jsonResponse(
            UnprocessableEntity,
            buildJsonObject {
                put("problem", problem)
                when (check) {
                    is RatingPosterCheck.KeyRejected -> {
                        put("host", check.host)
                        put("code", check.code)
                    }
                    is RatingPosterCheck.NotFound -> put("host", check.host)
                    is RatingPosterCheck.NoPoster -> put("host", check.host)
                    else -> Unit
                }
            }.toString()
        )
    }

    private fun IHTTPSession.earlyAnswer(status: Response.IStatus): Response {
        drainDeclaredBody(MAX_ADDRESS_BODY_BYTES)
        return plain(status)
    }

    private fun plain(status: Response.IStatus): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, status.description)

    companion object {
        /** First port tried: after AccessHeaderServer's 8140-8149 (Spec 97 A5). */
        const val DEFAULT_PORT = 8150

        /** A template of [RatingPosterTemplate.MAX_LENGTH] characters, with room for a paste's whitespace and UTF-8. */
        const val MAX_ADDRESS_BODY_BYTES = 8 * 1024

        /** After the last answer, for its bytes to leave before stop() closes the socket. */
        internal const val STOP_GRACE_MS = 1_000L

        /** The longest a close waits for a check in flight: the 10 s test poster, the save, margin. */
        internal const val STOP_WAIT_MS = 15_000L

        private const val POLL_MS = 50L
        private const val PATH_PREFIX = "/r/"

        /** The phone page's word for a refusal (the page picks its text by it); null when [check] isn't one. */
        internal fun problemOf(check: RatingPosterCheck): String? = when (check) {
            is RatingPosterCheck.Refused -> when (check.reason) {
                RatingPosterTemplate.Refusal.HTTP_NOT_HOME_NETWORK -> "https"
                RatingPosterTemplate.Refusal.NO_ID_PLACEHOLDER -> "no_id"
                else -> "format"
            }
            RatingPosterCheck.Untestable -> "untestable"
            is RatingPosterCheck.KeyRejected -> "key"
            is RatingPosterCheck.NotFound -> "address"
            is RatingPosterCheck.NoPoster -> "no_poster"
            is RatingPosterCheck.Saved, RatingPosterCheck.Closed -> null
        }

        fun startOnAvailablePort(
            pageHtml: () -> String,
            submit: (RatingPosterServer, String) -> RatingPosterCheck,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10,
        ): RatingPosterServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            RatingPosterServer(newSessionToken(), pageHtml, submit, hostname = null, port = port)
        }
    }
}
