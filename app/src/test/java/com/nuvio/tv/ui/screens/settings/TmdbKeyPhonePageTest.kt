package com.nuvio.tv.ui.screens.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.server.FakeUploadSession
import com.nuvio.tv.core.server.TmdbKeyAnswer
import com.nuvio.tv.core.server.TmdbKeyServer
import com.nuvio.tv.core.server.newSessionToken
import com.nuvio.tv.core.tmdb.TmdbApiKeyProvider
import com.nuvio.tv.core.tmdb.TmdbKeyEntry
import com.nuvio.tv.core.tmdb.TmdbKeySession
import com.nuvio.tv.data.remote.api.TmdbApi
import fi.iki.elonen.NanoHTTPD
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * Spec 52 S3: the phone page per panel session, its lifetime, and phone sends through the real
 * [TmdbKeyEntry] (one check at a time, the session re-read after the lock): the answers a queued or
 * late send gets, and that a cleared ViewModel cannot cut an accepted key from its save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TmdbKeyPhonePageTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // The key store runs on real threads: phone sends block on it from server threads.
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = mockk<TmdbApi>()
    private val provider by lazy {
        TmdbApiKeyProvider(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(tmpFolder.root, "tmdb_api_key.preferences_pb") }),
            storeScope,
            builtInKey = ""
        )
    }
    private val entry by lazy { TmdbKeyEntry(api, provider, mockk(relaxed = true)) }
    private val servers = mutableListOf<TmdbKeyServer>()
    private val saved = mutableListOf<TmdbKeySession>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        storeScope.cancel()
    }

    private fun page(scope: CoroutineScope): TmdbKeyPhonePage = TmdbKeyPhonePage(
        scope = scope,
        start = { submit ->
            TmdbKeyServer(newSessionToken(), { "" }, submit, "127.0.0.1", 0, stopGraceMs = 0).let { server ->
                servers += server
                StartedKeyServer(server, "http://10.0.0.2:8120/k/${servers.size}/")
            }
        },
        checkAndSave = entry::checkAndSave,
        onSaved = { saved += it }
    )

    /** `/k/<token>/key` of [this] page (taken from its QR address; these servers are never started). */
    private fun TmdbKeyServer.keyRoute(): String = "/k/" + url("10.0.0.2").substringAfter("/k/") + "key"

    /** A send to [server]'s key route, as its handler thread serves it. */
    private fun send(server: TmdbKeyServer, key: String): NanoHTTPD.Response =
        server.serve(FakeUploadSession(server.keyRoute(), kind = null, body = key.toByteArray()))

    /** [send] on a thread of its own, as NanoHTTPD's handler threads run sends side by side. */
    private inner class SendThread(server: TmdbKeyServer, key: String) {
        @Volatile private var response: NanoHTTPD.Response? = null
        private val thread = Thread { response = send(server, key) }.apply { start() }

        /** Waits until the send is parked: runBlocking suspended on the entry's lock, the only wait before a check. */
        fun awaitQueued() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // runBlocking parks with parkNanos: TIMED_WAITING.
            while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
                check(System.nanoTime() < deadline) { "the send never queued (state ${thread.state})" }
                Thread.sleep(5)
            }
        }

        fun result(): NanoHTTPD.Response? {
            thread.join(10_000)
            return response
        }
    }

    /** TMDB's answer for [key] waits on the returned deferred; [called] counts down once it was asked. */
    private fun pendingCheck(key: String, called: CountDownLatch): CompletableDeferred<Response<Unit>> {
        val answer = CompletableDeferred<Response<Unit>>()
        coEvery { api.checkApiKey(key) } coAnswers {
            called.countDown()
            answer.await()
        }
        return answer
    }

    private fun storedKey(): String = runBlocking {
        withTimeout(5_000) { provider.state.filterNotNull().first { it.hasUserKey }.userKey }
    }

    @Test
    fun `one page per session, ended by close and by its lifetime`() = runTest {
        val page = page(backgroundScope)
        val first = TmdbKeySession()
        page.open(first)
        assertEquals("http://10.0.0.2:8120/k/1/", page.url.value)

        val second = TmdbKeySession()
        page.open(second)
        assertTrue(servers[0].isClosed)
        assertEquals("http://10.0.0.2:8120/k/2/", page.url.value)

        advanceTimeBy(TmdbKeyPhonePage.LIFETIME - 1.milliseconds)
        assertFalse(servers[1].isClosed)
        advanceTimeBy(2.milliseconds)
        assertNull(page.url.value)
        assertTrue(servers[1].isClosed)
        // The panel (its TV field) outlives the page.
        assertEquals(TmdbKeySession.State.OPEN, second.current)

        page.open(TmdbKeySession())
        page.close()
        assertNull(page.url.value)
        assertTrue(servers[2].isClosed)
    }

    @Test
    fun `an accepted send answers 200, saves the key and tells its session on the scope`() = runTest {
        coEvery { api.checkApiKey(KEY) } returns Response.success(Unit)
        val page = page(backgroundScope)
        val session = TmdbKeySession()
        page.open(session)

        val response = send(servers[0], KEY)

        assertEquals(TmdbKeyAnswer.SAVED.status, response.status)
        assertEquals(KEY, storedKey())
        assertTrue(saved.isEmpty())
        runCurrent()
        assertSame(session, saved.single())
    }

    @Test
    fun `rejected and unreachable sends keep the panel and save nothing`() = runTest {
        val page = page(backgroundScope)
        page.open(TmdbKeySession())
        coEvery { api.checkApiKey(KEY) } returns Response.error(401, "".toResponseBody(null))
        assertEquals(TmdbKeyAnswer.REJECTED.status, send(servers[0], KEY).status)
        coEvery { api.checkApiKey(KEY) } returns Response.error(500, "".toResponseBody(null))
        assertEquals(TmdbKeyAnswer.UNREACHABLE.status, send(servers[0], KEY).status)
        runCurrent()
        assertTrue(saved.isEmpty())
        assertFalse(runBlocking { provider.state.filterNotNull().first() }.hasUserKey)
    }

    @Test
    fun `a send queued behind the TV's accepted check answers 409 and is never checked`() = runTest {
        val tvAsked = CountDownLatch(1)
        val tvAnswer = pendingCheck(KEY, tvAsked)
        val page = page(backgroundScope)
        val session = TmdbKeySession()
        page.open(session)

        val tv = Thread { runBlocking { entry.checkAndSave(KEY, session) } }.apply { start() }
        assertTrue(tvAsked.await(5, TimeUnit.SECONDS))
        val phone = SendThread(servers[0], OTHER_KEY)
        phone.awaitQueued()
        tvAnswer.complete(Response.success(Unit))
        tv.join(5_000)

        assertEquals(TmdbKeyAnswer.ALREADY_SAVED.status, phone.result()?.status)
        coVerify(exactly = 0) { api.checkApiKey(OTHER_KEY) }
        assertEquals(KEY, storedKey())
    }

    @Test
    fun `when the panel closes, the send being checked still gets 200 and a queued one 410 unchecked`() = runTest {
        val firstAsked = CountDownLatch(1)
        val firstAnswer = pendingCheck(KEY, firstAsked)
        val page = page(backgroundScope)
        val session = TmdbKeySession()
        page.open(session)

        val first = SendThread(servers[0], KEY)
        assertTrue(firstAsked.await(5, TimeUnit.SECONDS))
        val queued = SendThread(servers[0], OTHER_KEY)
        queued.awaitQueued()
        // As the ViewModel closes a panel: the session, then its page.
        session.close()
        page.close()
        firstAnswer.complete(Response.success(Unit))

        assertEquals(TmdbKeyAnswer.SAVED.status, first.result()?.status)
        assertEquals(TmdbKeyAnswer.CLOSED.status, queued.result()?.status)
        coVerify(exactly = 0) { api.checkApiKey(OTHER_KEY) }
        assertEquals(KEY, storedKey())
    }

    @Test
    fun `when only the page expires, a queued send answers 410 unchecked`() = runTest {
        val firstAsked = CountDownLatch(1)
        val firstAnswer = pendingCheck(KEY, firstAsked)
        val page = page(backgroundScope)
        val session = TmdbKeySession()
        page.open(session)

        val first = SendThread(servers[0], KEY)
        assertTrue(firstAsked.await(5, TimeUnit.SECONDS))
        val queued = SendThread(servers[0], OTHER_KEY)
        queued.awaitQueued()
        advanceTimeBy(TmdbKeyPhonePage.LIFETIME + 1.milliseconds)
        firstAnswer.complete(Response.error(401, "".toResponseBody(null)))

        assertEquals(TmdbKeyAnswer.REJECTED.status, first.result()?.status)
        assertEquals(TmdbKeyAnswer.CLOSED.status, queued.result()?.status)
        coVerify(exactly = 0) { api.checkApiKey(OTHER_KEY) }
        assertEquals(TmdbKeySession.State.OPEN, session.current)
    }

    @Test
    fun `a cleared ViewModel mid-check still answers 200 and keeps the key`() {
        val asked = CountDownLatch(1)
        val answer = pendingCheck(KEY, asked)
        val viewModelScope = CoroutineScope(Job())
        val page = page(viewModelScope)
        val session = TmdbKeySession()
        page.open(session)

        val phone = SendThread(servers[0], KEY)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        // onCleared: the scope ends, the session and page close.
        viewModelScope.cancel()
        session.close()
        page.close()
        answer.complete(Response.success(Unit))

        assertEquals(TmdbKeyAnswer.SAVED.status, phone.result()?.status)
        assertEquals(KEY, storedKey())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `each page has its own token`() = runTest {
        val page = page(backgroundScope)
        page.open(TmdbKeySession())
        page.open(TmdbKeySession())
        assertNotEquals(servers[0].keyRoute(), servers[1].keyRoute())
        // A send to the first page's address on the second server: another token, 404.
        val stale = servers[1].serve(FakeUploadSession(servers[0].keyRoute(), kind = null, body = KEY.toByteArray()))
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, stale.status)
    }

    private companion object {
        const val KEY = "4c1e9a7b2f6d08e35a9c1b7f42d6e0a8"
        const val OTHER_KEY = "0123456789abcdef0123456789abcdef"
    }
}
