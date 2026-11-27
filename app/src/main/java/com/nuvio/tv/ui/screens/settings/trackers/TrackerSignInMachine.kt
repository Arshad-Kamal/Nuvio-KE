package com.nuvio.tv.ui.screens.settings.trackers

import com.nuvio.tv.core.tracking.TrackingDeviceCode
import com.nuvio.tv.core.tracking.TrackingDevicePoll

/**
 * A tracker's device-code sign-in as a pure state machine (Spec 104 S3b, mockup B1-B5). The ViewModel runs the calls
 * and feeds their results back as [TrackerSignInEvent]s; nothing polls but a [TrackerSignInEffect.Poll] it returned,
 * one at a time, and only while the sign-in screen is open (the ViewModel's job). Every result names its [attempt];
 * one from an older attempt (a code replaced by New code / Try again) is ignored. Not Spec 31's `QrLoginMachine`:
 * that flow exchanges a code for a session in a second step; a device-code poll returns the tokens itself.
 */
internal sealed interface TrackerSignIn {
    val attempt: Int

    data class Starting(override val attempt: Int) : TrackerSignIn

    /** B1/B2: the code is shown and polled every [intervalSeconds]. */
    data class Waiting(
        override val attempt: Int,
        val code: TrackingDeviceCode,
        val intervalSeconds: Int,
        val failures: Int = 0
    ) : TrackerSignIn

    /** B3: the code ran out; polling has stopped. */
    data class Expired(override val attempt: Int, val code: TrackingDeviceCode) : TrackerSignIn

    /** B4: declined on the phone, or the service could not be reached. Polling has stopped. */
    data class Failed(override val attempt: Int, val reason: Reason, val code: TrackingDeviceCode?) : TrackerSignIn

    /** B5: approved; the provider has saved the account. */
    data class Connected(override val attempt: Int) : TrackerSignIn

    enum class Reason { DENIED, UNREACHABLE }
}

internal sealed interface TrackerSignInEvent {
    /** Ask for a new code (opening the screen, New code, Try again). */
    data object Restart : TrackerSignInEvent
    data class Started(val attempt: Int, val code: TrackingDeviceCode) : TrackerSignInEvent
    data class StartFailed(val attempt: Int) : TrackerSignInEvent
    data class Polled(val attempt: Int, val result: TrackingDevicePoll, val nowEpochMs: Long) : TrackerSignInEvent
    data class PollFailed(val attempt: Int, val nowEpochMs: Long) : TrackerSignInEvent
}

internal sealed interface TrackerSignInEffect {
    data class Start(val attempt: Int) : TrackerSignInEffect
    data class Poll(val attempt: Int, val code: TrackingDeviceCode, val delayMs: Long) : TrackerSignInEffect
}

/** No service is polled faster than this, whatever interval it gives. */
internal const val TRACKER_POLL_MIN_INTERVAL_S = 2

/** RFC 8628 3.5: each slow_down adds 5 seconds to the interval. */
internal const val TRACKER_POLL_SLOW_DOWN_S = 5

/** Poll failures in a row that are retried (each backs off); the next one shows "Couldn't reach". */
internal const val TRACKER_POLL_MAX_FAILURES = 3

internal const val TRACKER_POLL_BACKOFF_MAX_S = 30

/** [lastAttempt]: the previous sign-in's last attempt, so attempt numbers never repeat within a screen's ViewModel. */
internal fun initialTrackerSignIn(lastAttempt: Int = 0): TrackerSignIn = TrackerSignIn.Starting(attempt = lastAttempt)

