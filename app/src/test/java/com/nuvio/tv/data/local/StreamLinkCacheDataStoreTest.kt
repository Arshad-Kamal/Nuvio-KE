package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.security.MessageDigest

/**
 * T1c pins for the stream-link cache: full payload round-trip through the
 * SHA-256 content key, out-of-window entries kept for any-age reads, self-healing drop of
 * unusable entries, explicit
 * evict of dead links, and eviction ordering derived from the
 * CACHED_AT_MS_REGEX extraction (entries without a parsable cachedAtMs evict
 * first).
 */
class StreamLinkCacheDataStoreTest {

    @Test
    fun `save then getValid round-trips a full link`() = runTest {
        val harness = harness()

        harness.store.save(
            contentKey = "tt1:series:1:2",
            url = "https://cdn.example/s.mp4",
            streamName = "CDN 1080p",
            headers = mapOf("User-Agent" to "Nuvio"),
            filename = "s01e02.mkv",
            videoHash = "abc123",
            videoSize = 1_234L,
            infoHash = "deadbeef",
            fileIdx = 3,
            sources = listOf("CDN", "P2P"),
            bingeGroup = "group-a",
            contentLanguage = "eng",
            year = "2024"
        )

        val cached = harness.store.getValid("tt1:series:1:2", maxAgeMs = 60_000L)

        requireNotNull(cached)
        assertEquals("https://cdn.example/s.mp4", cached.url)
        assertEquals("CDN 1080p", cached.streamName)
        assertEquals(mapOf("User-Agent" to "Nuvio"), cached.headers)
        assertEquals("s01e02.mkv", cached.filename)
        assertEquals("abc123", cached.videoHash)
        assertEquals(1_234L, cached.videoSize)
        assertEquals("deadbeef", cached.infoHash)
        assertEquals(3, cached.fileIdx)
        assertEquals(listOf("CDN", "P2P"), cached.sources)
        assertEquals("group-a", cached.bingeGroup)
        assertEquals("eng", cached.contentLanguage)
        assertEquals("2024", cached.year)
        assertTrue(cached.cachedAtMs > 0L)
    }

    @Test
    fun `entry past the window reads as null but stays for an any-age read`() = runTest {
        val harness = harness()
        harness.seedPayload(
            "tt1",
            payload(cachedAtMs = System.currentTimeMillis() - 120_000L)
        )

        assertNull(harness.store.getValid("tt1", maxAgeMs = 60_000L))
        assertEquals("https://cdn.example/s.mp4", harness.store.getValid("tt1", maxAgeMs = Long.MAX_VALUE)?.url)
    }

    @Test
    fun `unusable entry is dropped from the store on read`() = runTest {
        val harness = harness()
        harness.seedPayload("tt1", payload(cachedAtMs = 0L))
        harness.seedPayload("tt2", payload(url = ""))

        assertNull(harness.store.getValid("tt1", maxAgeMs = Long.MAX_VALUE))
        assertNull(harness.rawValue("tt1"))
        assertNull(harness.store.getValid("tt2", maxAgeMs = Long.MAX_VALUE))
        assertNull(harness.rawValue("tt2"))
    }

    @Test
    fun `torrent entry without a url but with infoHash stays valid`() = runTest {
        val harness = harness()
        harness.seedPayload(
            "tt1",
            payload(url = "", infoHash = "deadbeef")
        )

        val cached = harness.store.getValid("tt1", maxAgeMs = 60_000L)

        requireNotNull(cached)
        assertEquals("deadbeef", cached.infoHash)
    }

    @Test
    fun `evict drops a dead link so the next play re-resolves`() = runTest {
        val harness = harness()
        harness.store.save("tt1", "https://cdn.example/s.mp4", "CDN 1080p", null)

        harness.store.evict("tt1")

        assertNull(harness.rawValue("tt1"))
        assertNull(harness.store.getValid("tt1", maxAgeMs = 60_000L))
    }

    @Test
    fun `entry without a parsable cachedAtMs evicts first`() = runTest {
        val harness = harness()
        // Hand-written payload with no cachedAtMs: the regex extraction misses,
        // the entry sorts oldest, and the cap breach must take it.
        harness.seedPayload("zz-foreign", """{"url":"https://x.test/a","streamName":"X"}""")
        repeat(500) { index ->
            harness.store.save("filler$index", "https://x.test/$index", "S$index", null)
        }

        assertNull(harness.rawValue("zz-foreign"))
        assertEquals(500, harness.streamKeyCount())
    }

    private fun payload(
        cachedAtMs: Long = System.currentTimeMillis(),
        url: String = "https://cdn.example/s.mp4",
        infoHash: String? = null
    ): String = JSONObject()
        .put("url", url)
        .put("streamName", "CDN 1080p")
        .put("cachedAtMs", cachedAtMs)
        .put("headers", JSONObject())
        .apply { infoHash?.let { put("infoHash", it) } }
        .toString()

    private fun harness(): Harness {
        val backing = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns backing
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(StreamLinkCacheDataStore(factory, profileManager), backing)
    }

    private class Harness(val store: StreamLinkCacheDataStore, val backing: TestPreferencesDataStore) {
        /** Mirrors the store's public key derivation: SHA-256 hex of the content key. */
        private fun prefKey(contentKey: String) = stringPreferencesKey(
            "stream_link_" + MessageDigest.getInstance("SHA-256")
                .digest(contentKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        )

        suspend fun seedPayload(contentKey: String, payload: String) {
            backing.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[prefKey(contentKey)] = payload
                }.toPreferences()
            }
        }

        fun rawValue(contentKey: String): String? = backing.value[prefKey(contentKey)]

        fun streamKeyCount(): Int =
            backing.value.asMap().keys.count { it.name.startsWith("stream_link_") }
    }
}
