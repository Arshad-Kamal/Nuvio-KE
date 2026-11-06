package com.nuvio.tv.core.player

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.FrameRateUtils.FrameRateDetection
import com.nuvio.tv.core.player.FrameRateUtils.snapProbeRateByFrameDuration
import com.nuvio.tv.core.player.FrameRateUtils.snapToStandardRate
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * AFR NextLib / MediaExtractor frame-rate probes and source classification (scheme, MKV/MP4,
 * live, resolve-proxy URLs). Split out of [FrameRateUtils] (DRDYK L3b-57, move-only).
 */
object AfrMediaProbe {

    // Same tag as FrameRateUtils: `logcat -s FrameRateUtils` keeps every AFR line.
    private const val TAG = "FrameRateUtils"

    private const val MIN_VALID_VIDEO_FPS = 10f
    private const val MAX_VALID_VIDEO_FPS = 120f
    private val NEXTLIB_HTTP_SCHEMES = setOf("http", "https")
    private val LIVE_STREAM_EXTENSIONS = listOf(".mpd", ".ism/manifest")
    private const val MKV_EXTENSION = ".mkv"
    /**
     * Headers used for NextLib bypass decision and NextLib probe invocation.
     * Range is stripped because it is never meaningful for frame-rate probing.
     */
    internal fun streamHeadersForAfrProbe(headers: Map<String, String>): Map<String, String> {
        return headers.filterKeys { !it.equals("Range", ignoreCase = true) }
    }

    /**
     * Headers for MediaExtractor fallback. Adds Connection: close so the probe
     * connection is torn down promptly after sampling.
     */
    internal fun extractorProbeHeaders(headers: Map<String, String>): Map<String, String> {
        return streamHeadersForAfrProbe(headers).toMutableMap().apply {
            put("Connection", "close")
        }
    }

    /**
     * True when headers contain values NextLib cannot forward (auth tokens, cookies, etc.).
     * Synthetic / always-present headers (Range, User-Agent, Connection) are ignored so public
     * and debrid streams that only inject UA still get NextLib probing.
     */
    internal fun hasNextLibBlockingHeaders(headers: Map<String, String>): Boolean {
        return headers.any { (k, v) ->
            v.isNotBlank() &&
                !k.equals("Range", ignoreCase = true) &&
                !k.equals("User-Agent", ignoreCase = true) &&
                !k.equals("Connection", ignoreCase = true)
        }
    }

    private fun isValidVideoFrameRate(frameRate: Float): Boolean {
        return frameRate.isFinite() && frameRate in MIN_VALID_VIDEO_FPS..MAX_VALID_VIDEO_FPS
    }

