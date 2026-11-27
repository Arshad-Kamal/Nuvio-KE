package com.nuvio.tv.core.server

import android.util.Log
import com.nuvio.tv.data.local.UserSubtitleFontAddResult
import com.nuvio.tv.data.local.UserSubtitleFontStore
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The LAN page behind the Subtitles › Font panel's QR (Spec 132 D1, sandbox spec-132-subtitle-fonts
 * stages 3 and 8). One instance is one session: its own 128-bit [token] (every route lives under
 * `/f/<token>/`), one upload at a time.
 *
 * Routes: `GET /f/<t>/` the phone page ([pageHtml]); `POST /f/<t>/font?name=<file name>` the raw
 * font bytes. The POST answers in this order: guard, token, full (409), one upload at a time (409),
 * a name (400), Content-Length (411 / 413), read, then [addFont] (the store checks the font and
 * saves it). Every answer is `{"result": <word>}` (plus the family and counts on success); the page
 * maps the word to its own text and shows the family through textContent only.
 */
class SubtitleFontServer internal constructor(
    private val token: String,
    private val pageHtml: () -> String,
    private val fontCount: () -> Int,
    private val addFont: suspend (fileName: String, bytes: ByteArray) -> UserSubtitleFontAddResult,
    hostname: String?,
    port: Int
) : NanoHTTPD(hostname, port) {

    private val receiving = AtomicBoolean(false)

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            // A POST from a page whose panel has closed or reopened: drained, so its browser reads "expired".
            ?: return if (session.method == Method.POST) session.earlyAnswer(Response.Status.NOT_FOUND, EXPIRED) else notFoundResponse()
        return when {
            session.method == Method.GET && path.isEmpty() -> page()
            session.method == Method.POST && path == "font" -> receive(session)
            else -> notFoundResponse()
        }
    }

    private fun page(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()).apply {
            addHeader("Content-Security-Policy", PAGE_CSP)
        }

    private fun receive(session: IHTTPSession): Response {
        if (fontCount() >= UserSubtitleFontStore.CAP) return session.earlyAnswer(Response.Status.CONFLICT, FULL)
        if (!receiving.compareAndSet(false, true)) return session.earlyAnswer(Response.Status.CONFLICT, BUSY)
        try {
            val name = session.parameters["name"]?.singleOrNull()
                ?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_CHARS }
                ?: return session.earlyAnswer(Response.Status.BAD_REQUEST, BAD_REQUEST)
            val bytes = when (val body = session.readCappedBytes(UserSubtitleFontStore.MAX_BYTES)) {
                is CappedBody.Bytes -> body.bytes
                CappedBody.NoLength -> return result(Response.Status.LENGTH_REQUIRED, BAD_REQUEST)
                CappedBody.TooLarge -> return result(Response.Status.PAYLOAD_TOO_LARGE, TOO_BIG)
                CappedBody.Short -> return result(Response.Status.BAD_REQUEST, BAD_REQUEST)
            }
            // NanoHTTPD runs each request on its own thread: the blocking bridge into the store.
            return when (val added = runBlocking { addFont(name, bytes) }) {
                is UserSubtitleFontAddResult.Added -> saved(SAVED, added.font.family, fontCount())
                is UserSubtitleFontAddResult.AlreadyThere -> saved(ALREADY, added.font.family, fontCount())
                UserSubtitleFontAddResult.Full -> result(Response.Status.CONFLICT, FULL)
                UserSubtitleFontAddResult.TooBig -> result(Response.Status.PAYLOAD_TOO_LARGE, TOO_BIG)
                UserSubtitleFontAddResult.NotAFont -> result(UnprocessableEntity, NOT_A_FONT)
                UserSubtitleFontAddResult.Failed -> result(Response.Status.INTERNAL_ERROR, FAILED)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Font upload dropped: ${e.javaClass.simpleName}")
            return result(Response.Status.BAD_REQUEST, BAD_REQUEST)
        } catch (e: RuntimeException) {
            // The store failing unexpectedly (a SecurityException, say): the phone still gets an answer.
            Log.w(TAG, "Font upload failed: ${e.javaClass.simpleName}")
            return result(Response.Status.INTERNAL_ERROR, FAILED)
        } finally {
            receiving.set(false)
        }
    }

    private fun saved(word: String, family: String, count: Int): Response =
        jsonResponse(
            Response.Status.OK,
            buildJsonObject {
                put("result", word)
                put("family", family)
                put("count", count)
                put("cap", UserSubtitleFontStore.CAP)
            }.toString()
        )

    private fun result(status: Response.IStatus, word: String): Response =
        jsonResponse(status, buildJsonObject { put("result", word) }.toString())

    /** An answer before the body is read (404, 409, 400), after draining a body within the cap. */
    private fun IHTTPSession.earlyAnswer(status: Response.IStatus, word: String): Response {
        drainDeclaredBody(UserSubtitleFontStore.MAX_BYTES)
        return result(status, word)
    }

    companion object {
        private const val TAG = "SubtitleFontServer"

        /** First port tried: after Spec 106's paste-key pages (8160-8169); nothing else claims 817x. */
        const val DEFAULT_PORT = 8170

        /** Result words, mapped to the page's own text. */
        const val SAVED = "saved"
        const val ALREADY = "already"
        const val FULL = "full"
        const val BUSY = "busy"
        const val TOO_BIG = "too_big"
        const val NOT_A_FONT = "not_font"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
        const val BAD_REQUEST = "bad_request"

        /** A phone file name is far shorter; the store keeps at most 120 UTF-8 bytes of it. */
        private const val MAX_NAME_CHARS = 1024

        private const val PATH_PREFIX = "/f/"

        /** Inline only; the page previews the chosen file through a blob: URL font. */
        private const val PAGE_CSP =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; font-src blob:; " +
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        fun startOnAvailablePort(
            pageHtml: () -> String,
            fontCount: () -> Int,
            addFont: suspend (fileName: String, bytes: ByteArray) -> UserSubtitleFontAddResult,
            startPort: Int = DEFAULT_PORT,
            maxAttempts: Int = 10
        ): SubtitleFontServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            SubtitleFontServer(newSessionToken(), pageHtml, fontCount, addFont, hostname = null, port = port)
        }
    }
}
