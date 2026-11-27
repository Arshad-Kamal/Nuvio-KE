package com.nuvio.tv.data.simkl

import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One profile's Simkl daily allowance as Simkl last reported it (Spec 106 T6). [remaining] / [limit] come from the
 * reply headers, never from a local count (uncounted catalog calls would skew one). [pausedUntilEpochMs] is set when
 * Simkl refused a call because the day is spent; until then nothing is sent for that profile.
 */
data class SimklQuotaState(
    val limit: Int? = null,
    val remaining: Int? = null,
    val pausedUntilEpochMs: Long? = null
) {
    fun isPaused(nowEpochMs: Long): Boolean = pausedUntilEpochMs != null && nowEpochMs < pausedUntilEpochMs
}

/**
 * Per-profile quota, in memory: after a restart the first call learns it again (a refused call costs nothing). The
 * allowance belongs to the Simkl account and is shared with every app on it, so it can drop between our calls.
 */
class SimklQuota(private val nowEpochMs: () -> Long = System::currentTimeMillis) {
    private val states = MutableStateFlow<Map<Int, SimklQuotaState>>(emptyMap())

    /** Every profile's state; the Trackers panel reads the active one. */
    val all: StateFlow<Map<Int, SimklQuotaState>> = states.asStateFlow()

    fun state(profileId: Int): SimklQuotaState = states.value[profileId] ?: SimklQuotaState()

    /** True while Simkl refuses [profileId]'s calls for the day. */
    fun isPaused(profileId: Int): Boolean = state(profileId).isPaused(nowEpochMs())

    /**
     * Throws [SimklQuotaExceededException] while [profileId] is paused. A pause past its reset is dropped together with
     * its "0 left" (the new day's count arrives with the next reply), in one atomic edit so a newer pause is kept.
     */
    fun checkAvailable(profileId: Int) {
        val now = nowEpochMs()
        val state = state(profileId)
        if (state.isPaused(now)) throw SimklQuotaExceededException(state.pausedUntilEpochMs!!)
        if (state.pausedUntilEpochMs == null) return
        edit(profileId) { current ->
            if (current.pausedUntilEpochMs != null && !current.isPaused(now)) current.copy(remaining = null, pausedUntilEpochMs = null)
            else current
        }
    }

    fun record(profileId: Int, response: SimklResponse) {
        val remaining = response.rateLimitRemaining
        val limit = response.rateLimitLimit
        if (remaining == null && limit == null) return
        edit(profileId) { it.copy(limit = limit ?: it.limit, remaining = remaining ?: it.remaining) }
    }

    /** Called with every refused call; only `429 user_limit_exceeded` pauses. */
    fun recordFailure(profileId: Int, failure: SimklApiException) {
        if (failure.status != 429 || failure.error != SIMKL_ERROR_USER_LIMIT) return
        val now = nowEpochMs()
        // Retry-After is "seconds to the daily reset": anything over a day is not that, so the reset is used instead.
        val until = failure.retryAfterSeconds?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }?.let { now + it * 1_000L }
            ?: nextSimklResetEpochMs(now)
        edit(profileId) { it.copy(limit = failure.rateLimitLimit ?: it.limit, remaining = 0, pausedUntilEpochMs = until) }
    }

    fun clear(profileId: Int) {
        states.update { it - profileId }
    }

    private fun edit(profileId: Int, transform: (SimklQuotaState) -> SimklQuotaState) {
        states.update { all -> all + (profileId to transform(all[profileId] ?: SimklQuotaState())) }
    }
}

private const val MAX_RETRY_AFTER_SECONDS = 25L * 60 * 60

/**
 * Simkl's daily reset: the next midnight in New York, the same instant for everyone (05:00 UTC in winter, 04:00 in
 * summer). `java.util` keeps it working on minSdk 24, which has no `java.time`.
 */
internal fun nextSimklResetEpochMs(nowEpochMs: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
    calendar.timeInMillis = nowEpochMs
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    calendar.add(Calendar.DAY_OF_MONTH, 1)
    return calendar.timeInMillis
}
