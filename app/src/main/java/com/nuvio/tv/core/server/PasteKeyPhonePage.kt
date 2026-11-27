package com.nuvio.tv.core.server

import android.content.Context
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

/** A running paste page and its QR address. */
class StartedPasteKeyServer internal constructor(val server: PasteKeyServer, val url: String)

/** Starts a paste page on this TV's LAN address; null with no network or no free port in [startPort]'s ten. */
class PasteKeyServerStarter @Inject constructor(@ApplicationContext private val context: Context) {
    fun start(
        strings: PasteKeyPageStrings,
        startPort: Int,
        submit: (PasteKeyServer, String) -> PasteCheck
    ): StartedPasteKeyServer? {
        val ip = DeviceIpAddress.get(context) ?: return null
        val server = PasteKeyServer.startOnAvailablePort(
            pageHtml = { PasteKeyWebPage.html(context, strings) },
            submit = submit,
            maxBodyBytes = MAX_PASTE_BODY_BYTES,
            startPort = startPort
        ) ?: return null
        return StartedPasteKeyServer(server, server.url(ip))
    }

    private companion object {
        /** A key or app id is far shorter; a little room for the whitespace the page already drops. */
        const val MAX_PASTE_BODY_BYTES = 1_024
    }
}

/**
 * A panel's paste page (RatingPosterPhonePage's shape). [open] starts a page for one panel session (a new server: fresh
 * token), [close] (the panel closed, ON_STOP, the ViewModel cleared) ends it, and the [lifetime] ends it on its own.
 * [url] is null while no page runs: then the panel shows no QR (also with no network or no free port). Main thread
 * only, except the check on the server's handler threads.
 */
class PasteKeyPhonePage(
    private val scope: CoroutineScope,
    private val lifetime: Duration = LIFETIME
) {
    private var server: PasteKeyServer? = null
    private var lifetimeJob: Job? = null
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    /**
     * One send at a time (across this page's servers), so a slow older send never saves over a newer one that already
     * answered; a send queued behind another answers Closed if its page closed meanwhile.
     */
    private val sendLock = Any()

    /**
     * Starts a page through [start], ending any other first. [check] checks and saves one value; it runs on the
     * server's handler thread, never on [scope], so closing the panel cannot cut a yes from its save or the phone from
     * its answer; its `stillWanted` turns false once that page closed.
     */
    fun open(
        start: (submit: (PasteKeyServer, String) -> PasteCheck) -> StartedPasteKeyServer?,
        check: suspend (value: String, stillWanted: () -> Boolean) -> PasteCheck
    ) {
        close()
        val started = start { from, value ->
            synchronized(sendLock) {
                if (from.isClosed) PasteCheck.Closed else runBlocking { check(value) { !from.isClosed } }
            }
        } ?: return
        server = started.server
        _url.value = started.url
        lifetimeJob = scope.launch {
            delay(lifetime)
            if (server === started.server) close()
        }
    }

    /** Ends the page; a send already being checked still gets its answer ([PasteKeyServer.close]). */
    fun close() {
        lifetimeJob?.cancel()
        lifetimeJob = null
        val closing = server
        server = null
        _url.value = null
        closing?.close()
    }

    companion object {
        /** Absolute, as the key and photo pages': a page left open is not reachable for long. */
        val LIFETIME: Duration = 10.minutes
    }
}
