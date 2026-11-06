package com.nuvio.tv.core.profile

import android.os.SystemClock
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A server PIN check as the PIN screens act on it. */
sealed interface PinVerifyOutcome {
    data object Unlocked : PinVerifyOutcome
    data object Wrong : PinVerifyOutcome
    /** The server refused checks for [seconds] more (`retry_after_seconds`). */
    data class LockedOut(val seconds: Int) : PinVerifyOutcome
    /** Network or API failure: fail closed. */
    data object Failed : PinVerifyOutcome
}

/**
 * Per-profile PIN lockouts the server reported, kept for the process so a countdown survives
 * closing and reopening the PIN screen and is shared by the picker and the Settings gate.
 * Expiries are on [SystemClock.elapsedRealtime] (wall-clock changes cannot shorten one). Only
 * `retry_after_seconds` from a failed verify feeds it; the synced `pin_locked_until` is not used.
 */
@Singleton
class PinLockouts @Inject constructor() {
    private val _lockedUntil = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** Profile id -> lockout expiry in elapsedRealtime ms; expired entries may linger. */
    val lockedUntil: StateFlow<Map<Int, Long>> = _lockedUntil.asStateFlow()

    /** Maps a verify [result] to its outcome and records (or clears) the profile's lockout. */
    fun record(
        profileId: Int,
        result: Result<SupabaseProfilePinVerifyResult>,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): PinVerifyOutcome {
        val verify = result.getOrNull() ?: return PinVerifyOutcome.Failed
        return when {
            verify.unlocked -> {
                _lockedUntil.update { it - profileId }
                PinVerifyOutcome.Unlocked
            }
            verify.retryAfterSeconds > 0 -> {
                _lockedUntil.update { it + (profileId to nowMs + verify.retryAfterSeconds * 1000L) }
                PinVerifyOutcome.LockedOut(verify.retryAfterSeconds)
            }
            else -> PinVerifyOutcome.Wrong
        }
    }

    /** Whole seconds left on [profileId]'s lockout, rounded up; 0 when none. */
    fun remainingSeconds(profileId: Int, nowMs: Long = SystemClock.elapsedRealtime()): Int =
        secondsLeft(_lockedUntil.value[profileId], nowMs)

    companion object {
        /** Whole seconds from [nowMs] to an expiry [until] (a [lockedUntil] value), rounded up; 0 when none. */
        fun secondsLeft(until: Long?, nowMs: Long): Int {
            if (until == null || until <= nowMs) return 0
            return ((until - nowMs + 999) / 1000).toInt()
        }
    }
}
