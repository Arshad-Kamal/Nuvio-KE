package com.nuvio.tv.core.runtime

import android.content.Context
import android.util.Log
import coil3.imageLoader
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * M3 nightly self-trim, rescoped per derisk S5 (2026-08-31): the deployed
 * 04:00 adb reboot already resets resident memory every night — what survives
 * it is DISK state. When the app has been continuously backgrounded for
 * [IDLE_EXIT_DELAY_MS] and the 04:00-local trim window has passed (a missed
 * window catches up — the trim-day bucket flips at 04:00 and the trim runs at
 * most once per bucket):
 *
 * 1. Trim the Coil disk cache when it has grown past 3/4 of its cap
 *    (a bounded cache only ever trends toward its max; the trim resets it).
 * 2. Idle exit: when no external-player session is being tracked, exit the
 *    process so the next launch starts with a fresh heap — this covers
 *    skip-nights, where the reboot did not fire because media was playing.
 *
 * The idle delay makes brief hops (browser trailers, sign-in links, a quick
 * home press) NOT idle — the schedule is cancelled by the next foreground,
 * up to the exit itself: a foreground during the disk work skips the exit.
 * The bucket marker is committed synchronously when the trim actually runs,
 * never at schedule time, so a process death inside the delay keeps the
 * catch-up semantics intact. No-op while foreground by construction (trigger:
 * MainActivity onStop/onStart pair in the single-activity app).
 */
@Singleton
class NightlySelfTrim @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val externalPlaybackTracker: ExternalPlaybackTracker
) {
    private val schedule = IdleTrimSchedule(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        idleDelayMs = IDLE_EXIT_DELAY_MS,
        ioDispatcher = Dispatchers.IO,
        trimOnce = ::runTrimOnce,
        exit = { exitProcess(0) }
    )

    /** Call from the activity's onStop (= the app just went to the background). */
    fun onAppBackgrounded() = schedule.onBackgrounded()

    /** Call from the activity's onStart — a quick return is not idle. */
    fun onAppForegrounded() = schedule.onForegrounded()

    /** Runs at most once per trim-day bucket; returns whether to idle-exit afterwards. */
    private fun runTrimOnce(): Boolean {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bucket = trimDayBucket(Calendar.getInstance(), TRIM_HOUR)
        if (prefs.getLong(KEY_LAST_TRIM_DAY, Long.MIN_VALUE) == bucket) return false
        val trimmed = trimDiskCacheIfLarge()
        val trackingActive = externalPlaybackTracker.isTracking
        // Synchronous on purpose: the idle exit that follows terminates the
        // process, and an async apply() can lose this write — a lost marker
        // re-runs the trim (and the exit) on every background for the rest of the day.
        prefs.edit().putLong(KEY_LAST_TRIM_DAY, bucket).commit()
        Log.i(
            TAG,
            "nightly self-trim ran: diskCacheTrimmed=$trimmed, idleExitRequested=${!trackingActive}"
        )
        return !trackingActive
    }

    private fun trimDiskCacheIfLarge(): Boolean = runCatching {
        val diskCache = appContext.imageLoader.diskCache ?: return@runCatching false
        if (shouldTrimDiskCache(diskCache.size, diskCache.maxSize)) {
            diskCache.clear()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "NightlySelfTrim"
        const val TRIM_HOUR = 4
        const val PREFS_NAME = "nightly_self_trim"
        const val KEY_LAST_TRIM_DAY = "last_trim_day"
        const val IDLE_EXIT_DELAY_MS = 10L * 60 * 1000
    }
}

/**
 * Background -> idle delay -> trim (on [ioDispatcher]) -> optional exit, cancellable
 * by a foreground at every step. The job reference is held until the job finishes,
 * so a foreground during the blocking trim still cancels it; withContext rethrows
 * that cancellation when the trim returns, which skips the exit. All entry points
 * run on the [scope]'s (main) dispatcher.
 */
internal class IdleTrimSchedule(
    private val scope: CoroutineScope,
    private val idleDelayMs: Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val trimOnce: () -> Boolean,
    private val exit: () -> Unit
) {
    private var pending: Job? = null

    fun onBackgrounded() {
        if (pending != null) return
        pending = scope.launch {
            val self = coroutineContext.job
            try {
                delay(idleDelayMs)
                val exitRequested = withContext(ioDispatcher) { trimOnce() }
                if (exitRequested && isActive) exit()
            } finally {
                // A foreground + re-background during the trim installs a new job; keep it.
                if (pending === self) pending = null
            }
        }
    }

    fun onForegrounded() {
        pending?.cancel()
        pending = null
    }
}

/**
 * The trim-day bucket: the calendar day, computed as if the day flipped at
 * [trimHour] local. DST-safe (Calendar arithmetic, no raw hour math).
 */
internal fun trimDayBucket(
    now: Calendar,
    trimHour: Int = 4
): Long {
    val cal = (now.clone() as Calendar)
    if (cal.get(Calendar.HOUR_OF_DAY) < trimHour) {
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
}

/** Trim only once the cache has actually grown large — small caches are left alone. */
internal fun shouldTrimDiskCache(sizeBytes: Long, maxSizeBytes: Long): Boolean =
    maxSizeBytes > 0 && sizeBytes > maxSizeBytes * 3 / 4
