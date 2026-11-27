package com.nuvio.tv.core.posters

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import com.nuvio.tv.ui.components.ExpiringUrlBlacklist
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * Spec 114 P0-3, the Coil half: the addon's own poster for a card showing a rating poster. Carried on the
 * request in [RatingPosterFallback.KEY] (an extra, not part of the memory-cache key); the addon URL holds no key.
 */
internal class RatingPosterFallback(
    val url: String,
    val memoryCacheKey: String,
    /** The template the rating URL came from ([RatingPosterTemplate.fingerprint]). */
    val fingerprint: String,
) {
    override fun toString(): String = "RatingPosterFallback"

    companion object {
        val KEY = Extras.Key<RatingPosterFallback?>(default = null)
    }
}

/** How a rating-poster load failed, for [RatingPosterFailureMemory]. */
internal enum class RatingPosterFailure {
    /** 400/404/410, or a 2xx that did not decode: this title's URL, for hours. */
    TERMINAL,

    /** IO, timeout, 5xx, any other answer or error, the host gate's synthetic 429/503: retried after minutes. */
    TRANSIENT,

    /** 401: the key is revoked or wrong. */
    UNAUTHORIZED,

    /** 403: one is often a CDN or per-title answer; two URLs in a row mean the key. */
    FORBIDDEN,
}

internal fun ratingPosterFailureOf(throwable: Throwable): RatingPosterFailure = when {
    throwable is HttpException -> when (throwable.response.code) {
        401 -> RatingPosterFailure.UNAUTHORIZED
        403 -> RatingPosterFailure.FORBIDDEN
        400, 404, 410 -> RatingPosterFailure.TERMINAL
        else -> RatingPosterFailure.TRANSIENT
    }
    // Bytes that are not an image: Coil 3.3.0 decodes through ImageDecoder on API 29+ (the Shield), whose
    // DecodeException is an IOException, and through BitmapFactory below, which throws IllegalStateException.
    throwable.isImageDecoderFailure() || throwable is IllegalStateException -> RatingPosterFailure.TERMINAL
    else -> RatingPosterFailure.TRANSIENT
}

/** By name, so it runs below API 28 too, where `ImageDecoder` does not exist. */
private fun Throwable.isImageDecoderFailure(): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.name == IMAGE_DECODER_EXCEPTION }

private const val IMAGE_DECODER_EXCEPTION = "android.graphics.ImageDecoder\$DecodeException"

/**
 * Which rating-poster URLs not to ask for again (P0-3): two private blacklists (not Continue Watching's
 * `brokenImageUrls`) and one "key rejected" record per template, which applies only while that template is
 * active, so a template or profile change needs no clear. In memory for the process. Thread-safe.
 */
