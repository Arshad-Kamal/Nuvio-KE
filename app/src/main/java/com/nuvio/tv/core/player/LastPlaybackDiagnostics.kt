package com.nuvio.tv.core.player

import org.json.JSONObject

/**
 * Snapshot of the most recent playback's diagnostic data.
 *
 * Populated during initializePlayer and onRenderedFirstFrame / onPlayerError.
 * Persisted to DataStore so users can take a photo of Settings without ADB
 * to report which DV decision branch was taken on their device.
 *
 * All fields are nullable / have safe defaults so partial data is okay
 * (e.g. if playback errored before first frame, conversion counts will be 0).
 */
data class LastPlaybackDiagnostics(
    val timestampMs: Long = 0L,
    val host: String = "",
    // N6 V2 follow-on: the host that actually served the bytes -- the
    // post-redirect host captured live by the NET_CONN listener -- persisted
    // at first frame. Null when playback never rendered a frame or no call
    // completed; may equal [host] on cosmetic (same-host) redirects, which
    // renderers suppress at display time rather than here.
    val resolvedServingHost: String? = null,
    val streamUrl: String? = null,
    val filename: String? = null,
    val headersJson: String? = null,
    val videoBitrate: Int = -1,
    val durationMs: Long = 0L,


    // Display capabilities (from DolbyVisionBaseLayerPolicy.Result)
    val hdrCapsKnown: Boolean = false,
    val displayDv: Boolean = false,
    val displayHdr10: Boolean = false,
    val displayHdr10Plus: Boolean = false,

    // Codec capabilities
    val codecDv7Supported: Boolean = false,
    val dv81DecoderName: String? = null,  // e.g. "OMX.MTK.VIDEO.DECODER.DVHE.STH"

    // libdovi bridge
    val bridgeReady: Boolean = false,
    val bridgeVersion: String? = null,
    val bridgeReason: String? = null,  // "ready", "hdr10-base-layer-mode", etc.

    // DV decision
    val dv7ModeRequested: String = "",   // AUTO, HDR10_BASE_LAYER, DV81_LIBDOVI, OFF
    val dv7ModeEffective: String = "",
    val dv7AutoDecision: String? = null, // CONVERT_TO_DV81, STRIP_TO_HDR10, etc. (null when not AUTO)

    // Buffer/Network toggles
    val bufferEngineEnabled: Boolean = false,
    val parallelNetworkEnabled: Boolean = false,

    // Outcome
    val firstFrameMs: Long = -1L,        // -1 = never rendered
    val dv7DoviCalls: Int = 0,
    val dv7DoviSuccess: Int = 0,
    val dv7DoviSignalRewrites: Int = 0,
    val dvSourceProfile: String? = null,
    // DV7 F3: enhancement-layer type of the last DV7 stream ("FEL", "MEL",
    // "unknown", optionally suffixed when preserve-mapping was active), and
    // the nt8 per-playback RPU drop counter, both surfaced in Diagnostics.
    val dvElType: String? = null,
    val dv7RpuDrops: Int = 0,
    // Item 2: one-line static HDR mastering metadata read from the RPU
    // (MaxCLL/MaxFALL + MDL peak in nits), or null when unavailable.
    val dvHdrMastering: String? = null,

    // Video output (captured at first frame from the played video Format)
    val videoResolution: String? = null, // e.g. "3840x2160"
    val videoCodec: String? = null,       // e.g. "Dolby Vision", "HEVC", "AV1"
    val videoHdrType: String? = null,     // e.g. "Dolby Vision", "HDR10", "HLG", "SDR"
    val audioPath: String? = null,        // e.g. "TrueHD → Passthrough (TrueHD, 48 kHz, 8ch)"
    // What the platform claimed the chain could take, captured at sink build. The
    // per-format passthrough overrides exist because this is sometimes wrong, so the
    // claim itself is the first thing worth seeing in a report.
    val audioCapabilities: String? = null, // AudioCapabilityReport.Snapshot.text ("Box opens: …" lines)
    // Encodings the HDMI device lists that the box cannot open (Snapshot.listedNotOpened).
    val audioListedNotOpened: String? = null,

    // Buffer telemetry (rebuffers counted after first frame; re-persisted at playback end)
    val rebufferCount: Int = 0,
    val rebufferTotalMs: Long = 0L,

    val result: String = "Pending"       // "Played", "Error: ..."
) {
    /**
     * Serialize to a JSON string for DataStore persistence. Hand-written org.json: the
     * key set is a persisted format read back by [fromJson], so keep it byte-stable.
     */
    fun toJson(): String = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("host", host)
        put("resolvedServingHost", resolvedServingHost ?: JSONObject.NULL)
        put("streamUrl", streamUrl ?: JSONObject.NULL)
        put("filename", filename ?: JSONObject.NULL)
        put("headersJson", headersJson ?: JSONObject.NULL)
        put("videoBitrate", videoBitrate)
        put("durationMs", durationMs)
        put("hdrCapsKnown", hdrCapsKnown)
        put("displayDv", displayDv)
        put("displayHdr10", displayHdr10)
        put("displayHdr10Plus", displayHdr10Plus)
        put("codecDv7Supported", codecDv7Supported)
        put("dv81DecoderName", dv81DecoderName ?: JSONObject.NULL)
        put("bridgeReady", bridgeReady)
        put("bridgeVersion", bridgeVersion ?: JSONObject.NULL)
        put("bridgeReason", bridgeReason ?: JSONObject.NULL)
        put("dv7ModeRequested", dv7ModeRequested)
        put("dv7ModeEffective", dv7ModeEffective)
        put("dv7AutoDecision", dv7AutoDecision ?: JSONObject.NULL)
        put("bufferEngineEnabled", bufferEngineEnabled)
        put("parallelNetworkEnabled", parallelNetworkEnabled)
        put("firstFrameMs", firstFrameMs)
        put("dv7DoviCalls", dv7DoviCalls)
        put("dv7DoviSuccess", dv7DoviSuccess)
        put("dv7DoviSignalRewrites", dv7DoviSignalRewrites)
        put("dvSourceProfile", dvSourceProfile ?: JSONObject.NULL)
        put("dvElType", dvElType ?: JSONObject.NULL)
        put("dv7RpuDrops", dv7RpuDrops)
        put("dvHdrMastering", dvHdrMastering ?: JSONObject.NULL)
        put("videoResolution", videoResolution ?: JSONObject.NULL)
        put("videoCodec", videoCodec ?: JSONObject.NULL)
        put("videoHdrType", videoHdrType ?: JSONObject.NULL)
        put("audioPath", audioPath ?: JSONObject.NULL)
        put("audioCapabilities", audioCapabilities ?: JSONObject.NULL)
        put("audioListedNotOpened", audioListedNotOpened ?: JSONObject.NULL)
        put("rebufferCount", rebufferCount)
        put("rebufferTotalMs", rebufferTotalMs)
        put("result", result)
    }.toString()

    companion object {
        val EMPTY = LastPlaybackDiagnostics()

        /**
         * Parse from a JSON string previously produced by [toJson].
         * Returns [EMPTY] on any parse failure or invalid input.
         */
        fun fromJson(json: String): LastPlaybackDiagnostics = try {
            val o = JSONObject(json)
            LastPlaybackDiagnostics(
                timestampMs = o.optLong("timestampMs", 0L),
                host = o.optString("host", ""),
                resolvedServingHost = o.optNullableString("resolvedServingHost"),
                streamUrl = o.optNullableString("streamUrl"),
                filename = o.optNullableString("filename"),
                headersJson = o.optNullableString("headersJson"),
                videoBitrate = o.optInt("videoBitrate", -1),
                durationMs = o.optLong("durationMs", 0L),
                hdrCapsKnown = o.optBoolean("hdrCapsKnown", false),
                displayDv = o.optBoolean("displayDv", false),
                displayHdr10 = o.optBoolean("displayHdr10", false),
                displayHdr10Plus = o.optBoolean("displayHdr10Plus", false),
                codecDv7Supported = o.optBoolean("codecDv7Supported", false),
                dv81DecoderName = o.optNullableString("dv81DecoderName"),
                bridgeReady = o.optBoolean("bridgeReady", false),
                bridgeVersion = o.optNullableString("bridgeVersion"),
                bridgeReason = o.optNullableString("bridgeReason"),
                dv7ModeRequested = o.optString("dv7ModeRequested", ""),
                dv7ModeEffective = o.optString("dv7ModeEffective", ""),
                dv7AutoDecision = o.optNullableString("dv7AutoDecision"),
                bufferEngineEnabled = o.optBoolean("bufferEngineEnabled", false),
                parallelNetworkEnabled = o.optBoolean("parallelNetworkEnabled", false),
                firstFrameMs = o.optLong("firstFrameMs", -1L),
                dv7DoviCalls = o.optInt("dv7DoviCalls", 0),
                dv7DoviSuccess = o.optInt("dv7DoviSuccess", 0),
                dv7DoviSignalRewrites = o.optInt("dv7DoviSignalRewrites", 0),
                dvSourceProfile = o.optNullableString("dvSourceProfile"),
                dvElType = o.optNullableString("dvElType"),
                dv7RpuDrops = o.optInt("dv7RpuDrops", 0),
                dvHdrMastering = o.optNullableString("dvHdrMastering"),
                videoResolution = o.optNullableString("videoResolution"),
                videoCodec = o.optNullableString("videoCodec"),
                videoHdrType = o.optNullableString("videoHdrType"),
                audioPath = o.optNullableString("audioPath"),
                audioCapabilities = o.optNullableString("audioCapabilities"),
                audioListedNotOpened = o.optNullableString("audioListedNotOpened"),
                rebufferCount = o.optInt("rebufferCount", 0),
                rebufferTotalMs = o.optLong("rebufferTotalMs", 0L),
                result = o.optString("result", "Pending")
            )
        } catch (e: Exception) {
            EMPTY
        }

        /** Absent, JSON null (which org.json reads back as "null") and blank all map to null. */
        private fun JSONObject.optNullableString(key: String): String? =
            optString(key, "").takeUnless { it.isBlank() || it == "null" }
    }
}