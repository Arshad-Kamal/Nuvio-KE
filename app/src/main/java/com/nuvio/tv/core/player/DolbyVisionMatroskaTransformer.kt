package com.nuvio.tv.core.player

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.DolbyVisionConfig
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import java.io.ByteArrayOutputStream

/**
 * App-level (AAR mode) implementation of the vendored Matroska extractor's
 * [MatroskaExtractor.DolbyVisionSampleTransformer] seam.
 *
 * Performs the DV7 to DV8.1 conversion for MKV, wired to [DoviBridge]. The
 * extractor calls:
 *
 *  - [transformHevcSample] just before writing the HEVC sample; we rewrite
 *    its NALs (dropping EL NALs, converting the in-band RPU NAL).
 *  - [onDolbyVisionCodecString] when building the output Format; dvhe.07/dvh1.07
 *    becomes dvhe.08/dvh1.08 to advertise single-layer 8.1.
 *
 * Mode selection and the manual-DV8.1 mode-2 default come from [config]
 * (shared with the MP4/TS path in [DolbyVisionExtractorsFactory]).
 * [hdr10BaseLayerMode] tells the extractor to expose a DV track as its HEVC base layer.
 * [rpuConverter] is the per-RPU conversion seam (libdovi in production).
 *
 * Spec 21 N4: the per-sample NAL walk runs in [nativePipeline] (`nal_pipeline.cpp`; set when
 * [BuildConfig.DV_MKV_NATIVE_PIPELINE] is on) when the bridge is loaded and the production
 * converter is in use; otherwise (and for good after a missing native entry point) the Kotlin
 * walk below runs. Tests inject a scripted [nativePipeline] to exercise the Kotlin glue. The
 * once-per-stream policy (DV5 decision, probes, HDR10 SEI injection, abandon state and its
 * logging) stays here for both paths.
 */
