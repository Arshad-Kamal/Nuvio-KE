package com.nuvio.tv.core.tmdb

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.di.NetworkModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Spec 52 P1/P2: the no-key guard sends nothing; the user's key wins over the build's. */
class TmdbApiKeysTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private var proceeded = 0

    // The guard, then a stand-in for the network that counts what got past it.
    private val client = OkHttpClient.Builder()
        .addInterceptor(TmdbNoKeyInterceptor())
        .addInterceptor { chain ->
            proceeded++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody(null))
                .build()
        }
        .build()

    private fun call(url: String): Int =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `a blank or missing api_key answers 401 without a request`() {
        assertEquals(401, call("https://api.themoviedb.org/3/movie/1?api_key="))
        assertEquals(401, call("https://api.themoviedb.org/3/movie/1?api_key=%20"))
        assertEquals(401, call("https://api.themoviedb.org/3/movie/1"))
        assertEquals(0, proceeded)
    }

    @Test
    fun `a request with a key goes through`() {
        assertEquals(200, call("https://api.themoviedb.org/3/movie/1?api_key=abc&language=en"))
        assertEquals(1, proceeded)
    }

    @Test
    fun `the build key serves until a user key is saved, and again after it is removed`() = runTest {
        val provider = provider(builtInKey = "built-in", scope = backgroundScope)
        assertEquals("built-in", provider.key())

        provider.save("  user-key  ")
        assertEquals(TmdbKeyState(userKey = "user-key", effectiveKey = "user-key"), provider.awaitEffective("user-key"))
        assertEquals("user-key", provider.key())

        provider.remove()
        assertFalse(provider.awaitEffective("built-in").hasUserKey)
        assertEquals("built-in", provider.key())
    }

    @Test
    fun `a build without a key has no key until the user saves one`() = runTest {
        val provider = provider(builtInKey = "", scope = backgroundScope)
        assertFalse(provider.awaitEffective("").hasKey)
        assertEquals("", provider.key())

        provider.save("user-key")
        assertTrue(provider.awaitEffective("user-key").hasKey)
    }

    @Test
    fun `key waits for the store's first read instead of sending the build key`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stored = preferencesOf(stringPreferencesKey("user_key") to "user-key")
        val provider = TmdbApiKeyProvider(fakeStore(flow { gate.await(); emit(stored) }), backgroundScope, "built-in")

        val key = async { provider.key() }
        runCurrent()
        assertFalse(key.isCompleted)

        gate.complete(Unit)
        assertEquals("user-key", key.await())
    }

    @Test
    fun `a failed store read settles on no user key instead of waiting forever`() = runTest {
        val provider = TmdbApiKeyProvider(fakeStore(flow { throw IOException("disk") }), backgroundScope, "built-in")

        assertEquals("built-in", provider.key())
        assertFalse(provider.state.value!!.hasUserKey)
    }

    @Test
    fun `after a failed first read the state follows the store once reads work`() = runTest {
        var collections = 0
        val stored = preferencesOf(stringPreferencesKey("user_key") to "user-key")
        val store = fakeStore(flow {
            collections++
            if (collections == 1) throw IOException("disk")
            emit(stored)
        })
        val provider = TmdbApiKeyProvider(store, backgroundScope, "built-in")

        assertEquals("built-in", provider.key())
        assertEquals("user-key", provider.awaitEffective("user-key").userKey)
    }

    @Test
    fun `a read failure after a loaded key keeps that key`() = runTest {
        val stored = preferencesOf(stringPreferencesKey("user_key") to "user-key")
        val store = fakeStore(flow {
            emit(stored)
            throw IOException("disk")
        })
        val provider = TmdbApiKeyProvider(store, backgroundScope, "built-in")

        assertEquals("user-key", provider.key())
        advanceTimeBy(12_000)
        assertEquals("user-key", provider.state.value!!.effectiveKey)
    }

    @Test
    fun `the TMDB Retrofit client carries the no-key guard`() {
        val retrofit = NetworkModule.provideTmdbRetrofit(
            OkHttpClient(),
            NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson())
        )
        val client = retrofit.callFactory() as OkHttpClient
        assertTrue(client.interceptors.any { it is TmdbNoKeyInterceptor })
    }

    @Test
    fun `awaitKey returns at once with a key, else reports no key and returns once one is saved`() = runTest {
        var noKeyCalls = 0
        TmdbApiKeyProvider(fakeStore(MutableStateFlow(emptyPreferences())), backgroundScope, "built-in")
            .awaitKey { noKeyCalls++ }
        assertEquals(0, noKeyCalls)

        val stored = MutableStateFlow(emptyPreferences())
        val keyless = TmdbApiKeyProvider(fakeStore(stored), backgroundScope, "")
        val waiting = async { keyless.awaitKey { noKeyCalls++ } }
        runCurrent()
        assertEquals(1, noKeyCalls)
        assertFalse(waiting.isCompleted)

        stored.value = preferencesOf(stringPreferencesKey("user_key") to "user-key")
        waiting.await()
        assertEquals(1, noKeyCalls)
    }

    @Test
    fun `the key state never prints a key`() {
        val text = TmdbKeyState(userKey = "secret-user", effectiveKey = "secret-user").toString()
        assertFalse(text.contains("secret"))
    }

    private fun fakeStore(data: Flow<Preferences>) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("read-only test store")
    }

    // A write lands in the store first; the state follows on its own collector.
    private suspend fun TmdbApiKeyProvider.awaitEffective(expected: String): TmdbKeyState =
        state.filterNotNull().first { it.effectiveKey == expected }

    private fun provider(
        builtInKey: String,
        scope: CoroutineScope,
        file: File = File(tmpFolder.root, "tmdb_api_key.preferences_pb")
    ) = TmdbApiKeyProvider(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        scope,
        builtInKey
    )
}
