package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.trailer.TrailerService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** What TMDB said about a key (Spec 52 P3). */
enum class TmdbKeyCheck { ACCEPTED, REJECTED, UNREACHABLE }

/**
 * One key panel from open to close (Spec 52 P3), shared by its TV field and its phone page; safe from
 * any thread (phone sends arrive on server threads). [TmdbKeyEntry] re-reads it after taking its lock,
 * so a submit queued behind an accepted key, a removal or a closed panel is never checked.
 */
class TmdbKeySession {
    enum class State { OPEN, SAVED, CLOSED }

    private val state = AtomicReference(State.OPEN)

    val current: State get() = state.get()

    /** The panel closed (or its key was removed). A session that saved a key stays SAVED. */
    fun close() {
        state.compareAndSet(State.OPEN, State.CLOSED)
    }

    internal fun saved() {
        state.compareAndSet(State.OPEN, State.SAVED)
    }
}

/** A v3 TMDB API key's length: the key field's cap. */
const val TMDB_KEY_LENGTH = 32

/** The key field's input: whitespace dropped (a paste often carries some), at most [TMDB_KEY_LENGTH]. */
fun tmdbKeyInput(raw: String): String = raw.filterNot(Char::isWhitespace).take(TMDB_KEY_LENGTH)

/** The last four characters of the key requests send, for "Saved · ends e0a8"; null when there is none. */
fun TmdbKeyState.savedKeyEnding(): String? = effectiveKey.takeIf { hasKey }?.trim()?.takeLast(4)

/**
 * Spec 52 P3: checks a candidate key with `GET /3/configuration` and saves it once TMDB accepts it.
 * One check at a time, shared by the TV field and the phone page: a second submit waits for the first,
 * then re-reads its [TmdbKeySession].
 */
@Singleton
class TmdbKeyEntry @Inject constructor(
    private val api: TmdbApi,
    private val keys: TmdbApiKeyProvider,
    private val trailerService: TrailerService
) {
    private val lock = Mutex()

    /**
     * Checks [candidate] (trimmed) and, when TMDB accepts it, saves it and marks [session] saved before
     * the next submit may start. Null, with nothing sent to TMDB, when by the time this submit holds the
     * lock [session] is no longer open or [stillWanted] (the phone page's own lifetime) says no.
     * Cancelling the caller during the check saves nothing; once TMDB has said yes the save finishes
     * regardless, also for a session closed meanwhile (the key is valid). A failed write returns
     * UNREACHABLE: nothing was saved and trying again is the remedy.
     */
    suspend fun checkAndSave(
        candidate: String,
        session: TmdbKeySession,
        stillWanted: () -> Boolean = { true }
    ): TmdbKeyCheck? = lock.withLock {
        if (session.current != TmdbKeySession.State.OPEN || !stillWanted()) return@withLock null
        val key = candidate.trim()
        val result = check(key)
        if (result != TmdbKeyCheck.ACCEPTED) return@withLock result
        val saved = withContext(NonCancellable) { keyChanged { keys.save(key) } }
        if (!saved) return@withLock TmdbKeyCheck.UNREACHABLE
        session.saved()
        TmdbKeyCheck.ACCEPTED
    }

    /**
     * Drops the saved key and ends [session]; requests fall back to the build's key (none in a public
     * build). False when the write failed (the key stays), [session] is no longer open, or the saved key
     * is no longer [shownKey], the one the panel showed: a key saved meanwhile (from the phone, in this
     * panel or a just-closed one) is never removed unseen.
     */
    suspend fun remove(session: TmdbKeySession, shownKey: String): Boolean = lock.withLock {
        if (session.current != TmdbKeySession.State.OPEN || keys.state.value?.userKey != shownKey) return@withLock false
        val removed = withContext(NonCancellable) { keyChanged { keys.remove() } }
        if (removed) session.close()
        removed
    }

    // Trailer lookups made under the old key (3 h negative entries while keyless) must not outlive it.
    private suspend fun keyChanged(change: suspend () -> Unit): Boolean = try {
        change()
        trailerService.clearCache()
        true
    } catch (e: IOException) {
        Log.w(TAG, "TMDB key store write failed: ${e.javaClass.simpleName}")
        false
    }

    // The timeout wraps the call: the TMDB client's own callTimeout is 30 s.
    private suspend fun check(key: String): TmdbKeyCheck {
        val code = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            try {
                api.checkApiKey(key).code()
            } catch (e: IOException) {
                null
            }
        }
        return when (code) {
            200 -> TmdbKeyCheck.ACCEPTED
            401 -> TmdbKeyCheck.REJECTED
            else -> TmdbKeyCheck.UNREACHABLE
        }
    }

    internal companion object {
        private const val TAG = "TmdbKeyEntry"
        const val CHECK_TIMEOUT_MS = 10_000L
    }
}
