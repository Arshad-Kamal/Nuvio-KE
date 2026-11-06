package com.nuvio.tv.ui.screens.account

import androidx.annotation.StringRes
import com.nuvio.tv.R

/**
 * QR sign-in as a pure state machine (Spec 31 S7a). The ViewModel owns the RPCs and coroutines,
 * feeds their results in as [QrEvent]s and runs the returned [QrEffect]s; nothing else starts an
 * exchange. Rules:
 * - Exchange is emitted only on the Showing -> Exchanging transition (poll said "approved").
 * - ExchangeFailed -> Failed with no effects: Refresh is the only way out (no retry loop).
 * - The server's `expired` is authoritative; a local clock past expiresAt only changes the display
 *   ([qrCodeExpired]: dimmed code, "Expired", the expired copy) and polling continues. Nothing
 *   regenerates a code on its own.
 * - Polling is one-shot: each SchedulePoll runs one poll whose result comes back as an event.
 * Results carry the code (or nonce) they belong to; a result for another session is ignored.
 */
sealed interface QrLogin {
    data object Idle : QrLogin
    data class Starting(val nonce: String) : QrLogin
    data class Showing(
        val code: String,
        val nonce: String,
        val expiresAtMs: Long?,
        val baseIntervalS: Int,
        val failures: Int = 0,
        val polled: Boolean = false
    ) : QrLogin
    data class Exchanging(val code: String, val nonce: String) : QrLogin
    data class Expired(val code: String) : QrLogin
    data class Ended(val code: String, val reason: EndReason) : QrLogin
    data class Failed(val code: String?, val message: String) : QrLogin
    data object SignedIn : QrLogin

    enum class EndReason { USED, CANCELLED }
}

sealed interface QrEvent {
    /** Start a new session; the caller generates the nonce. */
    data class Refresh(val nonce: String) : QrEvent
    data class Started(val nonce: String, val code: String, val expiresAtMs: Long?, val intervalS: Int) : QrEvent
    data class StartFailed(val nonce: String, val message: String) : QrEvent
    data class Polled(val code: String, val status: String, val expiresAtMs: Long?, val intervalS: Int?) : QrEvent
    data class PollFailed(val code: String, val message: String) : QrEvent
    data class ExchangeOk(val code: String) : QrEvent
    data class ExchangeFailed(val code: String, val message: String) : QrEvent
    data object Clear : QrEvent
}

sealed interface QrEffect {
    data class StartSession(val nonce: String) : QrEffect
    data class SchedulePoll(val delayMs: Long, val code: String, val nonce: String) : QrEffect
    data class Exchange(val code: String, val nonce: String) : QrEffect
    data object CancelPoll : QrEffect
}

internal const val QR_POLL_MIN_INTERVAL_S = 2
internal const val QR_POLL_MAX_CONSECUTIVE_FAILURES = 3
internal const val QR_POLL_BACKOFF_MAX_S = 30

