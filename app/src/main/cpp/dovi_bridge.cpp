#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

#include "nal_pipeline.h"

#define LOG_TAG "DoviBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef DOVI_REAL_LINKED
#define DOVI_REAL_LINKED 0
#endif

#if DOVI_REAL_LINKED
// DV7 F3: include the bundled libdovi header instead of hand-declared
// prototypes. The EL-type accessor needs DoviRpuDataHeader plus
// dovi_rpu_get_header/dovi_rpu_free_header, and using the real header
// (verified: identical prototypes for all previously hand-declared
// functions, C++ guards present, no local name collisions) removes the
// drift risk between hand-written declarations and the linked library.
#include <libdovi/rpu_parser.h>

static inline bool dovi_has_error(const DoviRpuOpaque* rpu) {
    if (rpu == nullptr) return true;
    const char* error = dovi_rpu_get_error(rpu);
    return error != nullptr && error[0] != '\0';
}

// Tries the unspec62 NAL parser (RPU with its 7C 01 NAL header) first, then the raw RPU
// parser. Stateless on purpose: every call tries both, so concurrent loaders cannot race
// and a header-less RPU cannot switch the header-bearing path off for later ones.
static inline DoviRpuOpaque* dovi_parse_any_rpu(const uint8_t* data, size_t len) {
    if (data == nullptr || len == 0U) return nullptr;
    DoviRpuOpaque* parsed = dovi_parse_unspec62_nalu(data, len);
    if (parsed != nullptr && !dovi_has_error(parsed)) return parsed;
    if (parsed != nullptr) dovi_rpu_free(parsed);
    parsed = dovi_parse_rpu(data, len);
    if (parsed != nullptr && !dovi_has_error(parsed)) return parsed;
    if (parsed != nullptr) dovi_rpu_free(parsed);
    return nullptr;
}

// Kotlin passes 0..4 (DolbyVisionConversionConfig.conversionMode); anything else maps to
// mode 2 (To81).
static inline uint8_t map_conversion_mode(jint mode) {
    return (mode >= 0 && mode <= 4) ? static_cast<uint8_t>(mode) : 2U;
}

// The one RPU rewrite used by every entry point: parse an RPU NAL, convert it with `mode`
// and write it back as an unspec62 NAL into `out`, with nuh_layer_id cleared in the 2-byte
// NAL header of our copy (never mutating libdovi-owned memory).
// Returns false on any failure. Frees every libdovi object it receives, on every path.
static bool convert_rpu_nal(const uint8_t* data, size_t len, jint mode, std::vector<uint8_t>& out) {
    DoviRpuOpaque* rpu = dovi_parse_any_rpu(data, len);
    if (rpu == nullptr) return false;
    bool ok = false;
    if (dovi_convert_rpu_with_mode(rpu, map_conversion_mode(mode)) >= 0) {
        const DoviData* written = dovi_write_unspec62_nalu(rpu);
        if (written != nullptr) {
            if (written->data != nullptr && written->len > 0U) {
                out.assign(written->data, written->data + written->len);
                if (out.size() >= 2) {
                    out[0] &= 0xFE;
                    out[1] &= 0x07;
                }
                ok = true;
            }
            dovi_data_free(written);
        }
    }
    dovi_rpu_free(rpu);
    return ok;
}

