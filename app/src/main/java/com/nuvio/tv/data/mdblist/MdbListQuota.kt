package com.nuvio.tv.data.mdblist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One profile's MDBList daily allowance as MDBList last reported it (Spec 105 A2/A3). [remaining] / [limit] /
 * [resetsAtEpochMs] come from the reply headers, never from a local count: the allowance belongs to the account and is
 * shared with every app on it. [pausedUntilEpochMs] is set when MDBList refused a call because the day is spent; until
 * then nothing is sent for that profile (D5). [plan] is the account's plan from `/user` (A2), kept with the quota so a
 * new key or a disconnect drops it too.
 */
data class MdbListQuotaState(
    val limit: Int? = null,
    val remaining: Int? = null,
    val resetsAtEpochMs: Long? = null,
    val pausedUntilEpochMs: Long? = null,
    val plan: String? = null
) {
    fun isPaused(nowEpochMs: Long): Boolean = pausedUntilEpochMs != null && nowEpochMs < pausedUntilEpochMs
}

/** Per-profile quota, in memory: after a restart the first call learns it again (a refused call costs nothing). */
class MdbListQuota(private val nowEpochMs: () -> Long = System::currentTimeMillis) {
    private val states = MutableStateFlow<Map<Int, MdbListQuotaState>>(emptyMap())

    /** Every profile's state; the Trackers panel reads the active one. */
    val all: StateFlow<Map<Int, MdbListQuotaState>> = states.asStateFlow()

    fun state(profileId: Int): MdbListQuotaState = states.value[profileId] ?: MdbListQuotaState()

    /**
     * Throws [MdbListQuotaExceededException] while [profileId] is paused. A pause past its reset is dropped together
     * with its "0 left" (the new day's count arrives with the next reply), in one atomic edit so a newer pause is kept.
     */
    fun checkAvailable(profileId: Int) {
        val now = nowEpochMs()
        val state = state(profileId)
        if (state.isPaused(now)) throw MdbListQuotaExceededException(state.pausedUntilEpochMs!!)
        if (state.pausedUntilEpochMs == null) return
        edit(profileId) { current ->
            if (current.pausedUntilEpochMs != null && !current.isPaused(now)) current.copy(remaining = null, pausedUntilEpochMs = null)
            else current
        }
    }

    fun record(profileId: Int, response: MdbListResponse) {
        val limit = response.rateLimitLimit
        val remaining = response.rateLimitRemaining
        val resetsAt = response.rateLimitResetEpochSeconds?.let { plausibleReset(it * 1_000L) }
        if (limit == null && remaining == null && resetsAt == null) return
        edit(profileId) {
            it.copy(limit = limit ?: it.limit, remaining = remaining ?: it.remaining, resetsAtEpochMs = resetsAt ?: it.resetsAtEpochMs)
        }
    }

    /** Called with every refused call; only the daily `429` pauses (the 5-minute one is the client's short wait). */
    fun recordFailure(profileId: Int, failure: MdbListApiException) {
        if (!failure.dailyLimit) return
        val now = nowEpochMs()
        val until = failure.rateLimitResetEpochSeconds?.let { plausibleReset(it * 1_000L) } ?: nextMdbListResetEpochMs(now)
        edit(profileId) {
            it.copy(limit = failure.rateLimitLimit ?: it.limit, remaining = 0, resetsAtEpochMs = until, pausedUntilEpochMs = until)
        }
    }

    /** A blank or missing plan changes nothing. */
    fun recordPlan(profileId: Int, plan: String?) {
        val name = plan?.trim()?.takeIf(String::isNotEmpty) ?: return
        edit(profileId) { it.copy(plan = name) }
    }

    fun clear(profileId: Int) {
        states.update { it - profileId }
    }

    /** A reset more than a day away (or already past) is not MDBList's daily reset: the computed one is used instead. */
    private fun plausibleReset(epochMs: Long): Long? {
        val now = nowEpochMs()
        return epochMs.takeIf { it > now && it - now <= MAX_RESET_AHEAD_MS }
    }

    private fun edit(profileId: Int, transform: (MdbListQuotaState) -> MdbListQuotaState) {
        states.update { all -> all + (profileId to transform(all[profileId] ?: MdbListQuotaState())) }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1_000
private const val MAX_RESET_AHEAD_MS = DAY_MS + 60L * 60 * 1_000

/** MDBList's daily reset: the next 00:00 UTC (its API docs, read 2026-11-16). */
internal fun nextMdbListResetEpochMs(nowEpochMs: Long): Long = (Math.floorDiv(nowEpochMs, DAY_MS) + 1) * DAY_MS