internal fun reduceTrackerSignIn(
    state: TrackerSignIn,
    event: TrackerSignInEvent
): Pair<TrackerSignIn, List<TrackerSignInEffect>> {
    val unchanged = state to emptyList<TrackerSignInEffect>()
    if (event != TrackerSignInEvent.Restart && event.attempt() != state.attempt) return unchanged
    return when (event) {
        TrackerSignInEvent.Restart -> when (state) {
            is TrackerSignIn.Connected -> unchanged
            else -> {
                val next = state.attempt + 1
                TrackerSignIn.Starting(next) to listOf(TrackerSignInEffect.Start(next))
            }
        }

        is TrackerSignInEvent.Started -> {
            if (state !is TrackerSignIn.Starting) return unchanged
            val interval = event.code.intervalSeconds.coerceAtLeast(TRACKER_POLL_MIN_INTERVAL_S)
            TrackerSignIn.Waiting(state.attempt, event.code, interval) to
                listOf(TrackerSignInEffect.Poll(state.attempt, event.code, interval * 1000L))
        }

        is TrackerSignInEvent.StartFailed -> {
            if (state !is TrackerSignIn.Starting) return unchanged
            TrackerSignIn.Failed(state.attempt, TrackerSignIn.Reason.UNREACHABLE, code = null) to emptyList()
        }

        is TrackerSignInEvent.Polled -> {
            if (state !is TrackerSignIn.Waiting) return unchanged
            when (event.result) {
                TrackingDevicePoll.Approved -> TrackerSignIn.Connected(state.attempt) to emptyList()
                TrackingDevicePoll.Denied ->
                    TrackerSignIn.Failed(state.attempt, TrackerSignIn.Reason.DENIED, state.code) to emptyList()
                TrackingDevicePoll.Expired -> TrackerSignIn.Expired(state.attempt, state.code) to emptyList()
                TrackingDevicePoll.SlowDown -> pollAgain(state.copy(intervalSeconds = state.intervalSeconds + TRACKER_POLL_SLOW_DOWN_S, failures = 0), event.nowEpochMs)
                TrackingDevicePoll.Pending -> pollAgain(state.copy(failures = 0), event.nowEpochMs)
            }
        }

        is TrackerSignInEvent.PollFailed -> {
            if (state !is TrackerSignIn.Waiting) return unchanged
            val failures = state.failures + 1
            if (failures > TRACKER_POLL_MAX_FAILURES) {
                TrackerSignIn.Failed(state.attempt, TrackerSignIn.Reason.UNREACHABLE, state.code) to emptyList()
            } else {
                // Back off from the service's interval, not from an already backed-off one.
                val delayS = (state.intervalSeconds shl failures).coerceAtMost(TRACKER_POLL_BACKOFF_MAX_S)
                    .coerceAtLeast(state.intervalSeconds)
                pollAgain(state.copy(failures = failures), event.nowEpochMs, delayS)
            }
        }
    }
}

/** The next poll, unless the code has run out by the local clock (the service would only answer expired). */
private fun pollAgain(
    state: TrackerSignIn.Waiting,
    nowEpochMs: Long,
    delayS: Int = state.intervalSeconds
): Pair<TrackerSignIn, List<TrackerSignInEffect>> =
    if (nowEpochMs >= state.code.expiresAtEpochMs) {
        TrackerSignIn.Expired(state.attempt, state.code) to emptyList()
    } else {
        state to listOf(TrackerSignInEffect.Poll(state.attempt, state.code, delayS * 1000L))
    }

private fun TrackerSignInEvent.attempt(): Int = when (this) {
    TrackerSignInEvent.Restart -> -1
    is TrackerSignInEvent.Started -> attempt
    is TrackerSignInEvent.StartFailed -> attempt
    is TrackerSignInEvent.Polled -> attempt
    is TrackerSignInEvent.PollFailed -> attempt
}

/** The code on screen, while there is one. */
internal val TrackerSignIn.shownCode: TrackingDeviceCode?
    get() = when (this) {
        is TrackerSignIn.Waiting -> code
        is TrackerSignIn.Expired -> code
        is TrackerSignIn.Failed -> code
        is TrackerSignIn.Starting, is TrackerSignIn.Connected -> null
    }
