package com.nuvio.tv.core.util

import android.util.Log

/**
 * Time-to-first-frame stage trace (TTFF workstream, task T.1). Logs elapsed
 * marks under a single tag so one logcat filter yields the full press-to-
 * first-frame chain across the stream screen and the player:
 *
 *   adb logcat -s TTFF_STAGE
 *
 * begin() resets the clock and defines T0 for the run: the stream screen
 * fires it on entry (auto-select mode T0 = title play press, reached via
 * screen entry) and again on a manual stream selection (manual mode T0 =
 * the selection press). t0ElapsedMsOrNull() lets the resolved playback info
 * carry that T0 into the player's launchStartedAtMs, so the player side's
 * existing clickElapsedMs / clickToFirstFrameMs measure from the true press.
 * mirror() surfaces the player's STARTUP_STAGE / PLAYER_START_REQUEST /
 * PLAYBACK_STARTUP diagnostics lines to logcat under the same tag.
 * Logging only; no behavioural effect.
 */
object TtffTrace {
    private const val TAG = "TTFF_STAGE"
    private val trace = ElapsedTrace(TAG)

    fun begin(name: String) = trace.begin(name)

    fun mark(name: String) = trace.mark(name)

    /** T0 of the current run in SystemClock.elapsedRealtime() terms, if any. */
    fun t0ElapsedMsOrNull(): Long? = trace.t0ElapsedMsOrNull()

    /**
     * Mirror a pre-formatted diagnostics line to logcat under this tag. Diagnostics
     * lines can carry embedded newlines (Emby supplies multi-line stream names); a raw
     * newline splits the logcat entry and the continuation loses the tag, which strands
     * fragments like a bare "2160p" outside any TTFF_STAGE filter. Collapse any CR/LF
     * runs to a single space so one mirrored line is always one logcat line.
     */
    fun mirror(line: String) {
        Log.i(TAG, line.replace(NEWLINE_RUNS, " "))
    }

    private val NEWLINE_RUNS = Regex("[\r\n]+")
}
