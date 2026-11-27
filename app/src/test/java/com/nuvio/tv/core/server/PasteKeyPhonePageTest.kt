package com.nuvio.tv.core.server

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 106 K: a panel's paste page opens per session, ends on close or after its lifetime, and checks one send at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteKeyPhonePageTest {

    private val servers = mutableListOf<PasteKeyServer>()
    private var submit: ((PasteKeyServer, String) -> PasteCheck)? = null
    private val checked = mutableListOf<String>()

    private fun start(s: (PasteKeyServer, String) -> PasteCheck): StartedPasteKeyServer {
        submit = s
        val server = PasteKeyServer("0123456789abcdef0123456789abcdef", { "" }, s, 1_024, "127.0.0.1", 0)
        servers += server
        return StartedPasteKeyServer(server, "http://tv/p/${servers.size}/")
    }

    @Test
    fun `open shows the address, a reopen ends the old page, close and the lifetime end it`() = runTest {
        val page = PasteKeyPhonePage(backgroundScope, lifetime = 10.minutes)
        page.open(::start) { value, _ -> checked += value; PasteCheck.Saved }
        assertEquals("http://tv/p/1/", page.url.value)

        val first = submit!!
        page.open(::start) { value, _ -> checked += value; PasteCheck.Saved }
        assertTrue(servers[0].isClosed)
        assertEquals("http://tv/p/2/", page.url.value)
        assertEquals(PasteCheck.Closed, first(servers[0], "late"))
        assertEquals(PasteCheck.Saved, submit!!(servers[1], "fresh"))
        assertEquals(listOf("fresh"), checked)

        advanceTimeBy(10.minutes.inWholeMilliseconds + 1)
        runCurrent()
        assertNull(page.url.value)
        assertTrue(servers[1].isClosed)

        page.open(::start) { _, _ -> PasteCheck.Saved }
        page.close()
        assertNull(page.url.value)
        assertTrue(servers[2].isClosed)
    }

    @Test
    fun `no network or no free port shows no address`() = runTest {
        val page = PasteKeyPhonePage(backgroundScope)
        page.open({ null }) { _, _ -> PasteCheck.Saved }
        assertNull(page.url.value)
    }

    @Test
    fun `sends run one at a time, a check sees its page close, and a send queued behind it answers Closed unchecked`() = runTest {
        val page = PasteKeyPhonePage(backgroundScope)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstChecking = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var firstStillWanted: Boolean? = null
        page.open(::start) { value, stillWanted ->
            order += "start $value"
            if (value == "first") {
                firstChecking.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                firstStillWanted = stillWanted()
            }
            order += "end $value"
            if (stillWanted()) PasteCheck.Saved else PasteCheck.Closed
        }
        val server = servers.single()
        val send = submit!!
        var firstAnswer: PasteCheck? = null
        var secondAnswer: PasteCheck? = null
        val first = Thread { firstAnswer = send(server, "first") }.apply { start() }
        assertTrue(firstChecking.await(5, TimeUnit.SECONDS))
        val second = Thread { secondAnswer = send(server, "second") }.apply { start() }
        awaitBlocked(second)
        assertEquals(listOf("start first"), order.toList())

        page.close()
        releaseFirst.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(false, firstStillWanted)
        assertEquals(PasteCheck.Closed, firstAnswer)
        assertEquals(PasteCheck.Closed, secondAnswer)
        assertEquals(listOf("start first", "end first"), order.toList())
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
