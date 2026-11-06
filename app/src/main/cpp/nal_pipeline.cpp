// Spec 21 N3: JNI-free per-sample NAL pipeline (see nal_pipeline.h). Byte behaviour is pinned
// by the dv-golden corpus (MKV, README "Per-sample order") and the frozen pre-N3 Annex-B walk;
// quirks are kept on purpose (equivalence, not unification).
#include "nal_pipeline.h"

#include <cstring>

namespace nal {

void Writer::put(const uint8_t* p, size_t n) {
    if (n == 0) return;
    if (!spilled_ && len_ + n <= cap_) {
        std::memcpy(buf_ + len_, p, n);
        len_ += n;
        return;
    }
    if (!spilled_) {
        spill_.assign(buf_, buf_ + len_);
        spilled_ = true;
    }
    spill_.insert(spill_.end(), p, p + n);
    len_ += n;
}

namespace {

constexpr int kTypePrefixSei = 39;
constexpr int kTypeSuffixSei = 40;
constexpr int kTypeRpu = 62;
constexpr int kTypeEl = 63;
constexpr uint8_t kHdr10PlusSig[] = {0xB5, 0x00, 0x3C, 0x00, 0x01};
constexpr size_t kHdr10PlusSigLen = sizeof(kHdr10PlusSig);

inline int nal_type(uint8_t header0) { return (header0 >> 1) & 0x3F; }

inline int layer_id(uint8_t b0, uint8_t b1) { return ((b0 & 0x01) << 5) | ((b1 & 0xF8) >> 3); }

// ── HDR10+ SEI filter (one SEI NAL) ──

// The HDR10+ per-NAL filter (README step 3b). Returns false when the NAL is kept as is (too
// short, malformed, or no HDR10+ message); true with `ws.filtered` empty when it is dropped;
// true with `ws.filtered` = the rewritten NAL otherwise.
bool filter_sei_nal(const uint8_t* nal, size_t size, Workspace& ws) {
    if (size < 3) return false;

    std::vector<uint8_t>& rbsp = ws.rbsp;
    rbsp.clear();
    rbsp.push_back(nal[0]);
    rbsp.push_back(nal[1]);
    for (size_t i = 2; i < size;) {
        if (i + 2 < size && nal[i] == 0x00 && nal[i + 1] == 0x00 && nal[i + 2] == 0x03) {
            rbsp.push_back(0x00);
            rbsp.push_back(0x00);
            i += 3;
        } else {
            rbsp.push_back(nal[i]);
            i++;
        }
    }

    std::vector<uint8_t>& kept = ws.kept;
    kept.clear();
    kept.push_back(rbsp[0]);
    kept.push_back(rbsp[1]);

    const size_t end = rbsp.size();
    size_t pos = 2;
    bool has_hdr10plus = false;
    while (pos < end) {
        if (end - pos == 1 && rbsp[pos] == 0x80) break;  // RBSP stop bit
        const size_t msg_start = pos;
        uint32_t payload_type = 0;
        while (pos < end) {
            const uint8_t b = rbsp[pos++];
            payload_type += b;
            if (b != 0xFF) break;
        }
        uint32_t payload_size = 0;
        while (pos < end) {
            const uint8_t b = rbsp[pos++];
            payload_size += b;
            if (b != 0xFF) break;
        }
        if (payload_size > end - pos) return false;  // malformed: keep the NAL
        const size_t payload_start = pos;
        pos += payload_size;
        if (payload_type == 4 && payload_size >= kHdr10PlusSigLen &&
            std::memcmp(rbsp.data() + payload_start, kHdr10PlusSig, kHdr10PlusSigLen) == 0) {
            has_hdr10plus = true;
        } else {
            kept.insert(kept.end(), rbsp.begin() + msg_start, rbsp.begin() + pos);
        }
    }

    std::vector<uint8_t>& out = ws.filtered;
    out.clear();
    if (!has_hdr10plus) return false;
    if (kept.size() <= 2) return true;  // only the header left: drop the NAL

    kept.push_back(0x80);
    out.push_back(kept[0]);
    out.push_back(kept[1]);
    int zeros = 0;
    if (kept[0] == 0) zeros++;
    zeros = kept[1] == 0 ? (zeros == 1 ? 2 : 1) : 0;
    for (size_t j = 2; j < kept.size(); ++j) {
        const uint8_t b = kept[j];
        if (zeros == 2 && b <= 3) {
            out.push_back(0x03);
            zeros = 0;
        }
        out.push_back(b);
        zeros = b == 0 ? zeros + 1 : 0;
    }
    return true;
}

// ── Annex-B ──

constexpr size_t kNone = static_cast<size_t>(-1);

// First i >= from with d[i..] = 00 00 01 or 00 00 00 01 (i + 2 < limit). memchr jumps to the
// next zero byte instead of testing every byte (the walk's hot loop on multi-MB frames).
size_t find_start_code(const uint8_t* d, size_t from, size_t limit) {
    for (size_t i = from; i + 2 < limit; ++i) {
        const void* zero = std::memchr(d + i, 0, limit - 2 - i);
        if (zero == nullptr) return kNone;
        i = static_cast<size_t>(static_cast<const uint8_t*>(zero) - d);
        if (d[i + 1] == 0) {
            if (d[i + 2] == 1) return i;
            if (i + 3 < limit && d[i + 2] == 0 && d[i + 3] == 1) return i;
        }
    }
    return kNone;
}

size_t start_code_length(const uint8_t* d, size_t at, size_t limit) {
    return (at + 3 < limit && d[at] == 0 && d[at + 1] == 0 && d[at + 2] == 0 && d[at + 3] == 1) ? 4 : 3;
}

// ── Length-prefixed ──

inline uint32_t read_length(const uint8_t* p, int lfl) {
    uint32_t v = 0;
    for (int i = 0; i < lfl; ++i) v = (v << 8) | p[i];
    return v;
}

// Low `lfl` bytes of `value`, big-endian, no range check.
inline void put_length(Writer& out, size_t value, int lfl) {
    for (int shift = lfl - 1; shift >= 0; --shift) out.put_byte(static_cast<uint8_t>(value >> (shift * 8)));
}

inline size_t max_length(int lfl) {
    switch (lfl) {
        case 1: return 0xFF;
        case 2: return 0xFFFF;
        case 3: return 0xFFFFFF;
        default: return 0x7FFFFFFF;
    }
}

// Step 1: drop RPU (62), unspec63 EL and nuh_layer_id > 0 NALs. Any length 0 or past the end
// aborts (false = unchanged); a short tail is dropped. The layer id of a 1-byte NAL reads the
// next byte of the SAMPLE (quirk kept). True when a NAL was dropped.
bool strip_walk(const uint8_t* in, size_t n, int lfl, Writer& out) {
    if (n < static_cast<size_t>(lfl)) return false;
    out.reset();
    bool changed = false;
    size_t pos = 0;
    while (pos + lfl <= n) {
        const uint32_t size = read_length(in + pos, lfl);
        const size_t start = pos + lfl;
        if (size == 0 || size > n - start) return false;
        const int type = nal_type(in[start]);
        const int layer = start + 1 < n ? layer_id(in[start], in[start + 1]) : 0;
        if (type == kTypeRpu || type == kTypeEl || layer > 0) {
            changed = true;
        } else {
            put_length(out, size, lfl);
            out.put(in + start, size);
        }
        pos = start + size;
    }
    return changed;
}

// Step 2: drop layer > 0 NALs except RPUs and every unspec63; convert each RPU (failure or
// abandon drops it and counts); copy the rest, length-0 NALs included. A length past the end,
// a converted RPU too large for `lfl`, or a short tail aborts (false = unchanged). Failure
// state updates persist through an abort.
bool convert_walk(const uint8_t* in, size_t n, int lfl, const LengthPrefixedOps& ops,
                  const RpuConverter& converter, LengthPrefixedResult& result, Writer& out, Workspace& ws) {
    RpuFailureState& state = result.state;
    out.reset();
    bool changed = false;
    size_t off = 0;
    while (off + lfl <= n) {
        const uint32_t size = read_length(in + off, lfl);
        off += lfl;
        if (size > n - off) return false;
        const int type = size >= 1 ? nal_type(in[off]) : -1;
        const int layer = size >= 2 ? layer_id(in[off], in[off + 1]) : 0;
        if (layer > 0 && type != kTypeRpu) {
            changed = true;
        } else if (type == kTypeEl) {
            changed = true;
        } else if (type == kTypeRpu) {
            changed = true;
            bool ok = false;
            if (!state.abandoned) {
                // Counted like DoviBridge counts a conversion call, converter or not (stub build).
                result.convert_calls++;
                ok = converter.fn != nullptr &&
                     converter.fn(converter.ctx, in + off, size, ops.mode, ws.converted) && !ws.converted.empty();
            }
            if (ok) {
                result.converted++;
                state.consecutive = 0;
                if (ws.converted.size() > max_length(lfl)) return false;
                put_length(out, ws.converted.size(), lfl);
                out.put(ws.converted.data(), ws.converted.size());
            } else {
                state.consecutive++;
                result.rpu_drops++;
                if (!state.abandoned && state.consecutive >= ops.abandon_threshold) state.abandoned = true;
            }
        } else {
            put_length(out, size, lfl);
            out.put(in + off, size);
        }
        off += size;
    }
    if (off != n) return false;
    return changed && out.size() > 0;
}

// Step 3a pre-scan: a type 39/40 NAL contains the HDR10+ signature anywhere (header included),
// before the first length 0 or past the end.
bool has_hdr10plus_candidate(const uint8_t* d, size_t n, int lfl) {
    size_t pos = 0;
    while (pos + lfl <= n) {
        const uint32_t size = read_length(d + pos, lfl);
        const size_t start = pos + lfl;
        if (size == 0 || size > n - start) return false;
        const int type = nal_type(d[start]);
        if ((type == kTypePrefixSei || type == kTypeSuffixSei) && size >= kHdr10PlusSigLen) {
            for (size_t i = start; i + kHdr10PlusSigLen <= start + size; ++i) {
                if (std::memcmp(d + i, kHdr10PlusSig, kHdr10PlusSigLen) == 0) return true;
            }
        }
        pos = start + size;
    }
    return false;
}

// Step 3: HDR10+ SEI strip. Any length 0 or past the end aborts (false = unchanged). A
// rewritten NAL's length field is its low `lfl` bytes (no range check). When changed, a short
// tail is dropped.
bool hdr10plus_walk(const uint8_t* d, size_t n, int lfl, Writer& out, Workspace& ws) {
    if (!has_hdr10plus_candidate(d, n, lfl)) return false;
    out.reset();
    bool changed = false;
    size_t pos = 0;
    while (pos + lfl <= n) {
        const uint32_t size = read_length(d + pos, lfl);
        const size_t start = pos + lfl;
        if (size == 0 || size > n - start) return false;
        const int type = nal_type(d[start]);
        if ((type == kTypePrefixSei || type == kTypeSuffixSei) && filter_sei_nal(d + start, size, ws)) {
            changed = true;
            if (!ws.filtered.empty()) {
                put_length(out, ws.filtered.size(), lfl);
                out.put(ws.filtered.data(), ws.filtered.size());
            }
        } else {
            put_length(out, size, lfl);
            out.put(d + start, size);
        }
        pos = start + size;
    }
    return changed;
}

}  // namespace

AnnexBResult process_annexb(const uint8_t* in, size_t n, const AnnexBOps& ops,
                            const RpuConverter& converter, Writer& out, Workspace& ws) {
    AnnexBResult result;
    out.reset();
    size_t scan = 0;
    while (scan < n) {
        const size_t sc = find_start_code(in, scan, n);
        if (sc == kNone) {
            out.put(in + scan, n - scan);
            break;
        }
        const size_t nal_begin = sc + start_code_length(in, sc, n);
        const size_t next = find_start_code(in, nal_begin + 2, n);
        const size_t nal_end = next == kNone ? n : next;
        if (sc > scan) out.put(in + scan, sc - scan);

        // A start code with nothing after it is dropped without marking a change (quirk kept).
        if (nal_begin < nal_end) {
            const size_t size = nal_end - nal_begin;
            const int type = nal_type(in[nal_begin]);
            const int layer = nal_begin + 1 < nal_end ? layer_id(in[nal_begin], in[nal_begin + 1]) : 0;
            bool drop = false;
            const std::vector<uint8_t>* rewritten = nullptr;

            if (ops.convert || ops.strip_rpu) {
                if (type == kTypeRpu) {
                    if (ops.strip_rpu) {
                        drop = true;
                    } else if (converter.fn != nullptr) {
                        // Conversion failure drops the RPU (never raw P7 under 8.1 signalling);
                        // with no converter (stub build) the RPU is forwarded unchanged.
                        if (converter.fn(converter.ctx, in + nal_begin, size, ops.mode, ws.converted) &&
                            !ws.converted.empty()) {
                            rewritten = &ws.converted;
                        } else {
                            drop = true;
                            result.rpu_drops++;
                        }
                    }
                } else if (layer > 0 || type == kTypeEl) {
                    // unspec63 = single-track EL carriage at layer 0.
                    drop = true;
                }
            }

            if (!drop && rewritten == nullptr && ops.strip_hdr10plus &&
                (type == kTypePrefixSei || type == kTypeSuffixSei) &&
                filter_sei_nal(in + nal_begin, size, ws)) {
                if (ws.filtered.empty()) {
                    drop = true;
                } else {
                    rewritten = &ws.filtered;
                }
            }

            if (drop) {
                result.changed = true;
            } else if (rewritten != nullptr) {
                result.changed = true;
                out.put(in + sc, nal_begin - sc);
                out.put(rewritten->data(), rewritten->size());
            } else {
                out.put(in + sc, nal_end - sc);
            }
        }
        scan = nal_end;
    }
    return result;
}

LengthPrefixedResult process_length_prefixed(const uint8_t* in, size_t n, int lfl,
                                             const LengthPrefixedOps& ops, RpuFailureState state,
                                             const RpuConverter& converter, Writer& out, Workspace& ws) {
    LengthPrefixedResult result;
    result.state = state;
    if (lfl < 1 || lfl > 4) return result;

    bool rewritten = false;  // steps 1/2 produced the bytes now in `out`
    if (ops.strip_rpu) {
        rewritten = strip_walk(in, n, lfl, out);
        result.rpu_stripped = rewritten;
    } else if (ops.convert) {
        rewritten = convert_walk(in, n, lfl, ops, converter, result, out, ws);
    }

    bool hdr10plus = false;
    if (ops.strip_hdr10plus) {
        if (rewritten) {
            // Step 3 reads `out`, so it writes elsewhere, then replaces `out` when it changed.
            Writer step3(nullptr, 0, ws.step3);
            if (hdr10plus_walk(out.data(), out.size(), lfl, step3, ws)) {
                out.reset();
                out.put(step3.data(), step3.size());
            }
        } else {
            hdr10plus = hdr10plus_walk(in, n, lfl, out, ws);
        }
    }
    result.changed = rewritten || hdr10plus;
    return result;
}

}  // namespace nal
