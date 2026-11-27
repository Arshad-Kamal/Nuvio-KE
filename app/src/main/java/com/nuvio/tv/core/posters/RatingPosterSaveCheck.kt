package com.nuvio.tv.core.posters

import android.graphics.BitmapFactory
import com.nuvio.tv.core.di.Image
import com.nuvio.tv.core.network.AddonHostGuard
import com.nuvio.tv.core.network.addonGuardKey
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** How a phone send ended (Spec 114 P0-7). Nothing here holds the template or its key: host and code only. */
sealed interface RatingPosterCheck {
    /** Saved for the profile; [host] is [RatingPosterTemplate.displayHost]. */
    data class Saved(val host: String, val isHttp: Boolean) : RatingPosterCheck

    /** Refused before any request: the template's rules (P0-5, P0-9). */
    data class Refused(val reason: RatingPosterTemplate.Refusal) : RatingPosterCheck

    /** Valid, but no id Nuvio can test with (tvdb or anime ids only): D4. */
    data object Untestable : RatingPosterCheck

    /** 401/403 from the service: the key. */
    data class KeyRejected(val host: String, val code: Int) : RatingPosterCheck

    /** 404 from the service: the address. */
    data class NotFound(val host: String) : RatingPosterCheck

    /** Any other answer, a non-image, a failed decode, a network error or the 10 s limit. */
    data class NoPoster(val host: String) : RatingPosterCheck

    /** The panel closed, or Turn off replaced its page, before the poster came back: nothing saved. */
    data object Closed : RatingPosterCheck
}

/**
 * Spec 114 P0-7, the save check: resolve the template for Big Buck Bunny, load that one poster with a plain
 * OkHttp GET on a copy of the `@Image` client without [RatingPosterHostGate] (same pool and dispatcher), so a
 * pause or open breaker left by an old key never answers it, and save only on a 2xx `image/` body that
 * decodes (bounds only). On success the rating-poster guard's breaker for that host closes and the failure
 * memory resets, so cards resume at once. Errors come from the HTTP code alone, never a
 * throwable's text (P0-6).
 */
@Singleton
class RatingPosterSaveCheck internal constructor(
    client: OkHttpClient,
    private val guard: AddonHostGuard,
    private val save: suspend (profileId: Int, template: RatingPosterTemplate) -> Unit,
    private val decodesAsImage: (InputStream) -> Boolean = ::decodesBounds,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val failures: RatingPosterFailureMemory = ratingPosterFailures,
) {
    @Inject
    internal constructor(
        @Image imageClient: OkHttpClient,
        @Named(RATING_POSTERS_GUARD) guard: AddonHostGuard,
        settings: RatingPosterSettings,
    ) : this(imageClient, guard, settings::save)

    /** The `@Image` client minus the host gate; newBuilder shares its pool and dispatcher. */
    private val client: OkHttpClient = client.newBuilder()
        .apply { interceptors().removeAll { it is RatingPosterHostGate } }
        .build()

    /**
     * Checks [input] and saves it for [profileId] (the profile captured when the panel opened). [stillWanted]
     * is asked once the poster came back: false (the panel closed) saves nothing.
     */
    suspend fun checkAndSave(input: String, profileId: Int, stillWanted: () -> Boolean): RatingPosterCheck {
        val template = when (val parsed = RatingPosterTemplate.parse(input)) {
            is RatingPosterTemplate.Parsed.Refused -> return RatingPosterCheck.Refused(parsed.reason)
            is RatingPosterTemplate.Parsed.Valid -> parsed.template
        }
        val url = RatingPosterResolver.resolve(template, PROBE_IDS, PosterContentType.MOVIE)
            ?: return RatingPosterCheck.Untestable
        val host = template.displayHost
        val code = withTimeoutOrNull(timeoutMs) { load(url) } ?: return RatingPosterCheck.NoPoster(host)
        when (code) {
            LOADED -> Unit
            401, 403 -> return RatingPosterCheck.KeyRejected(host, code)
            404 -> return RatingPosterCheck.NotFound(host)
            else -> return RatingPosterCheck.NoPoster(host)
        }
        if (!stillWanted()) return RatingPosterCheck.Closed
        guard.onTrusted(addonGuardKey(template.host, template.port))
        failures.reset()
        save(profileId, template)
        return RatingPosterCheck.Saved(host, template.isHttp)
    }

    /** [LOADED], the HTTP code of a refusal, or [NO_IMAGE]; cancelling the coroutine cancels the call. */
    private suspend fun load(url: String): Int {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "image/*").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resume(NO_IMAGE)

                override fun onResponse(call: Call, response: Response) {
                    val code = try {
                        response.use(::verdict)
                    } catch (_: IOException) {
                        NO_IMAGE
                    }
                    continuation.resume(code)
                }
            })
        }
    }

    private fun verdict(response: Response): Int {
        if (!response.isSuccessful) return response.code
        val type = response.body.contentType()
        if (type?.type != "image") return NO_IMAGE
        return if (decodesAsImage(response.body.byteStream())) LOADED else NO_IMAGE
    }

    companion object {
        /** Shorter than the `@Image` client's 12 s call timeout (P0-7). */
        const val TIMEOUT_MS = 10_000L

        /** Big Buck Bunny: both RPDB and Posters+ serve it (verified 2026-11-14), and the panel shows it. */
        internal val PROBE_IDS = PosterContentIds.of(id = "tt1254207", imdbId = "tt1254207", tmdbId = "10378")

        private const val LOADED = 0
        private const val NO_IMAGE = -1
    }
}

/** True when the stream's header reads as an image with a size (no pixels decoded). */
private fun decodesBounds(stream: InputStream): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(stream, null, options)
    return options.outWidth > 0 && options.outHeight > 0
}
