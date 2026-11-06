package com.nuvio.tv.core.server

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeRules
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBadgeConfigServerTest {

    private val fusionPayload = """
        {
          "filters": [
            {
              "name": "Dolby Vision",
              "pattern": "DV",
              "imageURL": "https://cdn.example/dv.png"
            }
          ],
          "groups": []
        }
    """.trimIndent()

    /** Loopback stand-in for the remote badge JSON host the import fetches from. */
    private class BadgeSourceServer(
        private val status: NanoHTTPD.Response.Status,
        private val payload: String,
        private val onRequest: () -> Unit = {}
    ) : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            onRequest()
            return newFixedLengthResponse(status, "application/json", payload)
        }

        val url: String get() = "http://127.0.0.1:$listeningPort/badges.json"
    }

    private fun <T> withBadgeSource(source: BadgeSourceServer, block: (String) -> T): T {
        source.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        return try {
            block(source.url)
        } finally {
            source.stop()
        }
    }

    private fun importBody(sourceUrl: String): String =
        buildJsonObject { put("sourceUrl", sourceUrl) }.toString()

    @Test
    fun `imports badge rules fetched from the source url`() {
        val rules = AtomicReference(StreamBadgeRules())
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { rules.get() },
            onRulesChanged = { rules.set(it) }
        )

        val response = withBadgeSource(BadgeSourceServer(NanoHTTPD.Response.Status.OK, fusionPayload)) { url ->
            server.serve(FakePostSession(importBody(url), "/api/badges/import"))
        }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, rules.get().imports.size)
        assertTrue(rules.get().imports.first().sourceUrl.endsWith("/badges.json"))
        assertEquals("Dolby Vision", rules.get().imports.first().filters.first().name)
    }

    @Test
    fun `import keeps rule changes made while the fetch was in flight`() {
        val rules = AtomicReference(StreamBadgeRules())
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { rules.get() },
            onRulesChanged = { rules.set(it) }
        )
        val other = StreamBadgeImport(
            sourceUrl = "https://example.com/other.json",
            filters = listOf(StreamBadgeFilter(name = "Other", pattern = "Other"))
        )
        // Another import lands while this one is fetching.
        val source = BadgeSourceServer(NanoHTTPD.Response.Status.OK, fusionPayload) {
            rules.set(StreamBadgeRules(imports = listOf(other)))
        }

        val response = withBadgeSource(source) { url ->
            server.serve(FakePostSession(importBody(url), "/api/badges/import"))
        }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(2, rules.get().imports.size)
        assertTrue(rules.get().imports.any { it.sourceUrl == other.sourceUrl })
    }

    @Test
    fun `import failures return only the generic message`() {
        var saved = false
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { StreamBadgeRules() },
            onRulesChanged = { saved = true }
        )
        val sources = listOf(
            BadgeSourceServer(NanoHTTPD.Response.Status.OK, "{not json"),
            BadgeSourceServer(NanoHTTPD.Response.Status.NOT_FOUND, "internal detail")
        )

        sources.forEach { source ->
            val response = withBadgeSource(source) { url ->
                server.serve(FakePostSession(importBody(url), "/api/badges/import"))
            }
            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            assertEquals("""{"error":"Badge import failed."}""", response.bodyText())
        }
        assertEquals(false, saved)
    }

    @Test
    fun `import rejects blank and non-http urls before fetching`() {
        var saved = false
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { StreamBadgeRules() },
            onRulesChanged = { saved = true }
        )

        val blank = server.serve(FakePostSession(importBody("  "), "/api/badges/import"))
        val ftp = server.serve(FakePostSession(importBody("ftp://x.example/b.json"), "/api/badges/import"))

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, blank.status)
        assertEquals("""{"error":"Enter a badge JSON URL."}""", blank.bodyText())
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, ftp.status)
        assertEquals("""{"error":"Badge URL must start with http:// or https://."}""", ftp.bodyText())
        assertEquals(false, saved)
    }

    @Test
    fun `active and delete save the updated rules and echo them with their status`() {
        val one = StreamBadgeImport(
            sourceUrl = "https://example.com/one.json",
            isActive = true,
            filters = listOf(StreamBadgeFilter(name = "One", pattern = "One"))
        )
        val two = StreamBadgeImport(
            sourceUrl = "https://example.com/two.json",
            isActive = false,
            filters = listOf(StreamBadgeFilter(name = "Two", pattern = "Two"))
        )
        val rules = AtomicReference(StreamBadgeRules(imports = listOf(one, two)))
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { rules.get() },
            onRulesChanged = { rules.set(it) }
        )

        val active = server.serve(FakePostSession(importBody(two.sourceUrl), "/api/badges/active"))
        val activeRules = rules.get()
        val delete = server.serve(FakePostSession(importBody(one.sourceUrl), "/api/badges/delete"))
        val deletedRules = rules.get()

        fun expectedBody(status: String, rules: StreamBadgeRules) =
            """{"status":"$status","streamBadgeRules":${configServerJson.encodeToString(StreamBadgeRules.serializer(), rules)}}"""
        assertEquals(NanoHTTPD.Response.Status.OK, active.status)
        assertEquals(expectedBody("saved", activeRules), active.bodyText())
        assertEquals(two.sourceUrl, activeRules.imports.single { it.isActive }.sourceUrl)
        assertEquals(NanoHTTPD.Response.Status.OK, delete.status)
        assertEquals(expectedBody("deleted", deletedRules), delete.bodyText())
        assertEquals(listOf(two.sourceUrl), deletedRules.imports.map { it.sourceUrl })
    }

    @Test
    fun `settings POST is gone and saves nothing`() {
        var saved: StreamBadgeRules? = null
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { StreamBadgeRules() },
            onRulesChanged = { saved = it }
        )

        val response = server.serve(FakePostSession("""{"badgePlacement":"TOP"}""", "/api/settings"))

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        assertNull(saved)
    }

    @Test
    fun `malformed or oversized body is rejected without saving`() {
        var saved: StreamBadgeRules? = null
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { StreamBadgeRules() },
            onRulesChanged = { saved = it }
        )

        val malformed = server.serve(FakePostSession("{oops", "/api/badges/active"))
        val oversized = server.serve(
            FakePostSession(
                importBody("https://example.com/one.json"),
                "/api/badges/active",
                declaredLength = MAX_POST_BODY_BYTES + 1
            )
        )

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, malformed.status)
        assertEquals(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, oversized.status)
        assertNull(saved)
    }

    @Test
    fun `foreign host header is rejected`() {
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { StreamBadgeRules() },
            onRulesChanged = {}
        )

        val response = server.serve(FakeGetSession(host = "attacker.example:8091"))

        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }

    @Test
    fun `serves explicit active state for badge web client`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/one.json",
                    isActive = true,
                    filters = listOf(StreamBadgeFilter(name = "One", pattern = "One"))
                ),
                StreamBadgeImport(
                    sourceUrl = "https://example.com/two.json",
                    isActive = false,
                    filters = listOf(StreamBadgeFilter(name = "Two", pattern = "Two"))
                )
            )
        )
        val server = StreamBadgeConfigServer(
            currentRulesProvider = { rules },
            onRulesChanged = {}
        )

        val response = server.serve(FakeGetSession())
        val body = response.bodyText()

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body.contains("\"isActive\":true"))
        assertTrue(body.contains("\"isActive\":false"))
        assertFalse(body.contains("badgePlacement"))
        assertFalse(body.contains("showFileSizeBadges"))
    }
}
