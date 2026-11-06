package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 33 D4: the per-play network override and the stream qualifier. */
class PerPlayNetworkTest {

    private val url = "https://download.real-debrid.com/d/abc/film.mkv"
    private val twoBy8 = NetworkRow(2, 8, 90.0)
    private val table = ServiceTable(
        NetworkTransport.WIFI, 1_000L, http2 = true,
        rows = listOf(NetworkRow(1, 0, 40.0), twoBy8, NetworkRow(4, 16, 200.0))
    )
    private val tables = ServiceNetworkTables(tables = mapOf("real-debrid.com" to table))

    /** Automatic shape; network values distinct from every row so a pick shows. */
    private val saved = PlayerSettings(
        bufferEngineEnabled = true,
        parallelNetworkEnabled = true,
        bufferBudgetManaged = false,
        nuvioPerformanceModeEnabled = true,
        useParallelConnections = true,
        parallelConnectionCount = 3,
        parallelChunkSizeKb = 12_288,
        enableHttp2 = false,
        bufferSettings = BufferSettings(targetBufferSizeMb = 150, maxBufferMs = 120_000, backBufferDurationMs = 0)
    )

    private val limits2Gb = DeviceLimits(325, 16, perfModeSupported = true, afrSupported = null)

    // 20 GB over 120 min = 22.2 Mbit/s: the 2x bar (42.2 with tolerance) passes 2x8 and 4x16.
    private fun play(
        settings: PlayerSettings = saved,
        with: ServiceNetworkTables = tables,
        streamUrl: String = url,
        sizeBytes: Long? = 20_000_000_000L,
        runtimeMinutes: Int? = 120,
        isTorrent: Boolean = false,
        controllerHttp2: Boolean? = null
    ) = perPlayNetwork(settings, with, streamUrl, sizeBytes, runtimeMinutes, isTorrent, limits2Gb, controllerHttp2)

    @Test
    fun `config mapping round-trips every field`() {
        assertEquals(saved, saved.withPlaybackConfig(saved.toPlaybackConfig()))
        // Every field off its default, so a field the mapping drops shows.
        val config = PlaybackConfig(
            performanceMode = true,
            targetBufferMb = 275,
            minBufferMs = 21_000,
            maxBufferMs = 99_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 7_500,
            backBufferMs = 11_000,
            retainBackBufferFromKeyframe = false,
            parallel = true,
            connections = 4,
            chunkKb = 24_576,
            http2 = true,
            frameRateMatching = FrameRateMatchingMode.START_STOP
        )
        assertEquals(config, PlayerSettings().withPlaybackConfig(config).toPlaybackConfig())
    }

    @Test
    fun `the cheapest row past 2x is applied to a copy, every other field kept`() {
        val result = play()

        assertEquals(twoBy8, result.choice?.row)
        val s = result.settings
        assertTrue(s.useParallelConnections)
        assertEquals(2, s.parallelConnectionCount)
        assertEquals(8 * 1024, s.parallelChunkSizeKb)
        assertEquals(result.choice?.config, s.toPlaybackConfig())
        // Only PlaybackConfig fields moved: putting the saved ones back gives the saved settings.
        assertEquals(saved, s.withPlaybackConfig(saved.toPlaybackConfig()))
        assertFalse(saved.toPlaybackConfig() == s.toPlaybackConfig())
    }

    @Test
    fun `HTTP2 follows the table on a fresh controller and stays put on a running one`() {
        assertTrue(play(controllerHttp2 = null).settings.enableHttp2)
        val running = play(controllerHttp2 = false)
        assertFalse(running.settings.enableHttp2)
        assertFalse(running.choice!!.config.http2)
        // Controller value differs from both the table and the saved setting.
        val h1Table = tables.copy(tables = mapOf("real-debrid.com" to table.copy(http2 = false)))
        assertTrue(play(with = h1Table, controllerHttp2 = true).settings.enableHttp2)
    }

    @Test
    fun `saved settings come back untouched when the rule does not apply`() {
        val managed = saved.copy(bufferBudgetManaged = true)
        val engineOff = saved.copy(bufferEngineEnabled = false)
        val networkOff = saved.copy(parallelNetworkEnabled = false)
        val cases = listOf(
            saved to play(with = tables.copy(automatic = false)),
            saved to play(with = ServiceNetworkTables()),
            saved to play(streamUrl = "https://other-service.net/film.mkv"),
            saved to play(runtimeMinutes = null),
            saved to play(sizeBytes = null),
            saved to play(isTorrent = true),
            managed to play(settings = managed),
            engineOff to play(settings = engineOff),
            networkOff to play(settings = networkOff)
        )
        cases.forEach { (input, result) ->
            assertNull(result.choice)
            // The input instance itself, not an equal rebuild.
            assertSame(input, result.settings)
        }
    }

    @Test
    fun `qualifier takes public web links of 100 MB or more`() {
        val big = NETWORK_RULE_MIN_BYTES
        assertTrue(qualifiesForNetworkRule(url, big, isTorrent = false))
        assertTrue(qualifiesForNetworkRule("http://cdn.example.com/f.mkv", big, isTorrent = false))
        assertFalse(qualifiesForNetworkRule(url, big - 1, isTorrent = false))
        assertFalse(qualifiesForNetworkRule(url, null, isTorrent = false))
        assertFalse(qualifiesForNetworkRule(url, big, isTorrent = true))
        // TorrServer and LAN servers.
        assertFalse(qualifiesForNetworkRule("http://127.0.0.1:8090/stream/f.mkv", big, isTorrent = false))
        assertFalse(qualifiesForNetworkRule("http://192.168.1.20:8096/f.mkv", big, isTorrent = false))
        assertFalse(qualifiesForNetworkRule("http://localhost:11470/f.mkv", big, isTorrent = false))
        // Not http(s): isPublicServerHost alone would say true for these.
        assertFalse(qualifiesForNetworkRule("magnet:?xt=urn:btih:abc", big, isTorrent = false))
        assertFalse(qualifiesForNetworkRule("ftp://files.example.com/f.mkv", big, isTorrent = false))
        assertFalse(qualifiesForNetworkRule("not a url", big, isTorrent = false))
    }

    @Test
    fun automaticShapeNeedsBothMastersAndAnUnmanagedBudget() {
        val shape = PlayerSettings(bufferEngineEnabled = true, parallelNetworkEnabled = true, bufferBudgetManaged = false)
        assertTrue(inAutomaticShape(shape))
        assertFalse(inAutomaticShape(shape.copy(bufferEngineEnabled = false)))
        assertFalse(inAutomaticShape(shape.copy(parallelNetworkEnabled = false)))
        assertFalse(inAutomaticShape(shape.copy(bufferBudgetManaged = true)))
    }

    @Test
    fun connectionsInUseIsWhatTheSettingsAskFor() {
        assertEquals(3, connectionsInUse(saved))
        assertEquals(1, connectionsInUse(saved.copy(useParallelConnections = false)))
        assertEquals(1, connectionsInUse(saved.copy(parallelNetworkEnabled = false)))
        assertEquals(1, connectionsInUse(saved.copy(parallelConnectionCount = 0)))
    }
}