// DV7 review F5: counts RPUs dropped because per-frame conversion failed on
// the MP4/TS sample path, with a throttled log (first drop + every 100th).
// Process-wide by design: it is diagnostics-only and monotonically counts;
// worst-case cross-stream interleave slightly skews the throttle cadence.
static std::atomic<uint64_t> g_rpuDropCount{0};
static inline void noteRpuDropOnFailure() {
    const uint64_t n = g_rpuDropCount.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1 || n % 100 == 0) {
        LOGW("DV7_NATIVE: RPU conversion failed; dropping RPU (dropped=%llu)",
             static_cast<unsigned long long>(n));
    }
}
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeGetBridgeVersion(JNIEnv* env, jclass /* clazz */) {
#if DOVI_REAL_LINKED
    return env->NewStringUTF("dovi-bridge-libdovi-capi-0.2");
#else
    return env->NewStringUTF("dovi-bridge-stub-0.1");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeIsConversionPathReady(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
#if DOVI_REAL_LINKED
    LOGI("native conversion path: libdovi linked (real mode)");
    return JNI_TRUE;
#else
    LOGI("native conversion path not linked (stub mode)");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeConvertDv7RpuToDv81NonAllocating(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray sample,
    jint offset,
    jint len,
    jbyteArray outBuffer,
    jint mode
) {
#if DOVI_REAL_LINKED
    if (sample == nullptr || outBuffer == nullptr || len <= 0 || offset < 0) return 0;
    if (offset > env->GetArrayLength(sample) - len) return 0;

    // RPU NALs are tiny (hundreds of bytes): copy them out with GetByteArrayRegion (no
    // GC-suspending critical section) into thread_local buffers, so steady state stays
    // allocation-free, then run the libdovi parse on the copy.
    thread_local std::vector<uint8_t> input;
    thread_local std::vector<uint8_t> converted;
    input.resize(static_cast<size_t>(len));
    env->GetByteArrayRegion(sample, offset, len, reinterpret_cast<jbyte*>(input.data()));

    if (!convert_rpu_nal(input.data(), input.size(), mode, converted)) return 0;
    if (converted.size() > static_cast<size_t>(INT32_MAX)) return 0;

    const jsize outLen = static_cast<jsize>(converted.size());
    if (outLen > env->GetArrayLength(outBuffer)) {
        // Never silently truncate (a clipped RPU corrupts the frame). Signal the required
        // size with a negative return; Kotlin grows the reusable buffer and retries once.
        return -outLen;
    }
    env->SetByteArrayRegion(outBuffer, 0, outLen, reinterpret_cast<const jbyte*>(converted.data()));
    return outLen; // bytes written
#else
    (void)env;
    (void)sample;
    (void)offset;
    (void)len;
    (void)outBuffer;
    (void)mode;
    return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeDetectRpuElType(
        JNIEnv* env,
        jclass /* clazz */,
        jbyteArray sample,
        jint offset,
        jint length) {
    // ── DV7 F3: FEL/MEL detection ──
    // Returns: 2 = FEL, 1 = MEL, 0 = parsed but no EL type (not profile 7),
    //          -1 = parse failure, -2 = stub build / bad args.
#if DOVI_REAL_LINKED
    if (sample == nullptr || offset < 0 || length <= 0) return -2;
    const jsize arrLen = env->GetArrayLength(sample);
    if (offset > arrLen - length) return -2;
    std::vector<uint8_t> input(static_cast<size_t>(length));
    env->GetByteArrayRegion(sample, offset, length, reinterpret_cast<jbyte*>(input.data()));
    DoviRpuOpaque* rpu = dovi_parse_any_rpu(input.data(), input.size());
    if (rpu == nullptr) return -1;
    jint result = 0;
    const DoviRpuDataHeader* header = dovi_rpu_get_header(rpu);
    if (header != nullptr) {
        if (header->el_type != nullptr) {
            if (std::strcmp(header->el_type, "FEL") == 0) {
                result = 2;
            } else if (std::strcmp(header->el_type, "MEL") == 0) {
                result = 1;
            }
        }
        dovi_rpu_free_header(header);
    }
    dovi_rpu_free(rpu);
    return result;
#else
    (void) env; (void) sample; (void) offset; (void) length;
    return -2;
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeGetRpuStaticMetadata(
        JNIEnv* env,
        jclass /* clazz */,
        jbyteArray sample,
        jint offset,
        jint length) {
    // Item 2 (RPU-informed diagnostics): reads the DV RPU's static HDR mastering
    // metadata via the bundled libdovi 3.3.2 readers (no library bump) and returns
    //   int[6] = { source_min_pq, source_max_pq,
    //              L6 min_display_mastering_luminance, L6 max_display_mastering_luminance,
    //              L6 max_content_light_level (MaxCLL), L6 max_frame_average_light_level (MaxFALL) }
    // Any field absent from the RPU is -1 (L6 is a nullable block; source_*_pq are
    // always present when vdr_dm_data exists). Returns null when the RPU carries no
    // DM metadata, on parse failure, or in a stub build. Intended to run once per
    // stream on the first RPU, mirroring nativeDetectRpuElType.
#if DOVI_REAL_LINKED
    if (sample == nullptr || offset < 0 || length <= 0) return nullptr;
    const jsize arrLen = env->GetArrayLength(sample);
    if (offset > arrLen - length) return nullptr;

    std::vector<uint8_t> input(static_cast<size_t>(length));
    env->GetByteArrayRegion(sample, offset, length, reinterpret_cast<jbyte*>(input.data()));

    DoviRpuOpaque* rpu = dovi_parse_any_rpu(input.data(), input.size());
    if (rpu == nullptr) return nullptr;

    const DoviVdrDmData* dm = dovi_rpu_get_vdr_dm_data(rpu);
    if (dm == nullptr) {
        dovi_rpu_free(rpu);
        return nullptr;
    }

    jint values[6];
    values[0] = static_cast<jint>(dm->source_min_pq);
    values[1] = static_cast<jint>(dm->source_max_pq);
    const DoviExtMetadataBlockLevel6* l6 = dm->dm_data.level6;
    if (l6 != nullptr) {
        values[2] = static_cast<jint>(l6->min_display_mastering_luminance);
        values[3] = static_cast<jint>(l6->max_display_mastering_luminance);
        values[4] = static_cast<jint>(l6->max_content_light_level);
        values[5] = static_cast<jint>(l6->max_frame_average_light_level);
    } else {
        values[2] = -1;
        values[3] = -1;
        values[4] = -1;
        values[5] = -1;
    }

    dovi_rpu_free_vdr_dm_data(dm);
    dovi_rpu_free(rpu);

    jintArray out = env->NewIntArray(6);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 6, values);
    return out;
#else
    (void) env; (void) sample; (void) offset; (void) length;
    return nullptr;
#endif
}

// ── Per-sample NAL pipeline (Spec 21 N3: the JNI-free core is nal_pipeline.cpp) ──

#if DOVI_REAL_LINKED
static bool libdovi_convert(void* /* ctx */, const uint8_t* nal, size_t len, int mode, std::vector<uint8_t>& out) {
    return convert_rpu_nal(nal, len, mode, out);
}
static const nal::RpuConverter kRpuConverter{libdovi_convert, nullptr};
#else
static const nal::RpuConverter kRpuConverter{};  // stub build: no conversion
#endif

// One per loader thread, shared by both entry points (its buffers grow to the largest frame).
thread_local nal::Workspace t_workspace;

// Pins a Java byte array for the walk. The core reads the sample and writes the output buffer
// in place, so a frame costs no full-frame JNI copy in either direction (the pre-N3 path
// copied the sample in and the output out); the two arrays must differ. Inside the pinned
// span the code makes no JNI call and never blocks on Java threads; libdovi conversion is
// pure native compute.
class PinnedBytes {
public:
    PinnedBytes(JNIEnv* env, jbyteArray array, bool readOnly)
        : env_(env), array_(array),
          ptr_(static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(array, nullptr))),
          mode_(readOnly ? JNI_ABORT : 0) {}
    ~PinnedBytes() {
        if (ptr_ != nullptr) env_->ReleasePrimitiveArrayCritical(array_, ptr_, mode_);
    }
    PinnedBytes(const PinnedBytes&) = delete;
    PinnedBytes& operator=(const PinnedBytes&) = delete;
    uint8_t* get() const { return ptr_; }
    // Nothing written needs committing (only matters if the VM handed out a copy).
    void discard() { mode_ = JNI_ABORT; }

private:
    JNIEnv* env_;
    jbyteArray array_;
    uint8_t* ptr_;
    jint mode_;
};

// MP4/TS (Annex-B). Returns the rewritten length in outBuffer, 0 when unchanged (or when
// every NAL was dropped), or -required when outBuffer is too small (call again, larger).
// outBuffer must not be sample (then: 0).
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeProcessVideoSample(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray sample,
    jint sampleLen,
    jbyteArray outBuffer,
    jboolean convertDovi,
    jint doviMode,
    jboolean stripDoviRpu,
    jboolean stripHdr10Plus
) {
    if (sample == nullptr || outBuffer == nullptr || sampleLen <= 0) return 0;
    if (env->IsSameObject(sample, outBuffer)) return 0;
    if (sampleLen > env->GetArrayLength(sample)) return 0;
    const jsize outCap = env->GetArrayLength(outBuffer);

    nal::AnnexBOps ops;
    ops.convert = convertDovi == JNI_TRUE;
    ops.mode = doviMode;
    ops.strip_rpu = stripDoviRpu == JNI_TRUE;
    ops.strip_hdr10plus = stripHdr10Plus == JNI_TRUE;

    nal::AnnexBResult result;
    size_t outLen = 0;
    bool spilled = false;
    {
        PinnedBytes in(env, sample, true);
        if (in.get() == nullptr) return 0;
        PinnedBytes out(env, outBuffer, false);
        if (out.get() == nullptr) return 0;
        nal::Writer writer(out.get(), static_cast<size_t>(outCap), t_workspace.spill);
        result = nal::process_annexb(in.get(), static_cast<size_t>(sampleLen), ops, kRpuConverter, writer,
                                     t_workspace);
        outLen = writer.size();
        spilled = writer.spilled();
        if (!result.changed || spilled) out.discard();
    }
    if (!result.changed || outLen > static_cast<size_t>(INT32_MAX)) return 0;
    if (spilled) return -static_cast<jint>(outLen);  // the retry recomputes; log drops once
#if DOVI_REAL_LINKED
    for (uint32_t i = 0; i < result.rpu_drops; ++i) noteRpuDropOnFailure();
#endif
    return static_cast<jint>(outLen);
}

// MKV (length-prefixed), steps 1-3 of the dv-golden README; the caller (Kotlin) owns the
// once-per-stream policy, the abandon threshold and the RPU drop diagnostics, and passes this
// sample's ops:
//   ops bits: 1 = strip RPU/EL (step 1), 2 = convert (step 2), 4 = strip HDR10+ SEI (step 3).
//   state (int[6], DoviBridge.MKV_STATE_*): in [0] consecutive RPU failures, [1] abandoned (0/1);
//                   out [2] RPUs dropped by this call, [3] flags: 1 = changed,
//                   2 = step 1 dropped a NAL, [4] converter calls, [5] conversions that
//                   succeeded; [0], [1] updated when the call completes.
// Returns >= 0: done, output = outBuffer[0, ret) when flags & 1 (a 0-byte output is valid).
// Returns < 0: outBuffer too small, -ret bytes needed, [0] and [1] untouched; call again with a
// larger buffer (the core is pure, so the retry yields the same result). Bad arguments, a
// failed pin or outBuffer == sample: 0 with flags 0 (unchanged).
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_DoviBridge_nativeProcessLengthPrefixedSample(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray sample,
    jint sampleLen,
    jint lengthFieldBytes,
    jbyteArray outBuffer,
    jint ops,
    jint doviMode,
    jint abandonThreshold,
    jintArray state
) {
    constexpr jsize kStateSize = 6;
    if (state == nullptr || env->GetArrayLength(state) < kStateSize) return 0;
    jint io[kStateSize] = {};
    env->GetIntArrayRegion(state, 0, 2, io);
    // Outputs read "unchanged" until the walk completes; written before pinning, so no JNI call
    // follows a failed pin (which may leave an exception pending).
    env->SetIntArrayRegion(state, 2, kStateSize - 2, io + 2);
    if (sample == nullptr || outBuffer == nullptr || sampleLen < 0 || abandonThreshold < 1 ||
        env->IsSameObject(sample, outBuffer) || sampleLen > env->GetArrayLength(sample)) {
        return 0;
    }
    const jsize outCap = env->GetArrayLength(outBuffer);

    nal::LengthPrefixedOps lpOps;
    lpOps.strip_rpu = (ops & 1) != 0;
    lpOps.convert = (ops & 2) != 0;
    lpOps.strip_hdr10plus = (ops & 4) != 0;
    lpOps.mode = doviMode;
    lpOps.abandon_threshold = static_cast<uint32_t>(abandonThreshold);
    nal::RpuFailureState failures;
    failures.consecutive = io[0] > 0 ? static_cast<uint32_t>(io[0]) : 0U;
    failures.abandoned = io[1] != 0;

    nal::LengthPrefixedResult result;
    size_t outLen = 0;
    bool spilled = false;
    {
        PinnedBytes in(env, sample, true);
        if (in.get() == nullptr) return 0;
        PinnedBytes out(env, outBuffer, false);
        if (out.get() == nullptr) return 0;
        nal::Writer writer(out.get(), static_cast<size_t>(outCap), t_workspace.spill);
        result = nal::process_length_prefixed(in.get(), static_cast<size_t>(sampleLen), lengthFieldBytes,
                                              lpOps, failures, kRpuConverter, writer, t_workspace);
        outLen = writer.size();
        spilled = result.changed && writer.spilled();
        if (!result.changed || spilled) out.discard();
    }
    if (spilled && outLen <= static_cast<size_t>(INT32_MAX)) return -static_cast<jint>(outLen);
    if (spilled) result.changed = false;  // size not representable: keep the sample
    io[0] = static_cast<jint>(std::min<uint32_t>(result.state.consecutive, INT32_MAX));
    io[1] = result.state.abandoned ? 1 : 0;
    io[2] = static_cast<jint>(result.rpu_drops);
    io[3] = (result.changed ? 1 : 0) | (result.rpu_stripped ? 2 : 0);
    io[4] = static_cast<jint>(result.convert_calls);
    io[5] = static_cast<jint>(result.converted);
    env->SetIntArrayRegion(state, 0, kStateSize, io);
    return result.changed ? static_cast<jint>(outLen) : 0;
}