@UnstableApi
internal class DolbyVisionMatroskaTransformer(
    private val config: DolbyVisionConversionConfig,
    private val stripRpuOnly: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false,
    private val injectHdr10Sei: Boolean = false,
    private val hdr10BaseLayerMode: Boolean = false,
    private val rpuConverter: RpuConverter = RpuConverter.Native,
    private val nativePipeline: MkvNativePipeline? =
        if (BuildConfig.DV_MKV_NATIVE_PIPELINE) MkvNativePipeline.Native else null,
) : MatroskaExtractor.DolbyVisionSampleTransformer {

    private var lastTransformedLength = 0

    // This stream's native output buffer (converted RPU NAL); loader thread only.
    private val rpuOut = DoviBridge.OutBuffer()

    // Task 1: HDR10 SEI NALs (MDCV/CLLI) built once per stream from the RPU
    // static metadata, injected on the strip path so non-DV HDR10 sinks tone-map
    // correctly. Null until first resolved; empty-checked before use.
    private var cachedHdr10SeiNals: List<ByteArray>? = null

    // This stream's RPU static metadata, set by probeRpuMetadata. The SEI is
    // built from this, never from the process-wide DolbyVisionConversionStats
    // (diagnostics only, writable by any other stream).
    private var rpuMetadata: DoviBridge.RpuStaticMetadata? = null

    // nt27: log the strip-path injection outcome once per stream (inject vs skip)
    // so on-device testing can tell "injected" from "skipped as already-present".
    private var injectOutcomeLogged = false

    // Reused across samples; grows to the largest frame once.
    private val scratch = ExposedByteArrayOutputStream(64 * 1024)

    // Spec 21 N4 native path: this stream's output buffer and the JNI state array (MKV_STATE_*).
    // The production pipeline runs only with the production converter (libdovi on both sides).
    private var useNativePipeline = nativePipeline != null &&
        (nativePipeline !== MkvNativePipeline.Native || rpuConverter === RpuConverter.Native)
    private var nativeChecked = false
    private val sampleOut = DoviBridge.OutBuffer(0)
    private val nativeState = IntArray(DoviBridge.MKV_STATE_SIZE)

    // Per-stream timing log for the Shield A/B (window of TIMING_WINDOW_SAMPLES samples).
    private var timingSamples = 0
    private var timingBytes = 0L
    private var timingTotalNs = 0L
    private var timingMaxNs = 0L

    // Cached per-stream once resolved. SEI carrying mastering-display/CLL info
    // is typically only sent on keyframes, so we keep checking each sample
    // until we find it or hit the detection window, rather than deciding off
    // just the first frame.
    private var dv5StripDecision: Boolean? = null
    private var dv5DetectionSamplesChecked = 0

    private fun shouldStripDv5Rpu(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int
    ): Boolean {
        dv5StripDecision?.let { return it }
        // Every DV5 frame carries an RPU, so that is no signal. Base-layer HDR10 static SEI
        // (MDCV/CLLI) is: it marks a base layer that is viewable on its own (safe to strip),
        // vs one valid only through the DV reshaping curve.
        val detected = Hdr10SeiInjector.hasHdr10StaticSei(
            sample, sampleLength, nalUnitLengthFieldLength
        )
        if (detected) {
            dv5StripDecision = true
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=true -> STRIP")
            return true
        }
        dv5DetectionSamplesChecked++
        if (dv5DetectionSamplesChecked >= DV5_DETECTION_SAMPLE_LIMIT) {
            dv5StripDecision = false
            android.util.Log.i("DVStrip", "DV5_STRIP_DECISION: hdr10StaticSeiPresent=false after $dv5DetectionSamplesChecked samples -> SKIP")
            return false
        }
        return false
    }

    // Reuses the package-private ExposedByteArrayOutputStream from HevcDvRpuStripper.kt

    // ── DV7 review F5 state ──
    private var consecutiveRpuFailures = 0
    private var rpuConversionAbandoned = false
    private var droppedRpuCount = 0L

    // DV7 F3: EL-type detection runs once per stream, on the first in-band
    // RPU seen by the rewriter.
    private var elTypeProbed = false
    // Item 2: once-per-stream RPU static-metadata probe state (diagnostics only).
    private var metadataProbed = false
    private var metadataProbeAttempts = 0

    private fun probeElType(sample: ByteArray, offset: Int, nalSize: Int) {
        val code = DoviBridge.detectRpuElType(sample, offset, nalSize)
        DolbyVisionConversionStats.recordElType(code)
    }

    /**
     * Item 2: reads the RPU's static HDR metadata once per stream, for
     * Diagnostics and the strip-path HDR10 SEI injection. Called from the top
     * of [transformHevcSample] so it covers both the strip (non-DV sink) and
     * convert paths. Best-effort and read-only; attempt-bounded so a stream
     * whose RPU never rides in-band can't scan forever.
     */
    private fun probeRpuMetadata(sample: ByteArray, sampleLength: Int, nalUnitLengthFieldLength: Int) {
        if (metadataProbed || metadataProbeAttempts >= METADATA_PROBE_ATTEMPT_LIMIT) return
        if (!DoviBridge.isAvailable()) return
        metadataProbeAttempts++
        HevcDvRpuStripper.findRpuNalLengthDelimited(sample, sampleLength, nalUnitLengthFieldLength)?.let { rpu ->
            metadataProbed = true
            val meta = DoviBridge.getRpuStaticMetadata(sample, rpu.first, rpu.second)
            rpuMetadata = meta
            DolbyVisionConversionStats.recordRpuMetadata(meta)
            android.util.Log.i(
                "DVMetaProbe",
                "hookA mkv rpuLen=${rpu.second} meta=${meta?.toDiagnosticLine() ?: "null"}"
            )
        }
    }

    /**
     * DV7 review F5: a failed conversion now DROPS the RPU instead of
     * forwarding raw P7 bytes (or an unparseable blob) under dvhe.08
     * signalling — a persistent failure previously produced a stream whose
     * codec string and per-frame metadata disagreed, frame after frame. The
     * base layer continues (effectively HDR10). After
     * [RPU_FAILURE_ABANDON_THRESHOLD] consecutive failures the per-frame
     * conversion attempts stop entirely for this stream.
     */
    private fun registerRpuFailure() {
        consecutiveRpuFailures++
        droppedRpuCount++
        DolbyVisionConversionStats.recordRpuDrop()
        if (consecutiveRpuFailures == 1 || consecutiveRpuFailures % 100 == 0) logRpuDrop()
        if (!rpuConversionAbandoned && consecutiveRpuFailures >= RPU_FAILURE_ABANDON_THRESHOLD) markAbandoned()
    }

    private fun logRpuDrop() {
        android.util.Log.w(
            TAG,
            "DV7_MKV: RPU conversion failed; dropping RPU " +
                "(consecutive=$consecutiveRpuFailures dropped=$droppedRpuCount)"
        )
    }

    private fun markAbandoned() {
        rpuConversionAbandoned = true
        android.util.Log.e(
            TAG,
            "DV7_MKV: $RPU_FAILURE_ABANDON_THRESHOLD consecutive RPU conversion " +
                "failures; abandoning conversion for this stream " +
                "(RPUs dropped, base layer continues as HDR10)"
        )
    }

    override fun shouldTransform(codecs: String?, dolbyVisionConfigBytes: ByteArray?): Boolean {
        if (stripHdr10PlusSei) return true
        val isDv = codecs?.startsWith("dv", ignoreCase = true) == true ||
                (dolbyVisionConfigBytes != null && dolbyVisionConfigBytes.isNotEmpty())
        if (stripRpuOnly) return isDv
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        return config.shouldConvert(profile)
    }

    override fun lastTransformedSampleLength(): Int = lastTransformedLength

    override fun isHdr10BaseLayerModeActive(): Boolean = hdr10BaseLayerMode

    override fun transformHevcSample(
        sampleLengthDelimitedData: ByteArray?,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        val sample = sampleLengthDelimitedData ?: return null
        val start = System.nanoTime()
        val profile = resolveProfile(null, dolbyVisionConfigBytes)

        lastTransformedLength = sampleLength

        probeRpuMetadata(sample, sampleLength, nalUnitLengthFieldLength)

        val native = nativePipelineReady()
        val result = if (native) {
            transformNative(sample, sampleLength, nalUnitLengthFieldLength, profile)
        } else {
            transformKotlin(sample, sampleLength, nalUnitLengthFieldLength, profile)
        }
        recordTiming(native, sampleLength, System.nanoTime() - start)
        return result
    }

    private fun nativePipelineReady(): Boolean {
        if (useNativePipeline && !nativeChecked) {
            nativeChecked = true
            useNativePipeline = nativePipeline !== MkvNativePipeline.Native || DoviBridge.isAvailable()
            android.util.Log.i(TAG, "MKV sample pipeline: ${if (useNativePipeline) "native" else "kotlin (bridge unavailable)"}")
        }
        return useNativePipeline
    }

    /** Spec 21 N4: steps 1-3 in native; policy from [mkvNativeOps], failure state round-trips. */
    private fun transformNative(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        profile: Int?
    ): ByteArray {
        // Never null here (nativePipelineReady requires it); the elvis gives the smart cast.
        val pipeline = nativePipeline ?: return transformKotlin(sample, sampleLength, nalUnitLengthFieldLength, profile)
        val step = mkvNativeOps(config, stripRpuOnly, stripHdr10PlusSei, profile) {
            shouldStripDv5Rpu(sample, sampleLength, nalUnitLengthFieldLength)
        }
        if (step.ops == 0) return sample
        if ((step.ops and DoviBridge.MKV_OP_CONVERT) != 0 && !elTypeProbed) {
            HevcDvRpuStripper.findRpuNalLengthDelimited(sample, sampleLength, nalUnitLengthFieldLength)?.let {
                elTypeProbed = true
                probeElType(sample, it.first, it.second)
            }
        }
        nativeState[DoviBridge.MKV_STATE_CONSECUTIVE_FAILURES] = consecutiveRpuFailures
        nativeState[DoviBridge.MKV_STATE_ABANDONED] = if (rpuConversionAbandoned) 1 else 0
        nativeState.fill(0, fromIndex = DoviBridge.MKV_STATE_DROPS)  // outputs: never re-fold stale ones
        val written = try {
            pipeline.process(
                sample, sampleLength, nalUnitLengthFieldLength, sampleOut, step.ops, step.mode,
                RPU_FAILURE_ABANDON_THRESHOLD, nativeState
            )
        } catch (e: LinkageError) {
            // A library without the N3 entry point: the Kotlin path for the rest of the stream.
            useNativePipeline = false
            android.util.Log.w(TAG, "MKV native pipeline unavailable (${e.message}); using the Kotlin path")
            return transformKotlin(sample, sampleLength, nalUnitLengthFieldLength, profile)
        }
        applyNativeFailureState()
        val flags = nativeState[DoviBridge.MKV_STATE_FLAGS]
        if ((flags and DoviBridge.MKV_FLAG_CHANGED) == 0) return sample
        lastTransformedLength = written
        val out = sampleOut.bytes
        if ((flags and DoviBridge.MKV_FLAG_RPU_STRIPPED) == 0) return out
        return injectHdr10SeiIfEnabled(out, written, profile, nalUnitLengthFieldLength) ?: out
    }

    /**
     * Folds the native call's state into this stream's: the same counters and stats as
     * [registerRpuFailure] per failed RPU, plus the converter calls/successes [DoviBridge] would
     * have counted. The drop log fires once per call that starts or resets a failure run, or
     * crosses a hundred (registerRpuFailure's cadence, at sample granularity).
     */
    private fun applyNativeFailureState() {
        val before = consecutiveRpuFailures
        val drops = nativeState[DoviBridge.MKV_STATE_DROPS]
        val after = nativeState[DoviBridge.MKV_STATE_CONSECUTIVE_FAILURES]
        consecutiveRpuFailures = after
        DoviBridge.recordConversions(
            nativeState[DoviBridge.MKV_STATE_CONVERT_CALLS], nativeState[DoviBridge.MKV_STATE_CONVERTED]
        )
        if (drops > 0) {
            droppedRpuCount += drops
            repeat(drops) { DolbyVisionConversionStats.recordRpuDrop() }
            val resetInCall = after < before + drops
            if (before == 0 || resetInCall || after / 100 > before / 100) logRpuDrop()
        }
        if (nativeState[DoviBridge.MKV_STATE_ABANDONED] != 0 && !rpuConversionAbandoned) markAbandoned()
    }

    private fun recordTiming(native: Boolean, bytes: Int, ns: Long) {
        timingSamples++
        timingBytes += bytes
        timingTotalNs += ns
        if (ns > timingMaxNs) timingMaxNs = ns
        if (timingSamples < TIMING_WINDOW_SAMPLES) return
        android.util.Log.i(
            "DVMkvTiming",
            "path=${if (native) "native" else "kotlin"} samples=$timingSamples " +
                "avgKB=${timingBytes / timingSamples / 1024} avgUs=${timingTotalNs / timingSamples / 1000} " +
                "maxUs=${timingMaxNs / 1000}"
        )
        timingSamples = 0
        timingBytes = 0L
        timingTotalNs = 0L
        timingMaxNs = 0L
    }

    /** The pre-N4 per-sample path (README "Per-sample order"); fallback and A/B baseline. */
    private fun transformKotlin(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        profile: Int?
    ): ByteArray {
        if (stripRpuOnly) {
            if (profile == 5) {
                if (!shouldStripDv5Rpu(sample, sampleLength, nalUnitLengthFieldLength)) {
                    return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
                }
            }
            // Use the shared ExposedByteArrayOutputStream scratch buffer to avoid GC allocations on every frame
            val changed = HevcDvRpuStripper.stripRpuLengthDelimited(
                sample, sampleLength, nalUnitLengthFieldLength, scratch
            )
            if (changed) {
                val stripped = finishScratch()
                val afterHdr10Plus =
                    stripHdr10PlusIfEnabled(stripped, lastTransformedLength, nalUnitLengthFieldLength) ?: stripped
                return injectHdr10SeiIfEnabled(
                    afterHdr10Plus, lastTransformedLength, profile, nalUnitLengthFieldLength
                ) ?: afterHdr10Plus
            }
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (!config.shouldConvert(profile)) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        if (profile == 5 && !config.convertDv5Rpu) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        val mode = config.conversionMode(profile)
        if (!rewriteMp4HevcSampleInto(sample, sampleLength, nalUnitLengthFieldLength, mode)) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }
        val dvResult = finishScratch()
        return stripHdr10PlusIfEnabled(dvResult, lastTransformedLength, nalUnitLengthFieldLength) ?: dvResult
    }

    private fun finishScratch(): ByteArray {
        lastTransformedLength = scratch.size()
        return scratch.backingArray()
    }

    override fun onDolbyVisionCodecString(
        codecs: String?,
        dolbyVisionConfigBytes: ByteArray?
    ): String? {
        val profile = resolveProfile(codecs, dolbyVisionConfigBytes)
        if (stripRpuOnly) {
            // The extractor re-signals a stripped track as plain HEVC, so the codec-string
            // fallback at first frame finds no profile: record the source's here, or the HUD's
            // DV row (keyed on it) never shows under Strip DV / HDR10 base layer (F-11).
            DolbyVisionConversionStats.recordSourceProfile(profile)
            return null
        }
        if (!config.shouldConvert(profile)) return null
        DolbyVisionConversionStats.recordSourceProfile(profile)
        val normalized = DvCodecString.rewriteToProfile8(codecs) ?: return null
        DolbyVisionConversionStats.recordCodecStringRewrite()
        return normalized
    }

    /**
     * Applies HDR10+ SEI stripping to [data] if [stripHdr10PlusSei] is enabled.
     * Returns null when the feature is off or no HDR10+ was found; otherwise
     * returns the stripped bytes and updates [lastTransformedLength].
     */
    private fun stripHdr10PlusIfEnabled(
        data: ByteArray,
        len: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!stripHdr10PlusSei) return null
        val stripped = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(data, len, nalLengthFieldLength)
        if (stripped != null) {
            lastTransformedLength = stripped.size
            return stripped
        }
        return null
    }

    /**
     * Task 1: inject the source RPU's HDR10 static-metadata SEI (MDCV always,
     * CLLI when known) into [data] on the strip path, so a non-DV HDR10 sink
     * tone-maps against the master. Only for DV profile 8 (other profiles are
     * skipped and logged), only when the base layer does not already carry HDR10
     * static SEI, and only once this stream's RPU metadata has been read. Returns null (caller keeps [data]) when nothing is
     * injected; otherwise returns the enlarged bytes and updates
     * [lastTransformedLength]. [len] is the valid length of [data], which may be
     * an oversized scratch buffer.
     */
    private fun injectHdr10SeiIfEnabled(
        data: ByteArray,
        len: Int,
        profile: Int?,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!injectHdr10Sei) return null
        if (profile != 8) {
            logInjectOutcomeOnce("skipped: profile $profile not DV8 (P8.1)")
            return null
        }
        val nals = cachedHdr10SeiNals ?: run {
            val meta = rpuMetadata ?: return null
            val built = Hdr10SeiInjector.buildSeiNals(meta)
            if (built.isEmpty()) return null   // don't cache empty; retry next sample
            cachedHdr10SeiNals = built
            built
        }
        if (Hdr10SeiInjector.hasHdr10StaticSei(data, len, nalLengthFieldLength = nalLengthFieldLength)) {
            logInjectOutcomeOnce("skipped: base already carries HDR10 SEI")
            return null
        }
        val injected = Hdr10SeiInjector.injectLengthDelimited(data, len, nalLengthFieldLength, nals)
        logInjectOutcomeOnce("injected MDCV+CLLI (+${injected.size - len} bytes, ${nals.size} NAL(s))")
        lastTransformedLength = injected.size
        return injected
    }

    private fun logInjectOutcomeOnce(msg: String) {
        if (!injectOutcomeLogged) {
            injectOutcomeLogged = true
            android.util.Log.i("DVInject", "MKV strip: $msg")
        }
    }

    // ── Conversion + NAL helpers ──

    private fun rewriteMp4HevcSampleInto(
        sample: ByteArray,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        mode: Int
    ): Boolean {
        if (nalUnitLengthFieldLength !in 1..4) return false
        var offset = 0
        var changed = false
        val out = scratch
        out.reset()
        while (offset + nalUnitLengthFieldLength <= sampleLength) {
            val nalSize = readLengthField(sample, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return false
            offset += nalUnitLengthFieldLength
            if (nalSize > sampleLength - offset) return false
            val nalType = if (nalSize >= 1) nalUnitTypeAt(sample, offset) else -1
            val layerId = nuhLayerIdAt(sample, offset, nalSize)
            when {
                // Enhancement-layer NAL that isn't the RPU: drop it.
                layerId > 0 && nalType != NAL_TYPE_UNSPEC62 -> changed = true
                // DV7 F3 in-band EL strip: the P7 EL rides in unspec63 wrapper
                // NALs at layer 0 on single-track remuxes, so the layer-id
                // filter above never matches it. A DV8.1 stream must not carry
                // an enhancement layer -- forwarding these under dvhe.08
                // signalling makes the Amlogic DV core fall back to HDR10.
                nalType == NAL_TYPE_UNSPEC63 -> changed = true
                // RPU NAL: convert directly from sample buffer without JVM allocations
                nalType == NAL_TYPE_UNSPEC62 -> {
                    // DV7 F3: one-shot FEL/MEL detection on the first in-band
                    // RPU, plus the soft preserve-mapping-on-FEL guard.
                    if (!elTypeProbed) {
                        elTypeProbed = true
                        probeElType(sample, offset, nalSize)
                    }
                    val outLen = if (rpuConversionAbandoned) {
                        -1
                    } else {
                        rpuConverter.convert(sample, offset, nalSize, rpuOut, mode)
                    }
                    if (outLen > 0) {
                        changed = true
                        consecutiveRpuFailures = 0
                        if (!writeLengthField(out, outLen, nalUnitLengthFieldLength)) return false
                        out.write(rpuOut.bytes, 0, outLen)
                    } else {
                        // DV7 review F5: conversion failed — DROP the RPU rather
                        // than forwarding the original P7 RPU under dvhe.08
                        // signalling (previously produced a stream whose codec
                        // string and per-frame metadata disagreed).
                        changed = true
                        registerRpuFailure()
                    }
                }
                // Base-layer NAL: forward straight from the sample buffer, no copy.
                else -> {
                    if (!writeLengthField(out, nalSize, nalUnitLengthFieldLength)) return false
                    out.write(sample, offset, nalSize)
                }
            }
            offset += nalSize
        }
        if (offset != sampleLength) return false
        if (!changed) return false
        return out.size() > 0
    }

    private fun resolveProfile(codecs: String?, configBytes: ByteArray?): Int? {
        if (configBytes != null && configBytes.isNotEmpty()) {
            val parsedProfile = runCatching {
                DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
            }.getOrNull()
            if (parsedProfile != null) return parsedProfile
        }
        return DvCodecString.profile(codecs)
    }

    private fun nalUnitTypeAt(sample: ByteArray, offset: Int): Int =
        (sample[offset].toInt() ushr 1) and 0x3F

    private fun nuhLayerIdAt(sample: ByteArray, offset: Int, nalSize: Int): Int {
        if (nalSize < 2) return 0
        val b0 = sample[offset].toInt() and 0x01
        val b1 = sample[offset + 1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(out: ByteArrayOutputStream, value: Int, lengthBytes: Int): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out.write((value ushr (shift * 8)) and 0xFF)
        }
        return true
    }

    private companion object {
        const val NAL_TYPE_UNSPEC62 = 62
        // DV7 F3: unspec63 is the Dolby single-track carriage wrapper for the
        // P7 enhancement layer (EL rides at nuh_layer_id 0 inside type-63
        // NALs on FraMeSToR-class UHD remuxes -- probe-verified 05 Jul 2026).
        const val NAL_TYPE_UNSPEC63 = 63
        const val DV5_DETECTION_SAMPLE_LIMIT = 15
        // Item 2: bound the diagnostics RPU probe (see probeRpuMetadata).
        const val METADATA_PROBE_ATTEMPT_LIMIT = 30
        const val TAG = "DolbyVisionMkvXform"

        // DV7 review F5: consecutive conversion failures before per-frame
        // attempts stop for the stream (~2.5s of 24fps video). The remaining
        // failure mode (RPUs libdovi cannot parse or convert) is all-or-nothing
        // per stream, so a run this long means every frame will fail.
        const val RPU_FAILURE_ABANDON_THRESHOLD = 60

        // ~40 s at 24 fps per DVMkvTiming line.
        const val TIMING_WINDOW_SAMPLES = 1000
    }
}

