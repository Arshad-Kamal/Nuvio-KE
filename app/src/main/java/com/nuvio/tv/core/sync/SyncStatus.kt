package com.nuvio.tv.core.sync

/**
 * What the Account pane's sync line shows. [lastOkAtMs] is wall clock, stored per profile in
 * the local startup_sync_state (wiped on sign-out); null = never synced on this TV.
 * [pending] = "changes waiting" for the active profile ([pendingChanges]); hidden at 0.
 */
data class SyncStatus(
    val running: Boolean = false,
    val lastOkAtMs: Long? = null,
    val pending: Int = 0
)

/**
 * Steps a startup pull must complete to stamp "Synced N ago"; its other steps (settings blob,
 * collections, home catalog) keep local data on failure. The activity pull stamps on its own
 * watch-state + library steps (Spec 31 defaults).
 */
internal enum class CorePullStep { PROFILES, LIBRARY, ADDONS, WATCHED_ITEMS, WATCH_PROGRESS }

/** A startup pull counts as synced only when every core step reported success; a missing step is a failure. */
internal fun pullSucceeded(outcomes: Map<CorePullStep, Boolean>): Boolean =
    CorePullStep.entries.all { outcomes[it] == true }

sealed interface SyncAge {
    data object Never : SyncAge
    data object JustNow : SyncAge
    data class Minutes(val count: Int) : SyncAge
    data class Hours(val count: Int) : SyncAge
    data class Days(val count: Int) : SyncAge
}

/** Age bucket for the sync line, rounded down. A last-ok time ahead of [nowMs] (clock change) reads as just now. */
fun syncAge(nowMs: Long, lastOkAtMs: Long?): SyncAge {
    if (lastOkAtMs == null || lastOkAtMs <= 0L) return SyncAge.Never
    val elapsedMs = nowMs - lastOkAtMs
    return when {
        elapsedMs < MINUTE_MS -> SyncAge.JustNow
        elapsedMs < HOUR_MS -> SyncAge.Minutes((elapsedMs / MINUTE_MS).toInt())
        elapsedMs < DAY_MS -> SyncAge.Hours((elapsedMs / HOUR_MS).toInt())
        else -> SyncAge.Days((elapsedMs / DAY_MS).toInt())
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