fun reduceQrLogin(state: QrLogin, event: QrEvent): Pair<QrLogin, List<QrEffect>> {
    val unchanged = state to emptyList<QrEffect>()
    return when (event) {
        is QrEvent.Refresh -> when (state) {
            is QrLogin.Starting, is QrLogin.Exchanging, QrLogin.SignedIn -> unchanged
            else -> QrLogin.Starting(event.nonce) to
                listOf(QrEffect.CancelPoll, QrEffect.StartSession(event.nonce))
        }

        is QrEvent.Started -> {
            if (state !is QrLogin.Starting || state.nonce != event.nonce) return unchanged
            val interval = event.intervalS.coerceAtLeast(QR_POLL_MIN_INTERVAL_S)
            QrLogin.Showing(
                code = event.code,
                nonce = event.nonce,
                expiresAtMs = event.expiresAtMs,
                baseIntervalS = interval
            ) to listOf(QrEffect.SchedulePoll(interval * 1000L, event.code, event.nonce))
        }

        is QrEvent.StartFailed -> {
            if (state !is QrLogin.Starting || state.nonce != event.nonce) return unchanged
            QrLogin.Failed(code = null, message = event.message) to emptyList()
        }

        is QrEvent.Polled -> {
            if (state !is QrLogin.Showing || state.code != event.code) return unchanged
            when (event.status.lowercase()) {
                "approved" -> QrLogin.Exchanging(state.code, state.nonce) to
                    listOf(QrEffect.Exchange(state.code, state.nonce))
                "expired" -> QrLogin.Expired(state.code) to emptyList()
                "used" -> QrLogin.Ended(state.code, QrLogin.EndReason.USED) to emptyList()
                "cancelled" -> QrLogin.Ended(state.code, QrLogin.EndReason.CANCELLED) to emptyList()
                // "pending" and any status this build does not know: keep waiting.
                else -> {
                    val base = event.intervalS?.coerceAtLeast(QR_POLL_MIN_INTERVAL_S) ?: state.baseIntervalS
                    val next = state.copy(
                        expiresAtMs = event.expiresAtMs ?: state.expiresAtMs,
                        baseIntervalS = base,
                        failures = 0,
                        polled = true
                    )
                    next to listOf(QrEffect.SchedulePoll(base * 1000L, state.code, state.nonce))
                }
            }
        }

        is QrEvent.PollFailed -> {
            if (state !is QrLogin.Showing || state.code != event.code) return unchanged
            val failures = state.failures + 1
            if (failures > QR_POLL_MAX_CONSECUTIVE_FAILURES) {
                QrLogin.Failed(code = state.code, message = event.message) to emptyList()
            } else {
                // Transient: back off from the base interval, not the already backed-off one.
                val backedOff = (state.baseIntervalS shl failures).coerceAtMost(QR_POLL_BACKOFF_MAX_S)
                state.copy(failures = failures) to
                    listOf(QrEffect.SchedulePoll(backedOff * 1000L, state.code, state.nonce))
            }
        }

        is QrEvent.ExchangeOk -> {
            if (state !is QrLogin.Exchanging || state.code != event.code) return unchanged
            QrLogin.SignedIn to emptyList()
        }

        is QrEvent.ExchangeFailed -> {
            if (state !is QrLogin.Exchanging || state.code != event.code) return unchanged
            QrLogin.Failed(code = state.code, message = event.message) to emptyList()
        }

        QrEvent.Clear -> QrLogin.Idle to listOf(QrEffect.CancelPoll)
    }
}

/** The code to print under the QR, while one exists. */
val QrLogin.displayCode: String?
    get() = when (this) {
        is QrLogin.Showing -> code
        is QrLogin.Exchanging -> code
        is QrLogin.Expired -> code
        is QrLogin.Ended -> code
        is QrLogin.Failed -> code
        QrLogin.Idle, is QrLogin.Starting, QrLogin.SignedIn -> null
    }

/** A request is in flight: the Refresh button waits. */
val QrLogin.isBusy: Boolean
    get() = this is QrLogin.Starting || this is QrLogin.Exchanging

/**
 * The code has run out: the server said so, or the local clock passed its expiry (display only;
 * polling goes on). Shows "Code expired" over the QR and "Expired" for the countdown.
 */
fun qrCodeExpired(state: QrLogin, nowMs: Long): Boolean = when (state) {
    is QrLogin.Expired -> true
    is QrLogin.Showing -> state.expiresAtMs != null && nowMs >= state.expiresAtMs
    else -> false
}

/** The code is dead (expired, or nothing polls it any more): the QR dims and the status line turns red. */
fun qrCodeDimmed(state: QrLogin, nowMs: Long): Boolean =
    qrCodeExpired(state, nowMs) || state is QrLogin.Ended || state is QrLogin.Failed

/** Status line copy; null when there is none (Failed shows its message instead). */
@StringRes
fun qrStatusRes(state: QrLogin, nowMs: Long): Int? = when (state) {
    QrLogin.Idle, is QrLogin.Failed -> null
    is QrLogin.Starting -> R.string.qr_login_preparing
    is QrLogin.Showing -> when {
        qrCodeExpired(state, nowMs) -> R.string.qr_login_code_expired
        state.polled -> R.string.qr_login_pending
        else -> R.string.qr_login_scan_prompt
    }
    is QrLogin.Exchanging -> R.string.qr_login_signing_in
    is QrLogin.Expired -> R.string.qr_login_code_expired
    is QrLogin.Ended -> when (state.reason) {
        QrLogin.EndReason.USED -> R.string.qr_login_used
        QrLogin.EndReason.CANCELLED -> R.string.qr_login_cancelled
    }
    QrLogin.SignedIn -> R.string.qr_login_success
}
