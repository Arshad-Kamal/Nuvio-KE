package com.nuvio.tv.core.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastPlaybackDiagnosticsTest {

    @Test
    fun roundTripsEveryField() {
        assertEquals(FULL, LastPlaybackDiagnostics.fromJson(FULL.toJson()))
    }

    /**
     * FR-16: the persisted format. [GOLDEN] is the string the device writes for [FULL]:
     * Android's org.json keeps toJson()'s put order and escapes '/'. The JVM test
     * classpath's org.json orders keys by hash and leaves '/' bare, so the bytes cannot
     * be compared here; the key set and every value are.
     */
    @Test
    fun toJsonMatchesTheGoldenKeySetAndValues() {
        val produced = JSONObject(FULL.toJson()).asMap()

        assertEquals(JSONObject(GOLDEN).asMap(), produced)
    }

    @Test
    fun legacyGoldenStringReadsBackEveryField() {
        assertEquals(FULL, LastPlaybackDiagnostics.fromJson(GOLDEN))
    }

    @Test
    fun nullableStringsReadBackAsNull() {
        val parsed = LastPlaybackDiagnostics.fromJson(LastPlaybackDiagnostics.EMPTY.toJson())

        assertEquals(LastPlaybackDiagnostics.EMPTY, parsed)
    }

    @Test
    fun blankAndLiteralNullStringsBecomeNull() {
        val parsed = LastPlaybackDiagnostics.fromJson(
            """{"streamUrl":"  ","dvElType":"null","videoCodec":"HEVC"}"""
        )

        assertNull(parsed.streamUrl)
        assertNull(parsed.dvElType)
        assertEquals("HEVC", parsed.videoCodec)
    }

    @Test
    fun recordFromBeforeTheListedNotOpenedKeyReadsNull() {
        val parsed = LastPlaybackDiagnostics.fromJson("""{"host":"cdn.example","audioCapabilities":"Direct: AC3"}""")

        assertEquals("Direct: AC3", parsed.audioCapabilities)
        assertNull(parsed.audioListedNotOpened)
    }

    @Test
    fun malformedJsonReturnsEmpty() {
        assertEquals(LastPlaybackDiagnostics.EMPTY, LastPlaybackDiagnostics.fromJson("not json"))
    }

    // keys()/get() only: the API Android's org.json and the JVM one share.
    private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { get(it) }

    private companion object {
        val FULL = LastPlaybackDiagnostics(
            timestampMs = 123L,
            host = "cdn.example",
            resolvedServingHost = "edge.example",
            streamUrl = "https://cdn.example/a.mkv",
            filename = "a.mkv",
            headersJson = "{\"k\":\"v\"}",
            videoBitrate = 80_000_000,
            durationMs = 7_200_000L,
            hdrCapsKnown = true,
            displayDv = true,
            displayHdr10 = true,
            displayHdr10Plus = true,
            codecDv7Supported = true,
            dv81DecoderName = "OMX.dv",
            bridgeReady = true,
            bridgeVersion = "1.0",
            bridgeReason = "ready",
            dv7ModeRequested = "AUTO",
            dv7ModeEffective = "DV81_LIBDOVI",
            dv7AutoDecision = "CONVERT_TO_DV81",
            bufferEngineEnabled = true,
            parallelNetworkEnabled = true,
            firstFrameMs = 900L,
            dv7DoviCalls = 3,
            dv7DoviSuccess = 2,
            dv7DoviSignalRewrites = 1,
            dvSourceProfile = "7",
            dvElType = "FEL",
            dv7RpuDrops = 4,
            dvHdrMastering = "MaxCLL 1000",
            videoResolution = "3840x2160",
            videoCodec = "HEVC",
            videoHdrType = "Dolby Vision",
            audioPath = "TrueHD passthrough",
            audioCapabilities = "direct: TrueHD",
            audioListedNotOpened = "DTS-HD",
            rebufferCount = 2,
            rebufferTotalMs = 1_500L,
            result = "Played"
        )

        const val GOLDEN = """{"timestampMs":123,"host":"cdn.example","resolvedServingHost":"edge.example",""" +
            """"streamUrl":"https:\/\/cdn.example\/a.mkv","filename":"a.mkv","headersJson":"{\"k\":\"v\"}",""" +
            """"videoBitrate":80000000,"durationMs":7200000,"hdrCapsKnown":true,"displayDv":true,""" +
            """"displayHdr10":true,"displayHdr10Plus":true,"codecDv7Supported":true,""" +
            """"dv81DecoderName":"OMX.dv","bridgeReady":true,"bridgeVersion":"1.0","bridgeReason":"ready",""" +
            """"dv7ModeRequested":"AUTO","dv7ModeEffective":"DV81_LIBDOVI","dv7AutoDecision":"CONVERT_TO_DV81",""" +
            """"bufferEngineEnabled":true,"parallelNetworkEnabled":true,"firstFrameMs":900,""" +
            """"dv7DoviCalls":3,"dv7DoviSuccess":2,"dv7DoviSignalRewrites":1,"dvSourceProfile":"7",""" +
            """"dvElType":"FEL","dv7RpuDrops":4,"dvHdrMastering":"MaxCLL 1000",""" +
            """"videoResolution":"3840x2160","videoCodec":"HEVC","videoHdrType":"Dolby Vision",""" +
            """"audioPath":"TrueHD passthrough","audioCapabilities":"direct: TrueHD","audioListedNotOpened":"DTS-HD",""" +
            """"rebufferCount":2,"rebufferTotalMs":1500,"result":"Played"}"""
    }
}
