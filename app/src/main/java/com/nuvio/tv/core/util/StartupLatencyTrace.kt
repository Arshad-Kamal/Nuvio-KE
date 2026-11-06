package com.nuvio.tv.core.util

/**
 * Lightweight startup-latency trace for task 2.13 (profile-select to main UI).
 * Logs elapsed-time marks under a single tag so one logcat filter yields the
 * full attribution chain:
 *
 *   adb logcat -s NUVIO_STARTUP_TRACE
 *
 * begin() resets the clock; mark() logs the total since begin and the step
 * since the previous mark. Marks fired outside a profile-switch run (e.g. a
 * routine foreground settings pull) log against the stale start time and are
 * recognisable by their large t values. Logging only; no behavioural effect.
 */
object StartupLatencyTrace {
    private val trace = ElapsedTrace("NUVIO_STARTUP_TRACE")

    fun begin(name: String) = trace.begin(name)

    fun mark(name: String) = trace.mark(name)
}
