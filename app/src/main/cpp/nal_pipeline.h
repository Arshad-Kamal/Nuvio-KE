// Spec 21 N3: JNI-free HEVC per-sample NAL pipeline, one native home for MKV (length-prefixed)
// and MP4/TS (Annex-B) samples. dovi_bridge.cpp wraps it for JNI; scripts/test-dv-native.sh
// runs it on the host against the dv-golden corpus (contract:
// app/src/test/resources/dv-golden/README.md) and the frozen pre-N3 Annex-B walk.
//
// Once-per-stream policy (DV5 strip decision, RPU probes, HDR10 SEI injection, the abandon
// threshold's value) stays with the caller (Kotlin); this core is per sample and pure: same
// input + same state in, same bytes + same state out. That makes the overflow retry safe.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace nal {

// Converts one RPU NAL (`nal[0, len)`, 2-byte NAL header included) for libdovi conversion
// `mode` into `out` (replacing its contents). Returns false on failure; an empty `out` also
// counts as failure. Must be deterministic (the overflow retry re-runs the walk).
using ConvertFn = bool (*)(void* ctx, const uint8_t* nal, size_t len, int mode, std::vector<uint8_t>& out);

struct RpuConverter {
    ConvertFn fn = nullptr;  // null = no converter (stub build)
    void* ctx = nullptr;
};

// Output sink: fills the caller's fixed buffer while it fits; past its capacity it moves the
// bytes to `spill` and continues there, so a walk always completes and the caller learns the
// size it needs. With buf == nullptr and cap == 0 it is a plain growable vector.
class Writer {
public:
    Writer(uint8_t* buf, size_t cap, std::vector<uint8_t>& spill) : buf_(buf), cap_(cap), spill_(spill) {}
    void reset() {
        len_ = 0;
        spilled_ = false;
        spill_.clear();
    }
    void put(const uint8_t* p, size_t n);
    void put_byte(uint8_t b) { put(&b, 1); }
    size_t size() const { return len_; }
    const uint8_t* data() const { return spilled_ ? spill_.data() : buf_; }
    // True when the bytes no longer fit the fixed buffer (they are in `spill`).
    bool spilled() const { return spilled_; }

private:
    uint8_t* buf_;
    size_t cap_;
    std::vector<uint8_t>& spill_;
    size_t len_ = 0;
    bool spilled_ = false;
};

// Reusable per-thread buffers; steady state allocates nothing.
struct Workspace {
    std::vector<uint8_t> spill;      // the main Writer's overflow
    std::vector<uint8_t> step3;      // HDR10+ output when its input is the main Writer's bytes
    std::vector<uint8_t> converted;  // one converted RPU
    std::vector<uint8_t> rbsp;       // SEI filter: unescaped NAL
    std::vector<uint8_t> kept;       // SEI filter: kept messages
    std::vector<uint8_t> filtered;   // SEI filter: re-escaped NAL
};

// ── MP4/TS (Annex-B) ──
// The pre-N3 nativeProcessVideoSample walk, unchanged: per NAL, RPU (type 62) strip or
// convert (failure drops it; no converter keeps it), EL drop (layer > 0 or type 63) when
// converting or stripping, HDR10+ SEI filter on types 39/40. One deliberate difference, per
// the ConvertFn contract: a converter "success" with empty output counts as a failure (the
// pre-N3 walk wrote a bare start code; libdovi's convert_rpu_nal never returns that).
struct AnnexBOps {
    bool convert = false;
    int mode = 1;
    bool strip_rpu = false;
    bool strip_hdr10plus = false;
};

struct AnnexBResult {
    bool changed = false;     // output = out.data()[0, out.size()); may be empty
    uint32_t rpu_drops = 0;   // RPUs dropped on conversion failure
};

AnnexBResult process_annexb(const uint8_t* in, size_t n, const AnnexBOps& ops,
                            const RpuConverter& converter, Writer& out, Workspace& ws);

// ── MKV (length-prefixed) ──
// Steps 1-3 of the README's per-sample order; the caller picks the ops per sample.
struct RpuFailureState {
    uint32_t consecutive = 0;  // consecutive conversion failures
    bool abandoned = false;    // conversion abandoned for the stream
};

struct LengthPrefixedOps {
    bool strip_rpu = false;        // step 1 (exclusive with convert)
    bool convert = false;          // step 2
    int mode = 1;
    uint32_t abandon_threshold = 0;  // caller's policy, must be set (>= 1; Kotlin uses 60)
    bool strip_hdr10plus = false;  // step 3
};

struct LengthPrefixedResult {
    bool changed = false;          // output = out.data()[0, out.size()); may be empty (step 1)
    bool rpu_stripped = false;     // step 1 dropped a NAL (gates the caller's HDR10 SEI injection)
    uint32_t rpu_drops = 0;        // RPUs dropped on conversion failure or after abandon
    uint32_t convert_calls = 0;    // RPUs offered for conversion (none after abandon; counted with no converter too)
    uint32_t converted = 0;        // converter calls that succeeded
    RpuFailureState state;         // updated failure state (persists even when unchanged)
};

// lfl = NAL length-field bytes; outside 1..4 the sample is unchanged.
LengthPrefixedResult process_length_prefixed(const uint8_t* in, size_t n, int lfl,
                                             const LengthPrefixedOps& ops, RpuFailureState state,
                                             const RpuConverter& converter, Writer& out, Workspace& ws);

}  // namespace nal
