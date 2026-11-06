package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.nuvio.tv.core.player.PlayerMediaSourceFactory
import com.nuvio.tv.core.player.PlayerPlaybackNetworking
import com.nuvio.tv.livetv.model.LiveStream
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Builds Media3 sources for Live TV streams (Spec 34 D7, L6a) on the shared playback stack
 * ([PlayerPlaybackNetworking] client, [PlayerMediaSourceFactory] header and mime helpers) with the
 * live overrides: connect 4 s / read 8 s, [LiveLoadErrorHandlingPolicy], and none of the VOD
 * extras (no parallel ranges, MP4 session, prewarm or per-open logging: ranged requests against
 * an endless stream are wrong, and a playlist refresh every few seconds would flood the log).
 */
@OptIn(UnstableApi::class)
internal object LiveMediaSources {

    const val CONNECT_TIMEOUT_MS = 4_000L
    const val READ_TIMEOUT_MS = 8_000L
    private const val SNIFF_BYTES = 512L

    /** A stream ready to play: URL without `user:pass@`, sanitized headers (Basic auth added). */
    data class LiveRequest(val url: String, val headers: Map<String, String>)

    fun normalize(stream: LiveStream): LiveRequest =
        PlayerMediaSourceFactory.normalizePlaybackRequest(stream.url, stream.headers)
            .let { LiveRequest(it.url, it.headers) }

    /**
     * The shared playback client with the live timeouts (call timeout stays 0: streams never end)
     * and without the VOD per-call `PlaybackConnectionEvents` log (a playlist refresh every few
     * seconds would flood it).
     */
    fun client(headers: Map<String, String>): OkHttpClient =
        PlayerPlaybackNetworking.createHttpClient(headers).newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .eventListener(EventListener.NONE)
            .build()

    /**
     * The stream's mime type from its URL, else (extension-less URLs, D7) from one GET: an HLS or
     * DASH Content-Type, else the first [SNIFF_BYTES] (`#EXTM3U`, MPD), else the redirect target's
     * extension. Null means progressive (TsExtractor and friends sniff the bytes themselves); a
     * failed sniff is null too, and the player's own load then reports the real error.
     */
    suspend fun resolveMimeType(request: LiveRequest, client: OkHttpClient): String? =
        PlayerMediaSourceFactory.inferMimeType(request.url, null) ?: sniffMimeType(request, client)

    private suspend fun sniffMimeType(request: LiveRequest, client: OkHttpClient): String? {
        val httpRequest = try {
            Request.Builder().url(request.url).apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                PlayerMediaSourceFactory.defaultUserAgentUnlessSet(request.headers)?.let { header("User-Agent", it) }
            }.build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val call = client.newCall(httpRequest)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val mime = try {
                        response.use(::mimeFromResponse)
                    } catch (_: IOException) {
                        null
                    }
                    continuation.resume(mime)
                }
            })
        }
    }

    private fun mimeFromResponse(response: Response): String? {
        if (!response.isSuccessful) return null
        adaptive(PlayerMediaSourceFactory.normalizeMimeType(response.header("Content-Type")))?.let { return it }
        // peekBody waits for SNIFF_BYTES or the end of the body, so a short first read cannot hide them.
        val head = response.peekBody(SNIFF_BYTES).string()
        PlayerMediaSourceFactory.sniffManifestMimeType(head)?.let { return it }
        return adaptive(PlayerMediaSourceFactory.inferMimeType(response.request.url.toString(), null))
    }

    /** Only the adaptive answers matter here: anything else plays as progressive anyway. */
    private fun adaptive(mime: String?): String? =
        mime?.takeIf { it == MimeTypes.APPLICATION_M3U8 || it == MimeTypes.APPLICATION_MPD }

    /** HLS and DASH by [mimeType]; anything else progressive. [mediaId] is the channel key. */
    fun create(request: LiveRequest, mimeType: String?, client: OkHttpClient, mediaId: String): MediaSource {
        val dataSourceFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(request.headers)
            setUserAgent(PlayerMediaSourceFactory.defaultUserAgentUnlessSet(request.headers))
        }
        val mediaItem = MediaItem.Builder()
            .setUri(request.url)
            .setMediaId(mediaId)
            .apply { mimeType?.let(::setMimeType) }
            .build()
        return when (mimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = false))
                .createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = false))
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
                .setLoadErrorHandlingPolicy(LiveLoadErrorHandlingPolicy(progressive = true))
                .createMediaSource(mediaItem)
        }
    }
}
