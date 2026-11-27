package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What the TV made of one pasted value. */
sealed interface PasteCheck {
    /** Kept; the phone shows its Saved page. */
    data object Saved : PasteCheck

    /** Not kept: the service does not know the value. The phone keeps it for a fix. */
    data object Refused : PasteCheck

    /** Not kept: the service could not be asked (network, its outage, its limit). */
    data object Unreachable : PasteCheck

    /** The TV-side panel closed before the value was checked: nothing kept. */
    data object Closed : PasteCheck
}

/**
 * A LAN page where a phone pastes one value (a key, an app id) for the TV (Spec 106 D10: the trackers' setup; the TMDB
 * key, access header and Rating posters pages keep their own servers). One instance is one panel session with its own
 * 128-bit token (68/X3): every route lives under `/p/<token>/`. Routes: `GET /p/<t>/` the page ([pageHtml]);
 * `POST /p/<t>/value` the value as a text body of at most [maxBodyBytes], handed to [submit] (the check and save, run
 * on this handler thread) so the phone shows the outcome.
 *
 * Answers never carry the value back: 200 saved; 422 refused; 503 unreachable; 410 the panel closed; 404 another
 * session's token; 400/411/413 a body that isn't one. [close] never cuts an answer (TmdbKeyServer's rule): POSTs from
 * then on answer 410 at once, and a daemon thread stops the server once the POSTs in flight have answered, plus
 * [stopGraceMs], after [stopWaitMs] at most.
 */
class PasteKeyServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val submit: (PasteKeyServer, String) -> PasteCheck,
    private val maxBodyBytes: Int,
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
        thread(isDaemon = true, name = "PasteKeyServer-stop") {
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
            session.method == Method.POST && path == "value" -> receive(session)
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
            val bytes = when (val body = session.readCappedBytes(maxBodyBytes)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return plain(Response.Status.LENGTH_REQUIRED)
                CappedBody.TooLarge -> return plain(Response.Status.PAYLOAD_TOO_LARGE)
                CappedBody.Short -> return plain(Response.Status.BAD_REQUEST)
            }
            val value = String(bytes, StandardCharsets.UTF_8).trim()
            if (value.isEmpty()) return plain(Response.Status.BAD_REQUEST)
            return when (submit(this, value)) {
                PasteCheck.Saved -> plain(Response.Status.OK)
                PasteCheck.Refused -> plain(UnprocessableEntity)
                PasteCheck.Unreachable -> plain(Response.Status.SERVICE_UNAVAILABLE)
                PasteCheck.Closed -> plain(Response.Status.GONE)
            }
        } catch (_: IOException) {
            return plain(Response.Status.BAD_REQUEST)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun IHTTPSession.earlyAnswer(status: Response.IStatus): Response {
        drainDeclaredBody(maxBodyBytes)
        return plain(status)
    }

    private fun plain(status: Response.IStatus): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, status.description)

    companion object {
        /** After the last answer, for its bytes to leave before stop() closes the socket. */
        internal const val STOP_GRACE_MS = 1_000L

        /** The longest a close waits for a check in flight: a 30 s tracker call, margin. */
        internal const val STOP_WAIT_MS = 35_000L

        private const val POLL_MS = 50L
        private const val PATH_PREFIX = "/p/"

        /** [startPort]: each caller's own range of ten (8160-8169 Simkl). */
        fun startOnAvailablePort(
            pageHtml: () -> String,
            submit: (PasteKeyServer, String) -> PasteCheck,
            maxBodyBytes: Int,
            startPort: Int,
            maxAttempts: Int = 10,
        ): PasteKeyServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            PasteKeyServer(newSessionToken(), pageHtml, submit, maxBodyBytes, hostname = null, port = port)
        }
    }
}
