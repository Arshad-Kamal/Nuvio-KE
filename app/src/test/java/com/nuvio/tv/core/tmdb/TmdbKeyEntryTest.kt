package com.nuvio.tv.core.tmdb

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.trailer.TrailerService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.io.IOException

/** Spec 52 P3: what TMDB's answer means, and that only an accepted key is saved. */
class TmdbKeyEntryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val api = mockk<TmdbApi>()
    private val trailers = mockk<TrailerService>(relaxed = true)

    @Test
    fun `200 saves the trimmed key and clears the trailer cache`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val provider = provider(backgroundScope)

        assertEquals(TmdbKeyCheck.ACCEPTED, entry(provider).checkAndSave("  $KEY  ", TmdbKeySession()))

        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)
        verify(exactly = 1) { trailers.clearCache() }
    }

    @Test
    fun `401 is rejected and nothing is saved`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.error(401, "".toResponseBody(null))
        val provider = provider(backgroundScope)

        assertEquals(TmdbKeyCheck.REJECTED, entry(provider).checkAndSave(KEY, TmdbKeySession()))

        assertFalse(provider.state.filterNotNull().first().hasUserKey)
        verify(exactly = 0) { trailers.clearCache() }
    }

    @Test
    fun `other statuses, a network failure and no answer in time are unreachable`() = runTest {
        val provider = provider(backgroundScope)
        coEvery { api.checkApiKey(KEY) } returns Response.error(503, "".toResponseBody(null))
        assertEquals(TmdbKeyCheck.UNREACHABLE, entry(provider).checkAndSave(KEY, TmdbKeySession()))

        coEvery { api.checkApiKey(KEY) } throws IOException("offline")
        assertEquals(TmdbKeyCheck.UNREACHABLE, entry(provider).checkAndSave(KEY, TmdbKeySession()))

        coEvery { api.checkApiKey(KEY) } coAnswers {
            delay(TmdbKeyEntry.CHECK_TIMEOUT_MS + 1)
            Response.success(Unit)
        }
        assertEquals(TmdbKeyCheck.UNREACHABLE, entry(provider).checkAndSave(KEY, TmdbKeySession()))

        assertFalse(provider.state.filterNotNull().first().hasUserKey)
        verify(exactly = 0) { trailers.clearCache() }
    }

    @Test
    fun `an answer just inside the 10 s check counts`() = runTest {
        assertEquals(10_000L, TmdbKeyEntry.CHECK_TIMEOUT_MS)
        coEvery { api.checkApiKey(KEY) } coAnswers {
            delay(TmdbKeyEntry.CHECK_TIMEOUT_MS - 1)
            Response.success(Unit)
        }
        assertEquals(TmdbKeyCheck.ACCEPTED, entry(provider(backgroundScope)).checkAndSave(KEY, TmdbKeySession()))
    }

    @Test
    fun `cancelled during the check, nothing is saved even if TMDB then says yes`() = runTest {
        val answer = CompletableDeferred<Response<Unit>>()
        coEvery { api.checkApiKey(KEY) } coAnswers { answer.await() }
        val provider = provider(backgroundScope)

        val caller = launch { entry(provider).checkAndSave(KEY, TmdbKeySession()) }
        runCurrent()
        caller.cancel()
        answer.complete(Response.success(Unit))
        advanceUntilIdle()

        assertFalse(provider.state.filterNotNull().first().hasUserKey)
        verify(exactly = 0) { trailers.clearCache() }
    }

    @Test
    fun `cancelled after TMDB said yes, the save still finishes`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val store = GatedStore().apply { gate = CompletableDeferred() }
        val provider = TmdbApiKeyProvider(store, backgroundScope, builtInKey = "")

        val caller = launch { entry(provider).checkAndSave(KEY, TmdbKeySession()) }
        runCurrent()
        caller.cancel()
        store.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)
        verify(exactly = 1) { trailers.clearCache() }
    }

    @Test
    fun `a failed write saves nothing and reads as unreachable, a failed remove as false`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val store = GatedStore().apply { fail = true }
        val provider = TmdbApiKeyProvider(store, backgroundScope, builtInKey = "")
        val entry = entry(provider)
        provider.state.filterNotNull().first()

        assertEquals(TmdbKeyCheck.UNREACHABLE, entry.checkAndSave(KEY, TmdbKeySession()))
        assertFalse(entry.remove(TmdbKeySession(), shownKey = ""))
        verify(exactly = 0) { trailers.clearCache() }
    }

    @Test
    fun `a second check waits for the first`() = runTest {
        val firstAnswer = CompletableDeferred<Response<Unit>>()
        coEvery { api.checkApiKey("first") } coAnswers { firstAnswer.await() }
        coEvery { api.checkApiKey("second") } returns Response.error(401, "".toResponseBody(null))
        val entry = entry(provider(backgroundScope))

        val first = async { entry.checkAndSave("first", TmdbKeySession()) }
        runCurrent()
        val second = async { entry.checkAndSave("second", TmdbKeySession()) }
        runCurrent()
        coVerify(exactly = 0) { api.checkApiKey("second") }

        firstAnswer.complete(Response.error(401, "".toResponseBody(null)))
        assertEquals(TmdbKeyCheck.REJECTED, first.await())
        assertEquals(TmdbKeyCheck.REJECTED, second.await())
        coVerify(exactly = 1) { api.checkApiKey("second") }
    }

    @Test
    fun `remove drops the saved key and clears the trailer cache`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val provider = provider(backgroundScope)
        val entry = entry(provider)
        entry.checkAndSave(KEY, TmdbKeySession())
        provider.awaitUserKey(KEY)

        assertTrue(entry.remove(TmdbKeySession(), shownKey = KEY))

        assertFalse(provider.state.filterNotNull().first { !it.hasUserKey }.hasKey)
        verify(exactly = 2) { trailers.clearCache() }
    }

    @Test
    fun `a submit queued behind an accepted key in its session is never checked`() = runTest {
        val firstAnswer = CompletableDeferred<Response<Unit>>()
        coEvery { api.checkApiKey(KEY) } coAnswers { firstAnswer.await() }
        val provider = provider(backgroundScope)
        val entry = entry(provider)
        val session = TmdbKeySession()

        val first = async { entry.checkAndSave(KEY, session) }
        runCurrent()
        val queued = async { entry.checkAndSave(OTHER_KEY, session) }
        runCurrent()
        firstAnswer.complete(Response.success(Unit))

        assertEquals(TmdbKeyCheck.ACCEPTED, first.await())
        assertNull(queued.await())
        assertEquals(TmdbKeySession.State.SAVED, session.current)
        coVerify(exactly = 0) { api.checkApiKey(OTHER_KEY) }
        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)
    }

    @Test
    fun `a closed session or an unwanted submit sends nothing to TMDB`() = runTest {
        val entry = entry(provider(backgroundScope))
        val closed = TmdbKeySession().apply { close() }

        assertNull(entry.checkAndSave(KEY, closed))
        assertNull(entry.checkAndSave(KEY, TmdbKeySession()) { false })
        assertFalse(entry.remove(closed, shownKey = ""))

        coVerify(exactly = 0) { api.checkApiKey(any()) }
        verify(exactly = 0) { trailers.clearCache() }
    }

    @Test
    fun `a session closed during the check still saves an accepted key and stays closed`() = runTest {
        val answer = CompletableDeferred<Response<Unit>>()
        coEvery { api.checkApiKey(KEY) } coAnswers { answer.await() }
        val provider = provider(backgroundScope)
        val session = TmdbKeySession()

        val check = async { entry(provider).checkAndSave(KEY, session) }
        runCurrent()
        session.close()
        answer.complete(Response.success(Unit))

        assertEquals(TmdbKeyCheck.ACCEPTED, check.await())
        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)
        assertEquals(TmdbKeySession.State.CLOSED, session.current)
    }

    @Test
    fun `remove ends its session, and never removes a key it did not show`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val provider = provider(backgroundScope)
        val entry = entry(provider)

        // The phone's key was accepted first; the TV's Remove queued behind it changes nothing.
        val saving = TmdbKeySession()
        entry.checkAndSave(KEY, saving)
        assertFalse(entry.remove(saving, shownKey = KEY))
        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)

        // A panel that showed an older key (the phone of a just-closed panel saved this one meanwhile).
        assertFalse(entry.remove(TmdbKeySession(), shownKey = OTHER_KEY))
        assertEquals(KEY, provider.awaitUserKey(KEY).userKey)

        val removing = TmdbKeySession()
        assertTrue(entry.remove(removing, shownKey = KEY))
        assertEquals(TmdbKeySession.State.CLOSED, removing.current)
        assertNull(entry.checkAndSave(KEY, removing))
    }

    @Test
    fun `closing a saved session keeps it saved`() {
        val session = TmdbKeySession()
        session.saved()
        session.close()
        assertEquals(TmdbKeySession.State.SAVED, session.current)
    }

    @Test
    fun `the check is GET configuration with the candidate key, never cached`() {
        val server = MockWebServer()
        server.start()
        try {
            val tmdb = Retrofit.Builder()
                .baseUrl(server.url("/3/"))
                .client(OkHttpClient.Builder().addInterceptor(TmdbNoKeyInterceptor()).build())
                .addConverterFactory(NetworkModule.provideWireConverterFactory(NetworkModule.provideWireJson()))
                .build()
                .create(TmdbApi::class.java)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"images":{}}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status_code":7}"""))

            assertEquals(200, runBlocking { tmdb.checkApiKey(KEY).code() })
            assertEquals(401, runBlocking { tmdb.checkApiKey("wrong").code() })

            val request = server.takeRequest()
            assertEquals("/3/configuration?api_key=$KEY", request.path)
            assertEquals("no-store", request.getHeader("Cache-Control"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the key field drops whitespace and stops at 32`() {
        assertEquals(KEY, tmdbKeyInput(" ${KEY.chunked(8).joinToString(" ")}\n"))
        assertEquals(KEY, tmdbKeyInput(KEY + "ffff"))
        assertEquals("", tmdbKeyInput(" \t"))
    }

    @Test
    fun `the row shows the last four characters of the key requests send`() {
        assertNull(TmdbKeyState(userKey = "", effectiveKey = "").savedKeyEnding())
        assertEquals("e0a8", TmdbKeyState(userKey = KEY, effectiveKey = KEY).savedKeyEnding())
        // A private build with no user key reads its build key as saved (Spec 52 P4).
        assertEquals("1234", TmdbKeyState(userKey = "", effectiveKey = "build-key-1234").savedKeyEnding())
    }

    private fun entry(provider: TmdbApiKeyProvider) = TmdbKeyEntry(api, provider, trailers)

    /** A key store whose writes wait on [gate] (when set) and throw while [fail]. */
    private class GatedStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override val data = current
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate?.await()
            if (fail) throw IOException("disk full")
            return transform(current.value).also { current.value = it }
        }
    }

    private fun provider(scope: CoroutineScope) = TmdbApiKeyProvider(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmpFolder.root, "tmdb_api_key.preferences_pb") }),
        scope,
        builtInKey = ""
    )

    private suspend fun TmdbApiKeyProvider.awaitUserKey(expected: String): TmdbKeyState =
        state.filterNotNull().first { it.userKey == expected }

    private companion object {
        const val KEY = "4c1e9a7b2f6d08e35a9c1b7f42d6e0a8"
        const val OTHER_KEY = "0123456789abcdef0123456789abcdef"
    }
}
