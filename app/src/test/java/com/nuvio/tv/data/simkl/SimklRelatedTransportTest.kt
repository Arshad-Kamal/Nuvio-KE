package com.nuvio.tv.data.simkl

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 106 S4a: SimklModule's client for More like this reads `/redirect`'s 301 and never follows it. */
class SimklRelatedTransportTest {

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/redirect" -> MockResponse().setResponseCode(301).addHeader("Location", "/movies/7/page")
                "/movies/7" -> MockResponse().setResponseCode(200)
                    .setBody("""{"similar":[{"title":"Heat","type":"movie","ids":{"simkl":8,"imdb":"tt0113277"}}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `the redirect is read, not followed`() = runTest {
        val config = TEST_CONFIG.copy(baseUrl = server.url("/").toString().trimEnd('/'))
        val cards = SimklModule.provideSimklRelated(OkHttpClient(), config).related("tt1", "movie", SimklAnimeSeasons.PER_SHOW)

        assertEquals(listOf("tt0113277"), cards.map { it.id })
        val paths = List(server.requestCount) { server.takeRequest().path?.substringBefore('?') }
        assertEquals(listOf("/redirect", "/movies/7"), paths)
    }
}