/**
 * Spec 21 N4: the native MKV per-sample call ([DoviBridge.processLengthPrefixedSample] contract).
 * Production is [Native]; tests script one to exercise the transformer's native glue on the JVM.
 */
internal fun interface MkvNativePipeline {
    fun process(
        sample: ByteArray,
        sampleLen: Int,
        lengthFieldBytes: Int,
        out: DoviBridge.OutBuffer,
        ops: Int,
        doviMode: Int,
        abandonThreshold: Int,
        state: IntArray
    ): Int

    companion object {
        val Native = MkvNativePipeline(DoviBridge::processLengthPrefixedSample)
    }
}

/**
 * Spec 21 N4: one MKV sample's native steps (DoviBridge.MKV_OP_*) and conversion mode ([mode] is
 * read only with MKV_OP_CONVERT; 1 otherwise).
 */
internal data class MkvNativeOps(val ops: Int, val mode: Int)

/**
 * The Kotlin path's per-sample branch order (dv-golden README "Per-sample order") as native ops.
 * [dv5Strip] is the stream's DV5 strip decision; it is evaluated (and may advance the decision
 * window) only for a profile-5 sample under strip, as on the Kotlin path.
 */
internal fun mkvNativeOps(
    config: DolbyVisionConversionConfig,
    stripRpuOnly: Boolean,
    stripHdr10PlusSei: Boolean,
    profile: Int?,
    dv5Strip: () -> Boolean
): MkvNativeOps {
    val hdr10Plus = if (stripHdr10PlusSei) DoviBridge.MKV_OP_STRIP_HDR10PLUS else 0
    if (stripRpuOnly) {
        val strip = profile != 5 || dv5Strip()
        return MkvNativeOps(hdr10Plus or (if (strip) DoviBridge.MKV_OP_STRIP_RPU else 0), 1)
    }
    if (!config.shouldConvert(profile) || (profile == 5 && !config.convertDv5Rpu)) {
        return MkvNativeOps(hdr10Plus, 1)
    }
    return MkvNativeOps(hdr10Plus or DoviBridge.MKV_OP_CONVERT, config.conversionMode(profile))
}
