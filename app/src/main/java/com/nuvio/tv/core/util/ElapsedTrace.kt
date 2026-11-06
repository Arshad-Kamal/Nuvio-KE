package com.nuvio.tv.core.util

import android.os.SystemClock
import android.util.Log

/**
 * Shared begin/mark clock for the logcat stage traces (TtffTrace,
 * StartupLatencyTrace). begin() resets T0; mark() logs the total since T0 and
 * the step since the previous mark, and acts as begin() when no run has started.
 */
internal class ElapsedTrace(
    private val tag: String,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(tag, it) },
) {
    @Volatile private var t0ElapsedMs: Long = 0L
    @Volatile private var lastMarkElapsedMs: Long = 0L

    fun begin(name: String) {
        val at = now()
        t0ElapsedMs = at
        lastMarkElapsedMs = at
        log("[$name] t=0 ms (trace begin)")
    }

    fun mark(name: String) {
        val at = now()
        if (t0ElapsedMs == 0L) {
            begin(name)
            return
        }
        log("[$name] t=+${at - t0ElapsedMs} ms (step +${at - lastMarkElapsedMs} ms)")
        lastMarkElapsedMs = at
    }

    /** T0 of the current run in SystemClock.elapsedRealtime() terms, if any. */
    fun t0ElapsedMsOrNull(): Long? = t0ElapsedMs.takeIf { it > 0L }
}
