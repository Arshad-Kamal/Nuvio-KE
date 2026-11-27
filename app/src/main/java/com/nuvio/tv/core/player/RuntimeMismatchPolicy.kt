package com.nuvio.tv.core.player

import kotlin.math.abs

/**
 * Spec 35: warn when the file playing runs noticeably longer or shorter than the title's
 * metadata runtime, the tell that an addon served a different title under the same name
 * (2026-10-18: the 1997 Franklin cartoon, 23 min, got a 64 min file from the 2024 drama).
 *
 * A warning, not a reject: [PlaceholderStreamPolicy] keeps its hard limits and is checked
 * first, so a rejected stream never also warns. Pure, like that policy.
 *
 * Callers pass the runtime from the player's own metadata, never the route's runtime: the
 * Continue Watching route carries the previous playback's duration, which a wrong file would
 * have written itself.
 */
object RuntimeMismatchPolicy {

    /** Off by more than this fraction of the runtime, either side, warns (Arshad 2026-10-18). */
    const val TOLERANCE_RATIO = 0.10

    /**
     * Spec 142 (F13): the fraction for a TV episode. Listed episode runtimes are coarse (a flat 25 m
     * for every Beverly Hillbillies episode, whose correct files run 22.3 min, -11%; Twilight Zone
     * files run 28.7 min against 25 m, +15%; a 22 min sitcom listed at 30 m is -27%), while a
     * different show is off by about half or double (a 2024 Dora reboot file at -52%, Franklin at
     * +179%). Either side (Arshad 2026-11-21, Spec 142 D3); the 60 s floor never binds here.
     */
    const val EPISODE_TOLERANCE_RATIO = 0.35

    /** Metadata runtimes are whole minutes, so short titles get at least this much slack. */
    const val MIN_TOLERANCE_MS = 60_000L

    /** Below this runtime (clips, extras) the metadata is too coarse to judge. */
    const val MIN_JUDGED_RUNTIME_MS = 5L * 60_000L

    /**
     * The warning rewinds to 0:00, so it only fires near the start: when the metadata runtime
     * arrives later than this into playback, the stream is not judged.
     */
    const val MAX_WARN_POSITION_MS = 60_000L

    data class Mismatch(val durationMs: Long, val expectedRuntimeMs: Long) {
        val longer: Boolean get() = durationMs > expectedRuntimeMs
    }

    /**
     * @param durationMs decoded duration of the file, or null before it is known.
     * @param expectedRuntimeMs metadata runtime of the title, or null when unknown.
     * @param resumed true when playback started from a saved position (Continue Watching or
     *   a Resume press): the viewer already saw this file, so no warning.
     * @param episode true for a TV episode, judged with [EPISODE_TOLERANCE_RATIO]; a film keeps
     *   [TOLERANCE_RATIO].
     * @return the mismatch to warn about, or null.
     */
    fun evaluate(durationMs: Long?, expectedRuntimeMs: Long?, resumed: Boolean, episode: Boolean): Mismatch? {
        if (resumed) return null
        val expected = expectedRuntimeMs?.takeIf { it >= MIN_JUDGED_RUNTIME_MS } ?: return null
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val ratio = if (episode) EPISODE_TOLERANCE_RATIO else TOLERANCE_RATIO
        val toleranceMs = maxOf((expected * ratio).toLong(), MIN_TOLERANCE_MS)
        return if (abs(duration - expected) > toleranceMs) Mismatch(duration, expected) else null
    }
}
