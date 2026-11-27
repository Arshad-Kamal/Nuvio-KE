package com.nuvio.tv.core.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How opening the phone page went (b5). */
enum class BackupPhoneOpen { Opened, NoNetwork, NoPort }

internal sealed interface BackupPhoneStart {
    class Started(val server: BackupPhoneServer, val url: String) : BackupPhoneStart
    data object NoNetwork : BackupPhoneStart
    data object NoPort : BackupPhoneStart
}

/**
 * The QR panel's page (open while [url] is set) and, in RESTORE, the upload waiting for the TV's restore
 * screen ([pending]), which outlives the panel's 10 minutes so a restore in progress keeps its file.
 */
data class BackupPhoneState(
    val url: String? = null,
    val mode: BackupPhoneMode? = null,
    val pending: PendingUpload? = null,
)

/**
 * One phone-page session at a time (Spec 101 S6; the PhotoUploadController pattern, Spec 44). [open]
 * starts a new session (new server: fresh token, own slot) and stops any other; [close] (Back, leaving the
 * screen, a restore staged) stops it and deletes what it received; after [lifetime] (an absolute 10
 * minutes, not idle-based) the page stops but a pending upload stays until [release] or [close]. An upload
 * from any session but the current one is dropped. The server's handler threads call [onPending], so
 * session and state changes share one lock.
 */
class BackupPhoneSession internal constructor(
    private val scope: CoroutineScope,
    private val start: (BackupPhoneMode, (BackupPhoneServer, PendingUpload) -> Unit) -> BackupPhoneStart,
    private val lifetime: Duration = LIFETIME,
) {
    private val lock = Any()
    private var server: BackupPhoneServer? = null
    // Main thread only (open, close and the lifetime coroutine on the caller's scope).
    private var lifetimeJob: Job? = null
    private val _state = MutableStateFlow(BackupPhoneState())
    val state: StateFlow<BackupPhoneState> = _state.asStateFlow()

    fun open(mode: BackupPhoneMode): BackupPhoneOpen {
        close()
        return when (val started = start(mode, ::onPending)) {
            BackupPhoneStart.NoNetwork -> BackupPhoneOpen.NoNetwork
            BackupPhoneStart.NoPort -> BackupPhoneOpen.NoPort
            is BackupPhoneStart.Started -> {
                synchronized(lock) {
                    server = started.server
                    _state.value = BackupPhoneState(url = started.url, mode = mode)
                }
                lifetimeJob = scope.launch {
                    delay(lifetime)
                    expire(started.server)
                }
                BackupPhoneOpen.Opened
            }
        }
    }

    /** Stops the page and deletes any upload; safe to call when nothing is open. */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val stopping = synchronized(lock) {
            val current = server
            server = null
            _state.value = BackupPhoneState()
            current
        }
        stopping?.stop()
    }

    /**
     * Done with pending [id] (turned down on the TV): its file is deleted and the phone may send another.
     * A restore that stages it calls [close] instead, after staging has read the file.
     */
    fun release(id: String) {
        val from = synchronized(lock) {
            if (_state.value.pending?.id != id) return
            _state.update { it.copy(pending = null) }
            server
        }
        from?.release(id)
    }

    private fun expire(expiring: BackupPhoneServer) {
        synchronized(lock) {
            if (server !== expiring) return
            _state.update { it.copy(url = null) }
        }
        // The page stops; a pending upload's file stays with the server until release or close.
        expiring.stopListening()
    }

    private fun onPending(from: BackupPhoneServer, upload: PendingUpload) {
        val current = synchronized(lock) {
            if (server === from) {
                _state.update { it.copy(pending = upload) }
                true
            } else {
                false
            }
        }
        if (!current) from.release(upload.id)
    }

    companion object {
        val LIFETIME: Duration = 10.minutes
    }
}