internal class RatingPosterFailureMemory(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val terminal = ExpiringUrlBlacklist(TERMINAL_TTL_MS, MAX_URLS, nowMs)
    private val transient = ExpiringUrlBlacklist(TRANSIENT_TTL_MS, MAX_URLS, nowMs)

    private class KeyRejected(val fingerprint: String, val untilMs: Long)

    @Volatile
    private var keyRejected: KeyRejected? = null

    /** 403s of [forbiddenFingerprint]'s URLs in the last [FORBIDDEN_WINDOW_MS], by URL. */
    private val forbiddenAtMs = HashMap<String, Long>()
    private var forbiddenFingerprint: String? = null

    operator fun contains(url: String): Boolean = url in terminal || url in transient

    fun isKeyRejected(fingerprint: String): Boolean {
        val rejected = keyRejected ?: return false
        return rejected.fingerprint == fingerprint && nowMs() < rejected.untilMs
    }

    /**
     * [url] of template [fingerprint] failed with [failure]. A key verdict counts only for the active template
     * ([activeFingerprint]): a late answer for a template the user already replaced says nothing about the new one.
     */
    @Synchronized
    fun record(url: String, fingerprint: String, failure: RatingPosterFailure, activeFingerprint: String?) {
        when (failure) {
            RatingPosterFailure.TERMINAL -> terminal.add(url)
            RatingPosterFailure.TRANSIENT -> transient.add(url)
            RatingPosterFailure.UNAUTHORIZED ->
                if (fingerprint == activeFingerprint) rejectKey(fingerprint) else terminal.add(url)
            RatingPosterFailure.FORBIDDEN -> {
                terminal.add(url)
                if (fingerprint == activeFingerprint && forbiddenFromTwoUrls(url, fingerprint)) rejectKey(fingerprint)
            }
        }
    }

    /**
     * The save check just loaded a poster through a newly saved template (P0-7): every verdict so far (the
     * key record, the 403 window, per-URL entries a revoked key or a dead host left) is forgotten, so cards
     * try again at once.
     */
    @Synchronized
    fun reset() {
        keyRejected = null
        forbiddenAtMs.clear()
        forbiddenFingerprint = null
        terminal.clear()
        transient.clear()
    }

    private fun rejectKey(fingerprint: String) {
        keyRejected = KeyRejected(fingerprint, nowMs() + TERMINAL_TTL_MS)
    }

    @Synchronized
    private fun forbiddenFromTwoUrls(url: String, fingerprint: String): Boolean {
        val now = nowMs()
        if (forbiddenFingerprint != fingerprint) {
            forbiddenAtMs.clear()
            forbiddenFingerprint = fingerprint
        }
        forbiddenAtMs.values.removeIf { now - it > FORBIDDEN_WINDOW_MS }
        forbiddenAtMs[url] = now
        return forbiddenAtMs.size >= 2
    }

    companion object {
        val TERMINAL_TTL_MS = TimeUnit.HOURS.toMillis(6)
        val TRANSIENT_TTL_MS = TimeUnit.MINUTES.toMillis(2)
        val FORBIDDEN_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)
        private const val MAX_URLS = 512
    }
}

/** The process's one failure memory: the card spec reads it, [RatingPosterFallbackInterceptor] writes it. */
internal val ratingPosterFailures = RatingPosterFailureMemory()

/**
 * Spec 114 P0-3: a request carrying [RatingPosterFallback.KEY] that fails (or whose URL is already known bad)
 * is retried once with the addon's own poster under the addon's cache key, extra stripped. Runs before Coil's
 * memory cache, so a cached rating poster is still served. Cancellation propagates, never a fallback. Logs
 * nothing: the rating URL holds the user's key (P0-6).
 */
internal class RatingPosterFallbackInterceptor(
    private val failures: RatingPosterFailureMemory = ratingPosterFailures,
    private val activeFingerprint: () -> String? = { RatingPosters.activeTemplate?.fingerprint },
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val fallback = request.extras[RatingPosterFallback.KEY] ?: return chain.proceed()
        val ratingUrl = request.data as? String
        if (ratingUrl == null || ratingUrl in failures || failures.isKeyRejected(fallback.fingerprint)) {
            return chain.withRequest(request.toFallback(fallback)).proceed()
        }
        val result = chain.proceed()
        if (result !is ErrorResult) return result
        // A card that left the screen mid-load cancels its OkHttp call, which Coil reports as an IOException
        // error, not a cancellation: no verdict on the URL then (and the retry below never starts).
        currentCoroutineContext().ensureActive()
        failures.record(ratingUrl, fallback.fingerprint, ratingPosterFailureOf(result.throwable), activeFingerprint())
        return chain.withRequest(request.toFallback(fallback)).proceed()
    }

    private fun ImageRequest.toFallback(fallback: RatingPosterFallback): ImageRequest =
        newBuilder()
            .data(fallback.url)
            .memoryCacheKey(fallback.memoryCacheKey)
            .apply { extras[RatingPosterFallback.KEY] = null }
            .build()
}
