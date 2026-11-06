package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import kotlinx.serialization.json.JsonObject

// Response / lifecycle plumbing shared by AddonConfigServer and StreamBadgeConfigServer.

private const val JSON_MIME = "application/json; charset=utf-8"

internal fun jsonResponse(status: Response.IStatus, body: String): Response =
    NanoHTTPD.newFixedLengthResponse(status, JSON_MIME, body)

internal fun notFoundResponse(): Response =
    NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")

/** Serves the app logo PNG, or 404 when none is available. */
internal fun pngOrNotFound(bytes: ByteArray?): Response =
    if (bytes != null) {
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    } else {
        notFoundResponse()
    }

/**
 * Reads the capped POST body and parses it as a JSON object before handing it
 * to [handle]: 413 when the declared length exceeds [MAX_POST_BODY_BYTES], 400
 * (`{"error": invalidBodyMessage()}`) when the body is empty, malformed, or not
 * an object. A bad body never reaches a handler as "no fields".
 */
internal inline fun IHTTPSession.withJsonBody(
    invalidBodyMessage: () -> String,
    handle: (JsonObject) -> Response
): Response {
    val body = readCappedUtf8Body()
        ?: return NanoHTTPD.newFixedLengthResponse(
            Response.Status.PAYLOAD_TOO_LARGE,
            NanoHTTPD.MIME_PLAINTEXT,
            "Request body too large"
        )
    val parsed = parseJsonBody(body)
        ?: return jsonResponse(Response.Status.BAD_REQUEST, jsonMessage("error", invalidBodyMessage()))
    return handle(parsed)
}

/** A fresh 128-bit token for a phone page's path (PhotoUploadServer, TmdbKeyServer), hex. */
internal fun newSessionToken(): String = ByteArray(16).also(SecureRandom()::nextBytes).toHexString()

/**
 * Starts the server built by [create] on the first free port in
 * [startPort] until startPort + [maxAttempts]; null when every port is taken.
 */
internal fun <S : NanoHTTPD> startOnFirstAvailablePort(
    startPort: Int,
    maxAttempts: Int,
    create: (port: Int) -> S
): S? {
    for (port in startPort until startPort + maxAttempts) {
        val server = create(port)
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            return server
        } catch (_: IOException) {
            // Port in use, try next
        }
    }
    return null
}

/**
 * This context re-localized to the app's chosen language (LocaleCache, hydrated
 * from the app_locale prefs at startup and updated on change), so the served web
 * pages match the TV UI; unchanged when the system language is in use.
 */
internal fun Context.withAppLocale(): Context {
    val tag = LocaleCache.localeTag.takeIf { it.isNotEmpty() && it != LocaleCache.UNSET }
        ?: return this
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(config)
}

/**
 * Text for a single-quoted JS string literal in the served pages (L10-42): escapes the
 * backslash, the quote and newlines. One home for AddonWebPage and StreamBadgeWebPage.
 */
internal fun String.jsEscaped(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
