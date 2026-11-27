package com.nuvio.tv.core.posters

import android.content.Context
import com.nuvio.tv.core.server.DeviceIpAddress
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

/** A running phone page and its QR address. */
class StartedPosterServer internal constructor(val server: RatingPosterServer, val url: String)

/** Starts a phone page on this TV's LAN address ([RatingPosterServer]); null with no network or no free port. */
class RatingPosterServerStarter @Inject constructor(@ApplicationContext private val context: Context) {
    fun start(submit: (RatingPosterServer, String) -> RatingPosterCheck): StartedPosterServer? {
        val ip = DeviceIpAddress.get(context) ?: return null
        val server = RatingPosterServer.startOnAvailablePort(
            pageHtml = { RatingPosterWebPage.html(context) },
            submit = submit,
        ) ?: return null
        return StartedPosterServer(server, server.url(ip))
    }
}

/**
 * The Rating posters panel's phone page (TmdbKeyPhonePage's shape). [open] starts a page for one panel
 * session (a new server: fresh token) bound to the profile the panel shows; [close] (the panel closed,
 * ON_STOP, the ViewModel cleared) ends it, and the [lifetime] ends it on its own. [url] is null while no
 * page runs: then the panel shows no QR (also with no network or no free port). Main thread only, except
 * [submit] on the server's handler threads.
 */
class RatingPosterPhonePage(
    private val scope: CoroutineScope,
    private val start: (submit: (RatingPosterServer, String) -> RatingPosterCheck) -> StartedPosterServer?,
    private val checkAndSave: suspend (input: String, profileId: Int, stillWanted: () -> Boolean) -> RatingPosterCheck,
    private val lifetime: Duration = LIFETIME,
) {
    private var server: RatingPosterServer? = null
    private var lifetimeJob: Job? = null
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /** Starts the page for [profileId], ending any other first. */
    fun open(profileId: Int) {
        close()
        val started = start { from, input -> submit(from, profileId, input) } ?: return
        server = started.server
        _url.value = started.url
        lifetimeJob = scope.launch {
            delay(lifetime)
            if (server === started.server) close()
        }
    }

    /** Ends the page; a send already being checked still gets its answer ([RatingPosterServer.close]). */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val closing = server
        server = null
        _url.value = null
        closing?.close()
    }

    /**
     * One send at a time (across this page's servers), so a slow older send never saves over a newer one that
     * already answered; a send queued behind another answers Closed if its page closed meanwhile.
     */
    private val sendLock = Any()

    /**
     * A phone send, on [from]'s handler thread. The check and save run here, never on [scope], so the phone
     * always gets its answer; a send whose page closed before its poster came back saves nothing. The panel
     * shows the result through [RatingPosters.state].
     */
    private fun submit(from: RatingPosterServer, profileId: Int, input: String): RatingPosterCheck =
        synchronized(sendLock) {
            if (from.isClosed) RatingPosterCheck.Closed
            else runBlocking { checkAndSave(input, profileId) { !from.isClosed } }
        }

    companion object {
        /** Absolute, as the key and photo pages': a page left open is not reachable for long. */
        val LIFETIME: Duration = 10.minutes
    }
}