    fun detectFrameRateFromNextLib(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? {
        return detectFrameRateWithNextLib(context, sourceUrl, headers, mimeType, filename)
    }

    fun detectFrameRateFromExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap()
    ): FrameRateDetection? {
        if (isResolveProxyUrl(sourceUrl)) {
            val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank()) {
                detectFrameRateWithExtractor(context, embeddedResolveUrl, headers)?.let { return it }
            }
        }
        return detectFrameRateWithExtractor(context, sourceUrl, headers)
    }

    internal fun isMp4Source(
        sourceUrl: String,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (mimeType?.equals(MimeTypes.VIDEO_MP4, ignoreCase = true) == true ||
            mimeType?.equals("video/iso.segment", ignoreCase = true) == true) return true
        if (filename != null) {
            val lower = filename.lowercase(Locale.ROOT)
            if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) return true
        }
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return normalized.endsWith(".mp4") || normalized.endsWith(".m4v") || normalized.endsWith(".mov")
    }

    internal fun detectFrameRateFromLocalFile(
        context: Context,
        file: java.io.File,
        allowTimestampSampling: Boolean = true
    ): FrameRateDetection? {
        detectFrameRateWithNextLibFromLocalFile(context, file)?.let { return it }

        return detectFrameRateWithExtractor(
            context = context,
            sourceUrl = file.absolutePath,
            headers = emptyMap(),
            allowTimestampSampling = allowTimestampSampling
        )
    }

    internal fun detectFrameRateWithNextLibFromLocalFile(
        context: Context,
        file: java.io.File
    ): FrameRateDetection? {
        try {
            val uri = Uri.fromFile(file)
            val mediaInfo = MediaInfoBuilder().from(context = context, uri = uri).build()
            if (mediaInfo != null) {
                try {
                    val video = mediaInfo.videoStream
                    if (video != null) {
                        val measured = video.frameRate.toFloat()
                        if (isValidVideoFrameRate(measured)) {
                            return FrameRateDetection(
                                raw = measured,
                                snapped = snapToStandardRate(measured),
                                videoWidth = video.frameWidth.takeIf { it > 0 },
                                videoHeight = video.frameHeight.takeIf { it > 0 }
                            )
                        }
                    }
                } finally {
                    runCatching { mediaInfo.release() }
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Local NextLib probe failed: ${e.message}")
        }
        return null
    }

    private fun detectFrameRateWithNextLib(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>,
        mimeType: String? = null,
        filename: String? = null
    ): FrameRateDetection? {
        if (!shouldUseNextLibProbe(sourceUrl, headers, mimeType, filename)) return null

        val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
        val shouldPreferEmbedded = isResolveProxyUrl(sourceUrl)
        val candidates = buildList {
            if (shouldPreferEmbedded && !embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
                add(sourceUrl)
                return@buildList
            }

            add(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
            }
        }

        candidates.forEach { candidateUrl ->
            var mediaInfo: MediaInfo? = null
            try {
                val uri = Uri.parse(candidateUrl)
                val builder = MediaInfoBuilder().from(context = context, uri = uri)
                mediaInfo = builder.build() ?: return@forEach

                val video = mediaInfo.videoStream ?: return@forEach
                val measured = video.frameRate.toFloat()
                if (!isValidVideoFrameRate(measured)) return@forEach

                return FrameRateDetection(
                    raw = measured,
                    snapped = snapToStandardRate(measured),
                    videoWidth = video.frameWidth.takeIf { it > 0 },
                    videoHeight = video.frameHeight.takeIf { it > 0 }
                )
            } catch (e: Throwable) {
                Log.w(TAG, "NextLib frame rate probe failed: ${e.message}")
            } finally {
                runCatching { mediaInfo?.release() }
            }
        }
        return null
    }

    /** Hard deadline after which a stuck extractor probe is force-released. */
    private const val EXTRACTOR_PROBE_HARD_DEADLINE_MS = 6_000L

    private fun detectFrameRateWithExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>,
        allowTimestampSampling: Boolean = true
    ): FrameRateDetection? {
        val safeHeaders = headers
        val extractor = MediaExtractor()
        // AFR review F1: MediaExtractor.setDataSource() is a blocking native call
        // that cooperative withTimeoutOrNull cancellation cannot interrupt. On a
        // non-faststart MP4 (moov atom at the tail) it reads toward end-of-file
        // and can block indefinitely (runtime-confirmed >=109 s on 0.7.12-beta -
        // "AFR on hangs, AFR off starts cleanly"). Releasing the extractor from a
        // watchdog thread aborts the native open, making the probe budget real.
        val probeFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread({
            try {
                Thread.sleep(EXTRACTOR_PROBE_HARD_DEADLINE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!probeFinished.get()) {
                Log.w(TAG, "AFR extractor probe exceeded ${EXTRACTOR_PROBE_HARD_DEADLINE_MS} ms; force-releasing extractor")
                try {
                    extractor.release()
                } catch (_: Throwable) {
                }
            }
        }, "afr-probe-watchdog").apply {
            isDaemon = true
            start()
        }
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(sourceUrl, safeHeaders)
                else -> extractor.setDataSource(context, uri, safeHeaders)
            }

            var videoTrackIndex = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0) return null

            val detectedVideoWidth = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_WIDTH) }
                ?.runCatching { getInteger(android.media.MediaFormat.KEY_WIDTH) }
                ?.getOrNull()
                ?.takeIf { it > 0 }
            val detectedVideoHeight = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_HEIGHT) }
                ?.runCatching { getInteger(android.media.MediaFormat.KEY_HEIGHT) }
                ?.getOrNull()
                ?.takeIf { it > 0 }

            val declaredFrameRate = videoFormat
                ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.runCatching { getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.getOrNull()
            if (declaredFrameRate != null && isValidVideoFrameRate(declaredFrameRate)) {
                return FrameRateDetection(
                    raw = declaredFrameRate,
                    snapped = snapToStandardRate(declaredFrameRate),
                    videoWidth = detectedVideoWidth,
                    videoHeight = detectedVideoHeight
                )
            }

            // Truncated OkHttp head probes must not enter the sample loop — it can block for seconds
            // on incomplete MKV/MP4 containers and exhaust the AFR preflight budget.
            if (!allowTimestampSampling) return null

            extractor.selectTrack(videoTrackIndex)
            val timestamps = ArrayList<Long>(400)
            val ignoreSamples = 3
            val targetSamples = 350 + ignoreSamples

            while (timestamps.size < targetSamples) {
                val ts = extractor.sampleTime
                if (ts < 0) break
                timestamps.add(ts)
                if (!extractor.advance()) break
            }

            val averageFrameDurationUs =
                FrameRateUtils.averageSampleDurationUs(
                    timestamps,
                    skipFirst = ignoreSamples,
                    skipLast = FrameRateUtils.TIMESTAMP_REORDER_MARGIN
                ) ?: return null

            val measured = 1_000_000f / averageFrameDurationUs
            if (!isValidVideoFrameRate(measured)) return null

            FrameRateDetection(
                raw = measured,
                snapped = snapProbeRateByFrameDuration(measured, averageFrameDurationUs),
                videoWidth = detectedVideoWidth,
                videoHeight = detectedVideoHeight
            )
        } catch (e: Exception) {
            Log.w(TAG, "Frame rate probe failed: ${e.message}")
            null
        } finally {
            probeFinished.set(true)
            watchdog.interrupt()
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Whether NextLib MediaInfo probe should run for this source.
     * Exposed as internal for regression tests (0.7.10 parity + header bypass).
     */
    internal fun shouldUseNextLibProbe(
        sourceUrl: String,
        headers: Map<String, String>,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (sourceUrl.isBlank()) return false
        if (isLiveStreamUrl(sourceUrl)) return false

        // Bypass NextLib when auth/custom headers are present: MediaInfoBuilder cannot forward them.
        if (hasNextLibBlockingHeaders(headers)) return false

        if (isMkvSource(sourceUrl, mimeType, filename)) return true

        val scheme = parseUriScheme(sourceUrl)
        return when (scheme) {
            in NEXTLIB_HTTP_SCHEMES -> true
            "file", "content" -> true
            null -> true
            else -> false
        }
    }

    internal fun isLiveStreamUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return LIVE_STREAM_EXTENSIONS.any { ext -> normalized.endsWith(ext) }
    }

    internal fun isMkvSource(
        sourceUrl: String,
        mimeType: String? = null,
        filename: String? = null
    ): Boolean {
        if (mimeType?.equals(MimeTypes.VIDEO_MATROSKA, ignoreCase = true) == true ||
            mimeType?.equals("video/mkv", ignoreCase = true) == true ||
            mimeType?.equals("video/x-matroska", ignoreCase = true) == true) return true
        if (filename?.endsWith(MKV_EXTENSION, ignoreCase = true) == true) return true
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return normalized.endsWith(MKV_EXTENSION)
    }

    /** Scheme extraction that tolerates missing/stub Android Uri in JVM unit tests. */
    internal fun parseUriScheme(sourceUrl: String): String? {
        val fromUri = try {
            Uri.parse(sourceUrl).scheme?.lowercase(Locale.ROOT)
        } catch (_: Throwable) {
            null
        }
        if (!fromUri.isNullOrBlank()) return fromUri
        val schemeEnd = sourceUrl.indexOf("://")
        if (schemeEnd <= 0) return null
        return sourceUrl.substring(0, schemeEnd).lowercase(Locale.ROOT)
    }

    internal fun isResolveProxyUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return "/resolve/" in normalized
    }

    internal fun extractEmbeddedResolveUrl(sourceUrl: String): String? {
        val marker = "/resolve/"
        val markerIndex = sourceUrl.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null

        val afterResolve = sourceUrl.substring(markerIndex + marker.length)
        val nestedEncoded = afterResolve.substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/', missingDelimiterValue = "")
        if (nestedEncoded.isBlank()) return null

        val decoded = runCatching {
            URLDecoder.decode(nestedEncoded, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null

        if (decoded.startsWith("http://", ignoreCase = true) ||
            decoded.startsWith("https://", ignoreCase = true)
        ) {
            return decoded
        }
        return null
    }
}
