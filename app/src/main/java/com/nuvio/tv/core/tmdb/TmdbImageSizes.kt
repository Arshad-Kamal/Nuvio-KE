package com.nuvio.tv.core.tmdb

import android.content.res.Resources

/**
 * Resolution-aware TMDB source-size selection.
 *
 * Backdrops render full-bleed, so the source ceiling should track the UI framebuffer:
 * a fixed w1280 is a permanent 3x upscale on devices rendering the UI at 4K, while
 * remaining well matched on 1080p-rendering boxes. TMDB offers no official backdrop
 * size between w1280 and original, so the step is binary. Threshold 1600 px keeps
 * 1080p UIs on w1280 (no change in transfer cost) and upgrades only above that.
 */
object TmdbImageSizes {

    val backdrop: String by lazy {
        // Resources.getSystem() is null on the JVM (local unit tests) and can be null
        // very early in process start — fall back to the historical w1280 there.
        val longestSide = runCatching { Resources.getSystem().displayMetrics }
            .getOrNull()
            ?.let { maxOf(it.widthPixels, it.heightPixels) }
            ?: 0
        if (longestSide > 1600) "original" else "w1280"
    }
}

/** TMDB image URL for [path] at [size] (e.g. "w500", [TmdbImageSizes.backdrop]); null for a missing/blank path. */
internal fun tmdbImageUrl(path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}
