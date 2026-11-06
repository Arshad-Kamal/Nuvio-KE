package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.util.safeHost
import java.util.concurrent.atomic.AtomicLong

object DoviBridge {
    private const val TAG = "DoviBridge"
    private const val LIB_NAME = "dovi_bridge"

    data class RealtimeConversionProbe(
        val supported: Boolean,
        val reason: String,
        val bridgeVersion: String?,
        val extractorHookReady: Boolean
    )

    private val nativeLoaded: Boolean by lazy { loadNativeLibrary() }
    private val conversionCallCount = AtomicLong(0L)
    private val conversionSuccessCount = AtomicLong(0L)

    val isNativeEnabledInBuild: Boolean
        get() = BuildConfig.DOVI_NATIVE_ENABLED

    val isExtractorHookReadyInBuild: Boolean
        get() = BuildConfig.DOVI_EXTRACTOR_HOOK_READY

    val isLibraryLoaded: Boolean
        get() = nativeLoaded

    fun isAvailable(): Boolean = isNativeEnabledInBuild && nativeLoaded

    fun getBridgeVersionOrNull(): String? {
        if (!isAvailable()) return null
        return runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "Failed to read bridge version: ${it.message}") }
            .getOrNull()
    }

    fun probeRealtimeConversionSupport(streamUrl: String): RealtimeConversionProbe {
        if (!isNativeEnabledInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-disabled-in-build",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild
            )
        }
        if (!nativeLoaded) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-library-load-failed",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild
            )
        }
        if (!isExtractorHookReadyInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "extractor-hook-not-integrated",
                bridgeVersion = getBridgeVersionOrNull(),
                extractorHookReady = false
            )
        }

        val bridgeVersion = runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "probe version failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrNull()

        // True only when libdovi is linked (DOVI_REAL_LINKED); a stub build reports false.
        val ready = runCatching { nativeIsConversionPathReady() }
            .onFailure { Log.w(TAG, "probe readiness failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrDefault(false)

        return RealtimeConversionProbe(
            supported = ready,
            reason = if (ready) "ready" else "bridge-reports-not-ready",
            bridgeVersion = bridgeVersion,
            extractorHookReady = true
        )
    }

    fun resetRuntimeCounters() {
        conversionCallCount.set(0L)
        conversionSuccessCount.set(0L)
    }

    fun getConversionCallCount(): Long = conversionCallCount.get()

    fun getConversionSuccessCount(): Long = conversionSuccessCount.get()

    /** Conversions the native MKV pipeline ran itself (it calls libdovi without [convertDv7RpuToDv81NonAllocating]). */
    internal fun recordConversions(calls: Int, successes: Int) {
        if (calls > 0) conversionCallCount.addAndGet(calls.toLong())
        if (successes > 0) conversionSuccessCount.addAndGet(successes.toLong())
    }

    /**
     * Caller-owned output buffer for the non-allocating native calls: one per stream pipeline
     * (MKV transformer, MP4/TS track output), used on that pipeline's loader thread only, so no
     * buffer is shared across streams. Sized for typical RPU NALs; grows when the native side
     * reports a larger required size (negative-return contract), so read [bytes] after the call.
     */
    class OutBuffer(initialSize: Int = 4096) {
        var bytes = ByteArray(initialSize)
            private set

        internal fun grow(required: Int) {
            bytes = ByteArray(maxOf(required, bytes.size * 2))
        }

        /** Pre-sizes for a whole-sample rewrite, so a large frame skips the failed native pass. */
        internal fun ensureCapacity(min: Int) {
            if (bytes.size < min) grow(min)
        }
    }

    // ── DV7 F3: FEL/MEL detection result codes ──
    const val EL_TYPE_FEL = 2
    const val EL_TYPE_MEL = 1
    const val EL_TYPE_NONE = 0

    /**
     * DV7 F3: detects the profile-7 enhancement-layer type from an RPU NAL via
     * libdovi's pre-derived header field. Returns [EL_TYPE_FEL], [EL_TYPE_MEL],
     * [EL_TYPE_NONE] (parsed, not P7), -1 (parse failure) or -2 (bridge
     * unavailable). Intended to run once per stream on the first RPU.
     */
    fun detectRpuElType(sample: ByteArray, offset: Int, len: Int): Int {
        if (!isAvailable() || len <= 0) return -2
        return runCatching { nativeDetectRpuElType(sample, offset, len) }
            .onFailure { Log.w(TAG, "EL-type detection failed: ${it.message}") }
            .getOrDefault(-2)
    }

    /**
     * Item 2 (RPU-informed diagnostics): the DV RPU's static HDR mastering
     * metadata. Fields absent from the RPU are null (L6 is optional; the
     * source_*_pq pair is always present when DM metadata exists). Values are
     * raw as libdovi reports them: source_*_pq are 12-bit PQ codes; the L6
     * luminance and light-level values follow the ST2086/CTA-861.3 conventions.
     */
    data class RpuStaticMetadata(
        val sourceMinPq: Int,
        val sourceMaxPq: Int,
        val l6MinMasteringLuminance: Int?,
        val l6MaxMasteringLuminance: Int?,
        val maxCll: Int?,
        val maxFall: Int?
    ) {
        /**
         * A one-line human-readable summary for the Diagnostics card. MaxCLL /
         * MaxFALL are shown as libdovi reports them (nits); the mastering-display
         * peak is derived from [sourceMaxPq] via the ST 2084 (PQ) EOTF, which is
         * always available.
         */
        fun toDiagnosticLine(): String {
            val parts = ArrayList<String>(3)
            // A present L6 block with a zero content-light value means "unknown"
            // (common in WEB-DL masters), not literally zero nits - show a dash
            // rather than a misleading "0". A wholly absent L6 stays omitted.
            maxCll?.let { parts += "MaxCLL ${if (it > 0) it.toString() else "-"}" }
            maxFall?.let { parts += "MaxFALL ${if (it > 0) it.toString() else "-"}" }
            parts += "MDL ~${Math.round(Hdr10SeiInjector.pqCodeToNits(sourceMaxPq))} nits"
            return parts.joinToString(" · ")
        }
    }

    /**
     * Reads [RpuStaticMetadata] from an RPU NAL via the bundled libdovi 3.3.2
     * readers (no library bump). Returns null on a stub build, when the bridge
     * is unavailable, on parse failure, or when the RPU carries no DM metadata.
     * Intended to run once per stream on the first RPU, like [detectRpuElType].
     */
    fun getRpuStaticMetadata(sample: ByteArray, offset: Int, len: Int): RpuStaticMetadata? {
        if (!isAvailable() || len <= 0) return null
        val v = runCatching { nativeGetRpuStaticMetadata(sample, offset, len) }
            .onFailure { Log.w(TAG, "RPU metadata read failed: ${it.message}") }
            .getOrNull() ?: return null
        if (v.size < 6) return null
        fun opt(i: Int): Int? = v[i].takeIf { it >= 0 }
        return RpuStaticMetadata(
            sourceMinPq = v[0],
            sourceMaxPq = v[1],
            l6MinMasteringLuminance = opt(2),
            l6MaxMasteringLuminance = opt(3),
            maxCll = opt(4),
            maxFall = opt(5)
        )
    }

    /**
     * Converts a DV7 RPU NAL to DV8.1 into [out] with no per-call JVM allocation.
     *
     * Returns the number of bytes written (> 0) on success, or 0 on failure. If the native
     * output does not fit in [out], the native layer returns the negative required
     * size; we grow the buffer to that size and retry exactly once instead of truncating.
     */
    fun convertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        out: OutBuffer,
        mode: Int = 1
    ): Int {
        if (!isAvailable() || len <= 0) return 0
        conversionCallCount.incrementAndGet()
        var written = runCatching {
            nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, out.bytes, mode)
        }.onFailure { Log.w(TAG, "Non-allocating conversion failed: ${it.message}") }
            .getOrDefault(0)
        if (written < 0) {
            // Output didn't fit: grow the caller's buffer to the required size and retry once.
            out.grow(-written)
            written = runCatching {
                nativeConvertDv7RpuToDv81NonAllocating(sample, offset, len, out.bytes, mode)
            }.onFailure { Log.w(TAG, "Non-allocating retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        if (written > 0) {
            conversionSuccessCount.incrementAndGet()
        }
        return written
    }

    /**
     * Processes an Annex-B HEVC video sample (`sample[0, sampleLen)`) in the native layer.
     * Optionally converts or strips Dolby Vision RPUs, and strips HDR10+ SEIs.
     * Returns the size of the rewritten sample in [out], or 0 if no changes were made.
     * If the output buffer was too small, grows the buffer and retries once.
     */
    fun processVideoSampleNonAllocating(
        sample: ByteArray,
        sampleLen: Int,
        out: OutBuffer,
        convertDovi: Boolean,
        doviMode: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int {
        if (!isAvailable() || sampleLen <= 0) return 0
        // The rewrite is at most the sample's size in practice (EL/SEI drops shrink it; a
        // converted RPU that grows it is covered by the retry below).
        out.ensureCapacity(sampleLen)

        var written = runCatching {
            nativeProcessVideoSample(
                sample = sample,
                sampleLen = sampleLen,
                outBuffer = out.bytes,
                convertDovi = convertDovi,
                doviMode = doviMode,
                stripDoviRpu = stripDoviRpu,
                stripHdr10Plus = stripHdr10Plus
            )
        }.onFailure { Log.w(TAG, "nativeProcessVideoSample failed: ${it.message}") }
            .getOrDefault(0)

        if (written < 0) {
            out.grow(-written)
            written = runCatching {
                nativeProcessVideoSample(
                    sample = sample,
                    sampleLen = sampleLen,
                    outBuffer = out.bytes,
                    convertDovi = convertDovi,
                    doviMode = doviMode,
                    stripDoviRpu = stripDoviRpu,
                    stripHdr10Plus = stripHdr10Plus
                )
            }.onFailure { Log.w(TAG, "nativeProcessVideoSample retry failed: ${it.message}") }
                .getOrDefault(0)
        }
        return written
    }

    // Spec 21 N4: MKV per-sample ops and state layout (dovi_bridge.cpp nativeProcessLengthPrefixedSample).
    const val MKV_OP_STRIP_RPU = 1
    const val MKV_OP_CONVERT = 2
    const val MKV_OP_STRIP_HDR10PLUS = 4
    const val MKV_STATE_CONSECUTIVE_FAILURES = 0
    const val MKV_STATE_ABANDONED = 1
    const val MKV_STATE_DROPS = 2
    const val MKV_STATE_FLAGS = 3
    const val MKV_STATE_CONVERT_CALLS = 4
    const val MKV_STATE_CONVERTED = 5
    const val MKV_STATE_SIZE = 6
    const val MKV_FLAG_CHANGED = 1
    const val MKV_FLAG_RPU_STRIPPED = 2

    /**
     * Spec 21 N4: runs this MKV sample's steps ([ops], MKV_OP_*) in the native pipeline. [state]
     * (int[[MKV_STATE_SIZE]], MKV_STATE_*) carries the stream's RPU failure state in and out, plus
     * this call's drop count, flags and converter call/success counts (fold them in with
     * [recordConversions]); the output is `out.bytes[0, return)` when the CHANGED flag is set
     * (0 bytes is valid). Grows [out] and retries once when the output does not fit (the native
     * core is pure, so the retry repeats the same result). Throws [LinkageError] when the loaded
     * library lacks the entry point; the caller falls back to the Kotlin path. Requires [isAvailable].
     */
    fun processLengthPrefixedSample(
        sample: ByteArray,
        sampleLen: Int,
        lengthFieldBytes: Int,
        out: OutBuffer,
        ops: Int,
        doviMode: Int,
        abandonThreshold: Int,
        state: IntArray
    ): Int {
        out.ensureCapacity(sampleLen)
        var written = nativeProcessLengthPrefixedSample(
            sample, sampleLen, lengthFieldBytes, out.bytes, ops, doviMode, abandonThreshold, state
        )
        if (written < 0) {
            out.grow(-written)
            written = nativeProcessLengthPrefixedSample(
                sample, sampleLen, lengthFieldBytes, out.bytes, ops, doviMode, abandonThreshold, state
            )
        }
        // A second overflow cannot happen (same input, larger buffer); if it did, the flags say
        // unchanged and the failure state is untouched.
        return written.coerceAtLeast(0)
    }

    private fun loadNativeLibrary(): Boolean {
        if (!isNativeEnabledInBuild) {
            return false
        }
        return try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Loaded native library: $LIB_NAME")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load native library $LIB_NAME: ${t.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeIsConversionPathReady(): Boolean

    // DV7 F3: FEL/MEL detection.
    @JvmStatic
    private external fun nativeDetectRpuElType(sample: ByteArray, offset: Int, length: Int): Int

    // Item 2: RPU static HDR metadata read (MDL + MaxCLL/MaxFALL). Returns
    // int[6] or null; see [getRpuStaticMetadata].
    @JvmStatic
    private external fun nativeGetRpuStaticMetadata(
        sample: ByteArray,
        offset: Int,
        length: Int
    ): IntArray?

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81NonAllocating(
        sample: ByteArray,
        offset: Int,
        len: Int,
        outBuffer: ByteArray,
        mode: Int
    ): Int

    @JvmStatic
    private external fun nativeProcessLengthPrefixedSample(
        sample: ByteArray,
        sampleLen: Int,
        lengthFieldBytes: Int,
        outBuffer: ByteArray,
        ops: Int,
        doviMode: Int,
        abandonThreshold: Int,
        state: IntArray
    ): Int

    @JvmStatic
    private external fun nativeProcessVideoSample(
        sample: ByteArray,
        sampleLen: Int,
        outBuffer: ByteArray,
        convertDovi: Boolean,
        doviMode: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int
}

