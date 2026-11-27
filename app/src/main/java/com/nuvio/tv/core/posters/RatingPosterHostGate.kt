package com.nuvio.tv.core.posters

import okhttp3.Interceptor
import okhttp3.Response

/** `@Named` qualifier of the rating posters' own `AddonHostGuard` (NetworkModule). */
internal const val RATING_POSTERS_GUARD = "ratingPosters"

/**
 * Spec 114 P0-3 (D7), the network half: the `@Image` client's first interceptor. A request to the active
 * template's host:port goes through [guarded] (Spec 70's `AddonHostGuardInterceptor` over the rating posters'
 * own `AddonHostGuard`); every other image request just proceeds. Gating is per host:port, so other images
 * from that host share the gate.
 */
internal class RatingPosterHostGate(
    private val guarded: Interceptor,
    private val activeTemplate: () -> RatingPosterTemplate? = { RatingPosters.activeTemplate },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val template = activeTemplate()
        val url = chain.request().url
        return if (template != null && url.host == template.host && url.port == template.port) {
            guarded.intercept(chain)
        } else {
            chain.proceed(chain.request())
        }
    }
}
