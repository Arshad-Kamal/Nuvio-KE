package com.nuvio.tv.livetv.source

import com.nuvio.tv.core.di.Download
import com.nuvio.tv.core.network.executeCancellable
import com.nuvio.tv.core.network.tls.isAddonCertificateFailure
import com.nuvio.tv.livetv.parse.XmltvNowNextParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A source fetch that failed for a reason worth showing on the Sources route; [message] is
 * user-facing. [httpCode] is the status of a non-2xx answer, else null.
 */
open class SourceFetchException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Downloads a Live TV source over the `@Download` client (Spec 34 D10: no body cap there, so this
 * class enforces one) and detects what it serves from its first bytes. A gzip body is recognised
 * by its magic bytes, whatever the URL or headers say, and inflated; the cap counts inflated
 * bytes. A source fetch (`sourceCaps`) caps by the detected kind: [MAX_PLAYLIST_BYTES] for playlists
 * (whose parser bounds what it keeps, Spec 137), [XmltvNowNextParser.MAX_INPUT_BYTES] for guides (whose
 * parser guards the stream again with its entity and text-run checks), [MAX_TEXT_BYTES] for manifests
 * and anything undetected. Every other fetch (addon catalogs and streams, metadata, the pack, the
 * Xtream sign-in and categories) takes [MAX_TEXT_BYTES] whatever its head looks like: those callers
 * read the body whole. A `streamed` fetch (the Xtream stream list, decoded one bounded element at a
 * time) takes [MAX_PLAYLIST_BYTES], as a playlist does.
 */
