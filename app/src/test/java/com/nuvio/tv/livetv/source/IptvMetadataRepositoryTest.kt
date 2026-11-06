package com.nuvio.tv.livetv.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class IptvMetadataRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private var failing = false
    private var channelsBody: String? = null
    private val io = UnconfinedTestDispatcher()

    private fun resource(name: String) = javaClass.getResourceAsStream("/livetv/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (failing) return MockResponse().setResponseCode(500)
                    return when (request.path) {
                        "/api/channels.json" -> MockResponse().setBody(channelsBody ?: resource("iptv-org-channels-trimmed.json"))
                        "/api/feeds.json" -> MockResponse().setBody(resource("iptv-org-feeds-trimmed.json"))
                        "/api/blocklist.json" -> MockResponse().setBody(resource("iptv-org-blocklist-trimmed.json"))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repo() = IptvMetadataRepository(
        tmp.root,
        SourceFetcher(OkHttpClient(), io),
        { now },
        io,
        server.url("/api/").toString()
    )

    @Test
    fun `fetches, caches and refetches only after three days`() = runTest {
        val repo = repo()
        assertTrue(repo.refreshIfStale())
        assertEquals(11, repo.metadata.value!!.channels.size)
        assertEquals(3, server.requestCount)

        now += IptvMetadataRepository.MAX_AGE_MS - 1
        assertFalse(repo.refreshIfStale())
        assertEquals(3, server.requestCount)

        val cold = repo()
        cold.load()
        assertEquals(repo.metadata.value, cold.metadata.value)
    }

    @Test
    fun `a failed refresh keeps the old metadata`() = runTest {
        val repo = repo()
        assertTrue(repo.refreshIfStale())
        val before = repo.metadata.value
        now += IptvMetadataRepository.MAX_AGE_MS
        failing = true
        assertFalse(repo.refreshIfStale())
        assertEquals(before, repo.metadata.value)

        val cold = repo()
        cold.load()
        assertEquals(before, cold.metadata.value)

        // A failure is retried after an hour, not on every call.
        failing = false
        val requests = server.requestCount
        assertFalse(repo.refreshIfStale())
        assertEquals(requests, server.requestCount)
        now += IptvMetadataRepository.RETRY_MS
        assertTrue(repo.refreshIfStale())
    }

    @Test
    fun `a wrong shape or an empty channel list keeps the old metadata`() = runTest {
        val repo = repo()
        assertTrue(repo.refreshIfStale())
        val before = repo.metadata.value
        now += IptvMetadataRepository.MAX_AGE_MS
        channelsBody = "{\"error\":\"moved\"}"
        assertFalse(repo.refreshIfStale())
        assertEquals(before, repo.metadata.value)

        // Blocklist nsfw stubs alone must not pass for a channel list.
        now += IptvMetadataRepository.RETRY_MS
        channelsBody = "[]"
        assertFalse(repo.refreshIfStale())
        assertEquals(before, repo.metadata.value)
    }
}
