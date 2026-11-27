package com.nuvio.tv.core.server

import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeRules
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
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
            token = TOKEN,
            currentRulesProvider = { rules.get() },
            updateRules = { transform -> rules.updateAndGet { transform(it) } }
        )

        val response = withBadgeSource(BadgeSourceServer(NanoHTTPD.Response.Status.OK, fusionPayload)) { url ->
            server.serve(FakePostSession(importBody(url), "/b/$TOKEN/api/badges/import"))
        }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, rules.get().imports.size)
        assertTrue(rules.get().imports.first().sourceUrl.endsWith("/badges.json"))
        assertEquals("Dolby Vision", rules.get().imports.first().filters.first().name)
    }

    // Spec 130 (issue #4): the phone import of a real Fusion pack with long patterns saves it.
    @Test
    fun `imports a real Fusion pack fetched over http`() {
        val rules = AtomicReference(StreamBadgeRules())
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { rules.get() },
            updateRules = { transform -> rules.updateAndGet { transform(it) } }
        )
        val frPack = checkNotNull(javaClass.getResource("/stream-badges/fr-badges.json")).readText()

        val response = withBadgeSource(BadgeSourceServer(NanoHTTPD.Response.Status.OK, frPack)) { url ->
            server.serve(FakePostSession(importBody(url), "/b/$TOKEN/api/badges/import"))
        }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(219, rules.get().imports.single().filters.size)
    }

    @Test
    fun `import keeps rule changes made while the fetch was in flight`() {
        val rules = AtomicReference(StreamBadgeRules())
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { rules.get() },
            updateRules = { transform -> rules.updateAndGet { transform(it) } }
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
            server.serve(FakePostSession(importBody(url), "/b/$TOKEN/api/badges/import"))
        }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(2, rules.get().imports.size)
        assertTrue(rules.get().imports.any { it.sourceUrl == other.sourceUrl })
    }

    @Test
    fun `import failures return only the generic message`() {
        var saved = false
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> saved = true; transform(StreamBadgeRules()) }
        )
        val sources = listOf(
            BadgeSourceServer(NanoHTTPD.Response.Status.OK, "{not json"),
            BadgeSourceServer(NanoHTTPD.Response.Status.NOT_FOUND, "internal detail")
        )

        sources.forEach { source ->
            val response = withBadgeSource(source) { url ->
                server.serve(FakePostSession(importBody(url), "/b/$TOKEN/api/badges/import"))
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
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> saved = true; transform(StreamBadgeRules()) }
        )

        val blank = server.serve(FakePostSession(importBody("  "), "/b/$TOKEN/api/badges/import"))
        val ftp = server.serve(FakePostSession(importBody("ftp://x.example/b.json"), "/b/$TOKEN/api/badges/import"))

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
            token = TOKEN,
            currentRulesProvider = { rules.get() },
            updateRules = { transform -> rules.updateAndGet { transform(it) } }
        )

        val active = server.serve(FakePostSession(importBody(two.sourceUrl), "/b/$TOKEN/api/badges/active"))
        val activeRules = rules.get()
        val delete = server.serve(FakePostSession(importBody(one.sourceUrl), "/b/$TOKEN/api/badges/delete"))
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

    // Spec 130 F4, end to end over the real store as the ViewModel wires it: two deletes at once both stick.
    @Test
    fun `concurrent deletes against the store both stick`() {
        val factory = io.mockk.mockk<com.nuvio.tv.data.local.ProfileDataStoreFactory>()
        io.mockk.every { factory.get(any(), any()) } returns com.nuvio.tv.data.local.YieldingPreferencesDataStore()
        val profileManager = io.mockk.mockk<com.nuvio.tv.core.profile.ProfileManager>()
        io.mockk.every { profileManager.activeProfileId } returns kotlinx.coroutines.flow.MutableStateFlow(1)
        val store = com.nuvio.tv.data.local.StreamBadgeSettingsDataStore(factory, profileManager)
        fun import(name: String) = StreamBadgeImport(
            sourceUrl = "https://example.com/$name.json",
            filters = listOf(StreamBadgeFilter(name = name, pattern = name))
        )
        kotlinx.coroutines.runBlocking {
            store.setStreamBadgeRules(StreamBadgeRules(imports = listOf(import("A"), import("B"), import("C"))))
        }
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { kotlinx.coroutines.runBlocking { store.settings.first().rules } },
            updateRules = { transform -> kotlinx.coroutines.runBlocking { store.updateStreamBadgeRules(transform) } }
        )
        val start = java.util.concurrent.CountDownLatch(1)

        val deletes = listOf("A", "B").map { name ->
            Thread {
                start.await()
                server.serve(FakePostSession(importBody("https://example.com/$name.json"), "/b/$TOKEN/api/badges/delete"))
            }.apply { start() }
        }
        start.countDown()
        deletes.forEach { it.join() }

        val left = kotlinx.coroutines.runBlocking { store.settings.first().rules }
        assertEquals(listOf("https://example.com/C.json"), left.imports.map { it.sourceUrl })
    }

    @Test
    fun `settings POST is gone and saves nothing`() {
        var saved: StreamBadgeRules? = null
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> transform(StreamBadgeRules()).also { saved = it } }
        )

        val response = server.serve(FakePostSession("""{"badgePlacement":"TOP"}""", "/b/$TOKEN/api/settings"))

        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
        assertNull(saved)
    }

    @Test
    fun `malformed or oversized body is rejected without saving`() {
        var saved: StreamBadgeRules? = null
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> transform(StreamBadgeRules()).also { saved = it } }
        )

        val malformed = server.serve(FakePostSession("{oops", "/b/$TOKEN/api/badges/active"))
        val oversized = server.serve(
            FakePostSession(
                importBody("https://example.com/one.json"),
                "/b/$TOKEN/api/badges/active",
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
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> transform(StreamBadgeRules()) }
        )

        val response = server.serve(FakeGetSession("/b/$TOKEN/api/settings", host = "attacker.example:8091"))

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
            token = TOKEN,
            currentRulesProvider = { rules },
            updateRules = { transform -> transform(StreamBadgeRules()) }
        )

        val response = server.serve(FakeGetSession("/b/$TOKEN/api/settings"))
        val body = response.bodyText()

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body.contains("\"isActive\":true"))
        assertTrue(body.contains("\"isActive\":false"))
        assertFalse(body.contains("badgePlacement"))
        assertFalse(body.contains("showFileSizeBadges"))
    }

    @Test
    fun `routes answer only under this session's token`() {
        val rules = AtomicReference(StreamBadgeRules())
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { rules.get() },
            updateRules = { transform -> rules.updateAndGet { transform(it) } }
        )
        val stale = FakeUploadSession(
            "/b/${"0".repeat(32)}/api/badges/delete",
            kind = null,
            body = importBody("https://example.com/one.json").toByteArray()
        )

        val open = server.serve(FakeGetSession("/b/$TOKEN/api/settings"))
        val noToken = server.serve(FakeGetSession("/api/settings"))
        val staleAnswer = server.serve(stale)

        assertEquals(NanoHTTPD.Response.Status.OK, open.status)
        assertEquals("no-referrer", open.getHeader("Referrer-Policy"))
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, noToken.status)
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, staleAnswer.status)
        assertEquals(0, stale.stream.available())
        assertTrue(server.url("192.0.2.1").endsWith("/b/$TOKEN/"))
    }

    @Test
    fun `page is served under the token and calls only relative paths`() {
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> transform(StreamBadgeRules()) }
        )

        val page = server.serve(FakeGetSession("/b/$TOKEN/"))

        assertEquals(NanoHTTPD.Response.Status.OK, page.status)
        assertNoAbsoluteSameOriginPath(page.bodyText())
    }

    // Spec 130 F8: opened without the trailing slash, the page's relative logo.png resolved to /b/logo.png.
    @Test
    fun `page url without the trailing slash redirects to the slash form`() {
        val server = StreamBadgeConfigServer(
            token = TOKEN,
            currentRulesProvider = { StreamBadgeRules() },
            updateRules = { transform -> transform(StreamBadgeRules()) }
        )

        val bare = server.serve(FakeGetSession("/b/$TOKEN"))
        val wrongToken = server.serve(FakeGetSession("/b/${"0".repeat(32)}"))

        assertEquals(NanoHTTPD.Response.Status.FOUND, bare.status)
        assertEquals("/b/$TOKEN/", bare.getHeader("Location"))
        assertEquals("no-store", bare.getHeader("Cache-Control"))
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, wrongToken.status)
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