@Singleton
class SourceFetcher internal constructor(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val maxTextBytes: Long = MAX_TEXT_BYTES,
    private val maxPlaylistBytes: Long = MAX_PLAYLIST_BYTES
) {
    @Inject
    constructor(@Download client: OkHttpClient) : this(client, Dispatchers.IO)

    /**
     * GETs [url] and hands [read] the detected kind (null when unrecognised) and the capped,
     * inflated body, capped by its kind when [sourceCaps] (only a parse that streams a source's
     * playlist or guide), else at [MAX_TEXT_BYTES], or [MAX_PLAYLIST_BYTES] when [streamed] (a caller
     * that decodes its body in bounded pieces, as the Xtream stream list); [read] runs on the IO dispatcher and the body closes when it returns. The
     * whole call, body read included, is bounded by [CALL_TIMEOUT_MINUTES] (`@Download` sets no
     * call timeout, so a slow-drip host could otherwise hold a refresh open indefinitely).
     * Throws [SourceFetchException] on a non-2xx status or an exceeded cap, IOException on
     * network failure.
     */
    suspend fun <T> fetch(
        url: String,
        sourceCaps: Boolean = false,
        streamed: Boolean = false,
        read: (SourceKind?, InputStream) -> T
    ): T = withContext(io) {
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            throw SourceFetchException("Not a web address")
        }
        val call = client.newCall(request).apply { timeout().timeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
        withCertificateRefusalAsReason {
            call.executeCancellable { response ->
                if (!response.isSuccessful) throw SourceFetchException("HTTP ${response.code}", response.code)
                val (kind, body) = if (sourceCaps) {
                    sniff(response.body.byteStream(), maxTextBytes, maxPlaylistBytes)
                } else {
                    val cap = if (streamed) maxPlaylistBytes else maxTextBytes
                    sniff(response.body.byteStream(), cap, cap, cap)
                }
                read(kind, body)
            }
        }
    }

    /** What a HEAD request answered: [length] = its Content-Length, null when it sent none. */
    data class Probe(val code: Int, val length: Long?)

    /**
     * HEADs [url] (redirects followed), bounded by [PROBE_TIMEOUT_SECONDS]: the status and size a
     * guide would have, without its body (Spec 55 S1). Throws IOException on network failure or
     * a URL OkHttp refuses ([SourceFetchException]).
     */
    suspend fun probe(url: String): Probe = withContext(io) {
        val request = try {
            Request.Builder().url(url).head().build()
        } catch (e: IllegalArgumentException) {
            throw SourceFetchException("Not a web address")
        }
        val call = client.newCall(request).apply { timeout().timeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        withCertificateRefusalAsReason {
            call.executeCancellable { response ->
                Probe(response.code, response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 })
            }
        }
    }

    /**
     * Spec 68 P9: a host whose certificate the `@Download` client refuses fails with a fixed reason
     * the Sources route and the player can show (a Live TV host cannot be trusted until X8).
     */
    private inline fun <T> withCertificateRefusalAsReason(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e !is SourceFetchException && e.isAddonCertificateFailure()) throw SourceFetchException(CERTIFICATE_REFUSED)
        throw e
    }

    internal companion object {
        // Spec 68 D11 Q7: English like this class's other fixed reasons.
        const val CERTIFICATE_REFUSED = "Certificate not trusted"

        /** Cap on an inflated manifest or undetected body (the pack's TSVs). */
        const val MAX_TEXT_BYTES = 32L * 1024 * 1024
        /**
         * Cap on an inflated playlist (Spec 137 D1): full Xtream `m3u_plus` lists pass 32 MiB through movie
         * and series items the parser drops (estimated 130-250 MB for 400k-700k items), and the parser's own
         * bounds keep its memory independent of file size, so this bounds only download and parse time.
         */
        const val MAX_PLAYLIST_BYTES = 512L * 1024 * 1024
        /** Whole-call bound; a 50 MB guide at a slow 2 Mbit/s still fits. */
        const val CALL_TIMEOUT_MINUTES = 5L
        /** One [probe]'s bound: a HEAD carries no body, so a slow answer means a struggling host. */
        const val PROBE_TIMEOUT_SECONDS = 10L
        private const val HEAD_BYTES = 4096
        private val TV_ROOT = Regex("""<tv[\s>/]""")

        /** Inflates [raw] when gzip, detects the kind from its head and caps it by that kind. */
        fun sniff(
            raw: InputStream,
            maxTextBytes: Long = MAX_TEXT_BYTES,
            maxPlaylistBytes: Long = MAX_PLAYLIST_BYTES,
            maxGuideBytes: Long = XmltvNowNextParser.MAX_INPUT_BYTES
        ): Pair<SourceKind?, InputStream> {
            val outer = BufferedInputStream(raw)
            val inflated = if (startsWithGzipMagic(outer)) BufferedInputStream(GZIPInputStream(outer, 64 * 1024)) else outer
            inflated.mark(HEAD_BYTES)
            val head = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < HEAD_BYTES) {
                val r = inflated.read(head, n, HEAD_BYTES - n)
                if (r < 0) break
                n += r
            }
            inflated.reset()
            val kind = detectKind(String(head, 0, n, Charsets.UTF_8))
            val cap = when (kind) {
                SourceKind.XMLTV -> maxGuideBytes
                SourceKind.M3U -> maxPlaylistBytes
                else -> maxTextBytes
            }
            return kind to CappedInputStream(inflated, cap)
        }

        /**
         * XML with a `<tv` root = guide; a JSON object = Stremio manifest candidate (whether it
         * declares `tv` is the adapter's check); else a playlist when any line of the head starts
         * with an M3U directive ([M3U_DIRECTIVES], Spec 90 L6: comment or blank lines may come
         * first) or its first non-comment line is a bare http(s) URL (a header-less URL list; the
         * parser tolerates the missing header). Null for anything else (HTML error pages, raw video, images).
         */
        fun detectKind(head: String): SourceKind? {
            val text = head.removePrefix("\uFEFF").trimStart()
            return when {
                text.startsWith("<") && TV_ROOT.containsMatchIn(text) -> SourceKind.XMLTV
                text.startsWith("{") -> SourceKind.STREMIO
                looksLikeM3u(text) -> SourceKind.M3U
                else -> null
            }
        }

        private fun looksLikeM3u(text: String): Boolean {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { line -> M3U_DIRECTIVES.any { line.startsWith(it, ignoreCase = true) } }) return true
            val first = lines.firstOrNull { !it.startsWith("#") } ?: return false
            return (first.startsWith("http://", ignoreCase = true) || first.startsWith("https://", ignoreCase = true)) &&
                first.none(Char::isWhitespace)
        }

        private val M3U_DIRECTIVES = listOf("#EXTM3U", "#EXTINF", "#KODIPROP", "#EXTVLCOPT")

        private fun startsWithGzipMagic(input: BufferedInputStream): Boolean {
            input.mark(2)
            val b0 = input.read()
            val b1 = input.read()
            input.reset()
            return b0 == 0x1f && b1 == 0x8b
        }
    }
}

/** Throws [SourceFetchException] once more than [max] bytes were read. */
internal class CappedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0 && ++count > max) throw tooLarge()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw tooLarge()
        }
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        count += skipped
        if (count > max) throw tooLarge()
        return skipped
    }

    override fun markSupported(): Boolean = false

    private fun tooLarge() = SourceFetchException("Larger than ${max / (1024 * 1024)} MiB")
}
