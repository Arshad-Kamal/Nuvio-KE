package com.nuvio.tv.core.tmdb

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.loggedResetCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

/** The TMDB API key every TmdbApi call passes (Spec 52 P2). */
fun interface TmdbApiKeySource {
    /** The key to send; blank when this build has none and the user saved none. */
    suspend fun key(): String
}

/** [userKey]: the key saved on this TV (blank = none); [effectiveKey]: what requests send. */
data class TmdbKeyState(val userKey: String, val effectiveKey: String) {
    val hasKey: Boolean get() = effectiveKey.isNotBlank()
    val hasUserKey: Boolean get() = userKey.isNotBlank()

    /** Never the keys themselves: this may reach a log or a crash report. */
    override fun toString(): String = "TmdbKeyState(hasUserKey=$hasUserKey, hasKey=$hasKey)"
}

/** Device-wide, unsynced, kept on sign-out (ProfileDataStoreFactory's retained names). */
internal const val TMDB_API_KEY_STORE_NAME = "tmdb_api_key"

private val Context.tmdbApiKeyDataStore by preferencesDataStore(
    name = TMDB_API_KEY_STORE_NAME,
    corruptionHandler = loggedResetCorruptionHandler(TMDB_API_KEY_STORE_NAME)
)

private val USER_KEY = stringPreferencesKey("user_key")

/**
 * The user's own TMDB key (Spec 52 T1), else the build's: public APKs ship without one (Spec 49 D2),
 * private builds keep theirs as the fallback. The value is never logged.
 */
@Singleton
class TmdbApiKeyProvider internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val builtInKey: String
) : TmdbApiKeySource {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.tmdbApiKeyDataStore,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        BuildConfig.TMDB_API_KEY
    )

    // Set by the first real read; retryWhen's attempt counter never resets, so it cannot tell.
    @Volatile
    private var loaded = false

    /** Null until the store's first read; the app-lifetime scope owns the sharing. */
    val state: StateFlow<TmdbKeyState?> = dataStore.data
        .onEach { loaded = true }
        // A failed read before any value still settles the state (no user key), or every key() would
        // wait forever; after a value it keeps that value. Either way the collection retries, so the
        // state follows the store again once reads work.
        .retryWhen { e, _ ->
            Log.w(TAG, "TMDB key store read failed: ${e.javaClass.simpleName}")
            if (!loaded) emit(emptyPreferences())
            delay(READ_RETRY_MS)
            true
        }
        .map { prefs ->
            val userKey = prefs[USER_KEY].orEmpty()
            TmdbKeyState(userKey = userKey, effectiveKey = userKey.ifBlank { builtInKey })
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun key(): String = state.filterNotNull().first().effectiveKey

    /**
     * Returns once [state] (and so [key]) carries the saved key, or after [STATE_WAIT_MS] (a StateFlow
     * keeps only the latest value, so an overlapping write could otherwise leave this waiting forever).
     */
    suspend fun save(key: String) {
        val trimmed = key.trim()
        dataStore.edit { it[USER_KEY] = trimmed }
        withTimeoutOrNull(STATE_WAIT_MS) { state.first { it?.userKey == trimmed } }
    }

    /** Returns once [state] carries no user key, or after [STATE_WAIT_MS] (see [save]). */
    suspend fun remove() {
        dataStore.edit { it.remove(USER_KEY) }
        withTimeoutOrNull(STATE_WAIT_MS) { state.first { it?.hasUserKey == false } }
    }

    private companion object {
        const val TAG = "TmdbApiKeyProvider"
        const val READ_RETRY_MS = 5_000L
        const val STATE_WAIT_MS = 1_000L
    }
}

/**
 * For a page that needs TMDB (Spec 52 T1): returns at once when a key is set; otherwise runs [onNoKey] and
 * returns once one is saved, so the page loads by itself after Add a TMDB key.
 */
suspend fun TmdbApiKeyProvider.awaitKey(onNoKey: () -> Unit) {
    if (state.filterNotNull().first().hasKey) return
    onNoKey()
    state.first { it?.hasKey == true }
}

/** A TMDB call made with no key at all (Spec 52 T1); its message is the UI's "Needs a TMDB key". */
class TmdbNoKeyException(message: String) : IllegalStateException(message)

/**
 * Spec 52 P1, the one "no key = no request" guard: a TMDB request without an `api_key` value never
 * leaves the device and reads as a 401, which every TmdbApi caller already maps to null or empty.
 * An application interceptor, so it answers before the HTTP cache.
 */
internal class TmdbNoKeyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.queryParameter("api_key").isNullOrBlank()) return chain.proceed(request)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("No TMDB API key")
            .body("".toResponseBody(null))
            .build()
    }
}
