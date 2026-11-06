package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.TmdbKeyAnswer
import com.nuvio.tv.core.server.TmdbKeyServer
import com.nuvio.tv.core.server.TmdbKeyWebPage
import com.nuvio.tv.core.tmdb.TmdbKeyCheck
import com.nuvio.tv.core.tmdb.TmdbKeySession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A running key page and its QR address. */
internal class StartedKeyServer(val server: TmdbKeyServer, val url: String)

/** Starts a key page on this TV's LAN address ([TmdbKeyServer]); null with no network or no free port. */
class TmdbKeyServerStarter @Inject constructor(@ApplicationContext private val context: Context) {
    internal fun start(submit: (TmdbKeyServer, String) -> TmdbKeyAnswer): StartedKeyServer? {
        val ip = DeviceIpAddress.get(context) ?: return null
        val server = TmdbKeyServer.startOnAvailablePort(
            pageHtml = { TmdbKeyWebPage.html(context) },
            submit = submit
        ) ?: return null
        return StartedKeyServer(server, server.url(ip))
    }
}

/**
 * The key panel's phone page (Spec 52 S3; Spec 44's PhotoUploadController shape). [open] starts a
 * page for one panel session (a new server: fresh token), [close] (the panel closed, ON_STOP, the
 * ViewModel cleared) ends it, and the [lifetime] ends it on its own (the panel stays with its TV
 * field). [url] is null while no page runs: then the panel shows no phone block (also with no
 * network or no free port). Main thread only, except [submit] on the server's handler threads.
 */
internal class TmdbKeyPhonePage(
    private val scope: CoroutineScope,
    private val start: (submit: (TmdbKeyServer, String) -> TmdbKeyAnswer) -> StartedKeyServer?,
    private val checkAndSave: suspend (String, TmdbKeySession, () -> Boolean) -> TmdbKeyCheck?,
    private val onSaved: (TmdbKeySession) -> Unit,
    private val lifetime: Duration = LIFETIME
) {
    private var server: TmdbKeyServer? = null
    private var lifetimeJob: Job? = null
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /** Starts the page for [session], ending any other first. */
    fun open(session: TmdbKeySession) {
        close()
        val started = start { from, key -> submit(from, session, key) } ?: return
        server = started.server
        _url.value = started.url
        lifetimeJob = scope.launch {
            delay(lifetime)
            expire(started.server)
        }
    }

    /** Ends the page; a send already being checked still gets its answer ([TmdbKeyServer.close]). */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val closing = server
        server = null
        _url.value = null
        closing?.close()
    }

    // Runs inside lifetimeJob: close() cancelling it is harmless, nothing suspends after.
    private fun expire(expiring: TmdbKeyServer) {
        if (server === expiring) close()
    }

    /**
     * A phone send, on [from]'s handler thread. The check and save run here, never on [scope]: closing
     * the panel or clearing the ViewModel cannot cut TMDB's yes from its save, or the phone from its
     * answer. A send queued behind another check is dropped once [from] has closed.
     */
    private fun submit(from: TmdbKeyServer, session: TmdbKeySession, key: String): TmdbKeyAnswer =
        when (runBlocking { checkAndSave(key, session) { !from.isClosed } }) {
            TmdbKeyCheck.ACCEPTED -> {
                scope.launch { onSaved(session) }
                TmdbKeyAnswer.SAVED
            }
            TmdbKeyCheck.REJECTED -> TmdbKeyAnswer.REJECTED
            TmdbKeyCheck.UNREACHABLE -> TmdbKeyAnswer.UNREACHABLE
            null -> if (session.current == TmdbKeySession.State.SAVED) TmdbKeyAnswer.ALREADY_SAVED else TmdbKeyAnswer.CLOSED
        }

    companion object {
        /** Absolute, as the photo page's: a page left open is not reachable for long. */
        val LIFETIME: Duration = 10.minutes
    }
}
