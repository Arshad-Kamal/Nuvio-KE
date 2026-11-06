// Spec 21 N3 host harness for app/src/main/cpp/nal_pipeline.cpp (run: scripts/test-dv-native.sh).
//
//   dv_native_harness <dv-golden dir>           correctness: every MKV golden byte for byte, the
//                                               Annex-B core vs the frozen pre-N3 walk
//   dv_native_harness <dv-golden dir> --timing  native walk timing on the Spec 21 § Measurements frames
//
// The once-per-stream policy (shouldTransform, DV5 decision, which steps run) is the caller's
// (Kotlin) in production; emulate_policy() restates it from the dv-golden README so the per-sample
// core can reproduce the goldens, which DolbyVisionGoldenCorpusTest made from the Kotlin pipeline.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include <sys/mman.h>
#include <unistd.h>

#include "../../main/cpp/nal_pipeline.h"
#include "annexb_legacy_oracle.h"

using Bytes = std::vector<uint8_t>;

static int g_failures = 0;

#define CHECK(cond, ...)                                    \
    do {                                                    \
        if (!(cond)) {                                      \
            std::fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            std::fprintf(stderr, __VA_ARGS__);              \
            std::fprintf(stderr, "\n");                     \
            if (++g_failures > 20) std::exit(1);            \
        }                                                   \
    } while (0)

// ── Fake converter (dv-golden README "Fake converter") ──

static bool fake_convert(const uint8_t* nal, size_t len, int mode, Bytes& out) {
    out.clear();
    if (len < 3 || nal[2] == 0xFF) return false;
    out.resize(len + 1);
    out[0] = nal[0];
    out[1] = nal[1];
    for (size_t i = 2; i < len; ++i) out[i] = nal[i] ^ 0x5A;
    out[len] = static_cast<uint8_t>(mode);
    return true;
}

static bool fake_convert_core(void*, const uint8_t* nal, size_t len, int mode, Bytes& out) {
    return fake_convert(nal, len, mode, out);
}

static const nal::RpuConverter kFake{fake_convert_core, nullptr};
static const nal::RpuConverter kNoConverter{};

// A buffer whose last byte sits right before a PROT_NONE page, so a read or write past the end
// faults (the memory check when the host has no ASan runtime; harmless under ASan).
class Guarded {
public:
    explicit Guarded(size_t n) : n_(n) {
        const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
        span_ = (n + page - 1) / page * page + page;
        base_ = static_cast<uint8_t*>(mmap(nullptr, span_, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
        if (base_ == MAP_FAILED || mprotect(base_ + span_ - page, page, PROT_NONE) != 0) {
            std::perror("guard page");
            std::exit(2);
        }
        data_ = base_ + span_ - page - n;
    }
    Guarded(const Bytes& b) : Guarded(b.size()) {
        if (!b.empty()) std::memcpy(data_, b.data(), b.size());
    }
    ~Guarded() { munmap(base_, span_); }
    Guarded(const Guarded&) = delete;
    Guarded& operator=(const Guarded&) = delete;
    uint8_t* data() { return data_; }
    size_t size() const { return n_; }

private:
    size_t n_, span_;
    uint8_t* base_;
    uint8_t* data_;
};

// ── Golden files ──

static Bytes read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        std::fprintf(stderr, "cannot read %s\n", path.c_str());
        std::exit(2);
    }
    return Bytes(std::istreambuf_iterator<char>(f), {});
}

static std::vector<Bytes> read_frames(const std::string& path) {
    const Bytes all = read_file(path);
    std::vector<Bytes> frames;
    size_t pos = 0;
    while (pos + 4 <= all.size()) {
        const size_t len = (size_t(all[pos]) << 24) | (size_t(all[pos + 1]) << 16) | (size_t(all[pos + 2]) << 8) | all[pos + 3];
        pos += 4;
        if (len > all.size() - pos) {
            std::fprintf(stderr, "bad frames file %s\n", path.c_str());
            std::exit(2);
        }
        frames.emplace_back(all.begin() + pos, all.begin() + pos + len);
        pos += len;
    }
    return frames;
}

struct GoldenCase {
    std::string name, config;
    std::optional<int> profile;
    int lfl = 0;
    size_t frames = 0;
    uint64_t rpu_drops = 0;
};

static std::vector<GoldenCase> read_cases(const std::string& dir) {
    const Bytes raw = read_file(dir + "/cases.tsv");
    std::istringstream tsv(std::string(raw.begin(), raw.end()));
    std::vector<GoldenCase> cases;
    std::string line;
    std::getline(tsv, line);  // header
    while (std::getline(tsv, line)) {
        if (line.empty()) continue;
        std::istringstream row(line);
        GoldenCase c;
        std::string profile;
        std::getline(row, c.name, '\t');
        std::getline(row, c.config, '\t');
        std::getline(row, profile, '\t');
        row >> c.lfl >> c.frames >> c.rpu_drops;
        if (profile != "none") c.profile = std::stoi(profile);
        cases.push_back(c);
    }
    return cases;
}

// ── Per-stream policy (README "Configs", "Per-sample order", "DV5 decision") ──

struct Config {
    bool active = false, manual = false, dv5 = false, strip = false, hdr10p = false;
};

static Config config_of(const std::string& id) {
    Config c;
    if (id == "convert_auto") c.active = true;
    else if (id == "convert_manual") c.active = c.manual = true;
    else if (id == "convert_manual+hdr10p") c.active = c.manual = c.hdr10p = true;
    else if (id == "convert_dv5") c.active = c.dv5 = c.manual = true;
    else if (id == "strip") c.strip = true;
    else if (id == "strip+hdr10p") c.strip = c.hdr10p = true;
    else if (id == "hdr10p") c.hdr10p = true;
    else {
        std::fprintf(stderr, "unknown config %s\n", id.c_str());
        std::exit(2);
    }
    return c;
}

// Hdr10SeiInjector.hasHdr10StaticSei: a type-39 NAL whose first payload byte is MDCV (137) or
// CLLI (144), before the first length 0 or past the end.
static bool has_hdr10_static_sei(const Bytes& s, int lfl) {
    size_t pos = 0;
    while (pos + lfl <= s.size()) {
        uint32_t size = 0;
        for (int i = 0; i < lfl; ++i) size = (size << 8) | s[pos + i];
        const size_t start = pos + lfl;
        if (size == 0 || size > s.size() - start) break;
        if (size >= 3 && ((s[start] >> 1) & 0x3F) == 39 && (s[start + 2] == 137 || s[start + 2] == 144)) return true;
        pos = start + size;
    }
    return false;
}

struct StreamPolicy {
    Config cfg;
    std::optional<int> profile;
    std::optional<bool> dv5_strip;  // DV5 decision, undecided at stream start
    int dv5_checked = 0;

    bool should_convert() const {
        return cfg.active && profile && (*profile == 7 || (*profile == 5 && cfg.dv5 && cfg.manual));
    }
    bool should_transform() const { return cfg.hdr10p || (cfg.strip ? profile.has_value() : should_convert()); }

    bool dv5_decide(const Bytes& sample, int lfl) {
        if (dv5_strip) return *dv5_strip;
        if (has_hdr10_static_sei(sample, lfl)) return *(dv5_strip = true);
        if (++dv5_checked >= 15) return *(dv5_strip = false);
        return false;
    }

    nal::LengthPrefixedOps ops_for(const Bytes& sample, int lfl) {
        nal::LengthPrefixedOps ops;
        ops.abandon_threshold = 60;  // DolbyVisionMatroskaTransformer.RPU_FAILURE_ABANDON_THRESHOLD
        ops.strip_hdr10plus = cfg.hdr10p;
        if (cfg.strip) {
            ops.strip_rpu = !(profile == 5 && !dv5_decide(sample, lfl));
        } else if (should_convert()) {  // P5 converts only with dv5 + manual, as should_convert requires
            ops.convert = true;
            ops.mode = (profile == 5 || cfg.manual) ? 2 : 1;
        }
        return ops;
    }
};

// One sample the way the JNI wrapper runs it: write into a buffer of `cap` bytes; on overflow
// retry once with the required size from the PRE-call state (the core is pure).
struct LpOutcome {
    Bytes bytes;  // what the extractor writes
    nal::LengthPrefixedResult result;
};

static LpOutcome run_lp(const Bytes& in_bytes, int lfl, const nal::LengthPrefixedOps& ops, nal::RpuFailureState state,
                        size_t cap, nal::Workspace& ws) {
    Guarded in(in_bytes);
    Guarded buf(cap);
    nal::Writer w(buf.data(), cap, ws.spill);
    nal::LengthPrefixedResult r = nal::process_length_prefixed(in.data(), in.size(), lfl, ops, state, kFake, w, ws);
    if (r.changed && w.spilled()) {
        const Bytes spilled(w.data(), w.data() + w.size());
        const nal::LengthPrefixedResult first = r;
        Guarded big(w.size());
        nal::Writer w2(big.data(), big.size(), ws.spill);
        r = nal::process_length_prefixed(in.data(), in.size(), lfl, ops, state, kFake, w2, ws);
        CHECK(!w2.spilled() && Bytes(w2.data(), w2.data() + w2.size()) == spilled, "retry differs from the spilled run");
        CHECK(r.state.consecutive == first.state.consecutive && r.state.abandoned == first.state.abandoned &&
                  r.rpu_drops == first.rpu_drops && r.changed == first.changed &&
                  r.rpu_stripped == first.rpu_stripped && r.convert_calls == first.convert_calls &&
                  r.converted == first.converted,
              "retry state differs");
        return {Bytes(w2.data(), w2.data() + w2.size()), r};
    }
    return {r.changed ? Bytes(w.data(), w.data() + w.size()) : in_bytes, r};
}

static void check_goldens(const std::string& dir) {
    const std::vector<GoldenCase> cases = read_cases(dir);
    CHECK(cases.size() == 35, "expected 35 cases, got %zu", cases.size());
    nal::Workspace ws;
    // cap = sample size (Kotlin pre-sizes the out buffer to the sample), then cap 0 (every
    // changed sample spills: the retry path), then a fixed 64-byte buffer (spill mid-walk).
    for (const size_t mode : {size_t(1), size_t(0), size_t(2)}) {
        for (const GoldenCase& c : cases) {
            const std::vector<Bytes> in = read_frames(dir + "/" + c.name + ".in");
            const std::vector<Bytes> golden = read_frames(dir + "/" + c.name + ".out");
            CHECK(in.size() == c.frames && golden.size() == c.frames, "%s: frame count", c.name.c_str());
            StreamPolicy policy;
            policy.cfg = config_of(c.config);
            policy.profile = c.profile;
            const bool transform = policy.should_transform();
            nal::RpuFailureState state;
            uint64_t drops = 0;
            for (size_t i = 0; i < in.size() && i < golden.size(); ++i) {
                Bytes out = in[i];
                if (transform) {
                    const nal::LengthPrefixedOps ops = policy.ops_for(in[i], c.lfl);
                    const size_t cap = mode == 1 ? in[i].size() : mode == 0 ? 0 : 64;
                    LpOutcome o = run_lp(in[i], c.lfl, ops, state, cap, ws);
                    // Every failed call is a drop; no call once abandoned or off the convert walk.
                    CHECK(o.result.converted <= o.result.convert_calls &&
                              o.result.convert_calls - o.result.converted <= o.result.rpu_drops &&
                              (ops.convert || o.result.convert_calls == 0) &&
                              (!state.abandoned || o.result.convert_calls == 0),
                          "%s frame %zu: calls %u converted %u drops %u", c.name.c_str(), i,
                          o.result.convert_calls, o.result.converted, o.result.rpu_drops);
                    state = o.result.state;
                    drops += o.result.rpu_drops;
                    out = std::move(o.bytes);
                }
                CHECK(out == golden[i], "%s frame %zu (cap mode %zu): %zu bytes, golden %zu", c.name.c_str(), i, mode,
                      out.size(), golden[i].size());
            }
            CHECK(drops == c.rpu_drops, "%s: rpu_drops %llu, golden %llu", c.name.c_str(),
                  static_cast<unsigned long long>(drops), static_cast<unsigned long long>(c.rpu_drops));
        }
    }
    std::printf("goldens: %zu cases x 3 buffer modes OK\n", cases.size());
}

// ── Annex-B: core vs the frozen pre-N3 walk ──

struct Rng {
    uint32_t s;
    uint32_t next() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return s;
    }
    uint32_t below(uint32_t n) { return next() % n; }
    bool chance(uint32_t pct) { return below(100) < pct; }
};

static Bytes nal_bytes(Rng& r, int type, int layer, size_t size) {
    Bytes n(size);
    if (size > 0) n[0] = static_cast<uint8_t>(((type & 0x3F) << 1) | ((layer >> 5) & 1));
    if (size > 1) n[1] = static_cast<uint8_t>(((layer & 0x1F) << 3) | 1);
    for (size_t i = 2; i < size; ++i) n[i] = r.chance(30) ? 0 : static_cast<uint8_t>(r.next());
    return n;
}

// SEI NAL from messages; HDR10+ or other payloads, escaped like a muxer would (or not).
static Bytes sei_bytes(Rng& r, int type) {
    Bytes rbsp;
    const int messages = 1 + r.below(3);
    for (int m = 0; m < messages; ++m) {
        const bool hdr10plus = r.chance(60);
        const uint32_t ptype = hdr10plus ? 4 : (r.chance(20) ? 300 : r.below(200));
        size_t psize = hdr10plus ? 5 + r.below(60) : r.below(40);
        if (r.chance(5)) psize = 260 + r.below(40);
        for (uint32_t t = ptype; ; t -= 255) {
            if (t >= 255) rbsp.push_back(0xFF);
            else { rbsp.push_back(static_cast<uint8_t>(t)); break; }
        }
        for (size_t s = psize; ; s -= 255) {
            if (s >= 255) rbsp.push_back(0xFF);
            else { rbsp.push_back(static_cast<uint8_t>(s)); break; }
        }
        const uint8_t sig[] = {0xB5, 0x00, 0x3C, 0x00, 0x01};
        for (size_t i = 0; i < psize; ++i) {
            if (hdr10plus && i < 5 && !r.chance(3)) rbsp.push_back(sig[i]);
            else rbsp.push_back(r.chance(35) ? 0 : static_cast<uint8_t>(r.below(6)));
        }
    }
    if (!r.chance(10)) rbsp.push_back(0x80);
    if (r.chance(10) && !rbsp.empty()) rbsp.resize(r.below(static_cast<uint32_t>(rbsp.size())));  // malformed
    Bytes n = nal_bytes(r, type, 0, 2);
    if (r.chance(10)) n[0] = 0, n[1] = 0;  // zero header bytes: escaping starts from them
    int zeros = 0;
    const bool escape = !r.chance(10);
    for (uint8_t b : rbsp) {
        if (escape && zeros == 2 && b <= 3) {
            n.push_back(3);
            zeros = 0;
        }
        n.push_back(b);
        zeros = b == 0 ? zeros + 1 : 0;
    }
    return n;
}

static Bytes random_annexb(Rng& r) {
    static const int kTypes[] = {1, 19, 32, 33, 34, 35, 39, 40, 62, 63, 62, 39};
    Bytes s;
    if (r.chance(10)) for (uint32_t i = 0, n = r.below(6); i < n; ++i) s.push_back(static_cast<uint8_t>(r.below(3)));
    const uint32_t nals = r.below(12);
    for (uint32_t k = 0; k < nals; ++k) {
        if (r.chance(50)) s.push_back(0);
        s.push_back(0), s.push_back(0), s.push_back(1);
        const int type = kTypes[r.below(sizeof(kTypes) / sizeof(kTypes[0]))];
        Bytes n;
        if (type == 39 || type == 40) {
            n = sei_bytes(r, type);
        } else {
            const int layer = r.chance(75) ? 0 : static_cast<int>(r.below(3));
            const size_t size = r.chance(10) ? r.below(3) : r.below(300);
            n = nal_bytes(r, type, layer, size);
            if (type == 62 && size > 2) n[2] = r.chance(25) ? 0xFF : 0x19;
        }
        s.insert(s.end(), n.begin(), n.end());
    }
    if (r.chance(10)) s.push_back(0), s.push_back(0), s.push_back(1);
    if (!s.empty() && r.chance(30)) {  // mutations
        for (uint32_t i = 0, n = 1 + r.below(4); i < n; ++i) s[r.below(static_cast<uint32_t>(s.size()))] = static_cast<uint8_t>(r.chance(50) ? 0 : r.next());
    }
    if (!s.empty() && r.chance(10)) s.resize(r.below(static_cast<uint32_t>(s.size())) + 1);
    return s;
}

// Golden MKV frames re-framed as Annex-B (4- or 3-byte start codes; malformed rest kept raw).
static std::vector<Bytes> golden_as_annexb(const std::string& dir) {
    std::vector<Bytes> out;
    for (const GoldenCase& c : read_cases(dir)) {
        size_t k = 0;
        for (const Bytes& f : read_frames(dir + "/" + c.name + ".in")) {
            Bytes s;
            size_t pos = 0;
            while (pos + c.lfl <= f.size()) {
                uint32_t size = 0;
                for (int i = 0; i < c.lfl; ++i) size = (size << 8) | f[pos + i];
                if (size > f.size() - pos - c.lfl) break;
                if (++k % 2) s.push_back(0);
                s.push_back(0), s.push_back(0), s.push_back(1);
                s.insert(s.end(), f.begin() + pos + c.lfl, f.begin() + pos + c.lfl + size);
                pos += c.lfl + size;
            }
            s.insert(s.end(), f.begin() + pos, f.end());
            out.push_back(std::move(s));
        }
    }
    return out;
}

static void check_annexb(const std::string& dir) {
    std::vector<Bytes> corpus = golden_as_annexb(dir);
    const size_t golden_count = corpus.size();
    Rng r{0x2545F491u};
    for (int i = 0; i < 6000; ++i) corpus.push_back(random_annexb(r));
    nal::Workspace ws;
    size_t runs = 0, changed = 0, dropped = 0;
    for (const Bytes& bytes : corpus) {
        if (bytes.empty()) continue;  // the JNI wrapper returns 0 before the walk
        Guarded s(bytes);
        for (int combo = 0; combo < 32; ++combo) {
            nal::AnnexBOps ops;
            ops.convert = combo & 1;
            ops.strip_rpu = combo & 2;
            ops.strip_hdr10plus = combo & 4;
            const bool with_converter = combo & 8;
            ops.mode = 1 + ((combo >> 4) & 1);
            const legacy::Result want = legacy::processVideoSample(
                s.data(), static_cast<int>(s.size()), ops.convert, ops.mode, ops.strip_rpu, ops.strip_hdr10plus,
                with_converter ? fake_convert : nullptr);
            for (const size_t cap : {s.size(), size_t(0)}) {
                Guarded buf(cap);
                nal::Writer w(buf.data(), cap, ws.spill);
                const nal::AnnexBResult got =
                    nal::process_annexb(s.data(), s.size(), ops, with_converter ? kFake : kNoConverter, w, ws);
                CHECK(got.changed == want.changed && got.rpu_drops == want.rpuDrops,
                      "annexb sample %zu combo %d: changed %d/%d drops %u/%u", runs / 32, combo, got.changed,
                      want.changed, got.rpu_drops, want.rpuDrops);
                CHECK(!want.changed || Bytes(w.data(), w.data() + w.size()) == want.output,
                      "annexb sample %zu combo %d cap %zu: bytes differ", runs / 32, combo, cap);
            }
            ++runs;
            changed += want.changed;
            dropped += want.rpuDrops;
        }
    }
    std::printf("annexb: %zu samples (%zu from goldens) x 32 op/converter/mode combos = %zu runs, %zu changed, %zu RPU drops; "
                "core == pre-N3 walk\n",
                corpus.size(), golden_count, runs, changed, dropped);
}

// Hand-built Annex-B cases for behaviour the random corpus may not pin.
static void check_annexb_fixed() {
    nal::Workspace ws;
    auto run = [&](const Bytes& s, nal::AnnexBOps ops) {
        Bytes buf;
        nal::Writer w(nullptr, 0, buf);
        nal::AnnexBResult r = nal::process_annexb(s.data(), s.size(), ops, kFake, w, ws);
        return std::make_pair(r, Bytes(w.data(), w.data() + w.size()));
    };
    nal::AnnexBOps convert;
    convert.convert = true;
    convert.mode = 2;
    // VPS, EL slice (layer 1), unspec63, RPU: EL + unspec63 dropped, RPU converted (header, XOR, mode).
    const Bytes s = {0, 0, 0, 1, 0x40, 0x01, 0xAA, 0, 0, 1, 0x02, 0x09, 0xBB, 0, 0, 1, 0x7E, 0x01, 0xCC,
                     0, 0, 1, 0x7C, 0x01, 0x19, 0x22};
    auto [r, out] = run(s, convert);
    const Bytes want = {0, 0, 0, 1, 0x40, 0x01, 0xAA, 0, 0, 1, 0x7C, 0x01, 0x19 ^ 0x5A, 0x22 ^ 0x5A, 2};
    CHECK(r.changed && out == want, "annexb fixed convert");
    // Failed RPU (byte 2 = 0xFF) is dropped and counted.
    const Bytes f = {0, 0, 1, 0x02, 0x01, 0x11, 0, 0, 1, 0x7C, 0x01, 0xFF, 0x00};
    auto [r2, out2] = run(f, convert);
    CHECK(r2.changed && r2.rpu_drops == 1 && out2 == Bytes({0, 0, 1, 0x02, 0x01, 0x11}), "annexb fixed failure drop");
    std::printf("annexb fixed: OK\n");
}

// rpu_stripped gates the caller's HDR10 SEI injection (only when step 1 dropped a NAL); the
// goldens run with injection off, so pin it here.
static void check_rpu_stripped() {
    nal::Workspace ws;
    auto lp = [](std::initializer_list<Bytes> nals, Bytes tail = {}) {
        Bytes s;
        for (const Bytes& n : nals) {
            for (int shift = 24; shift >= 0; shift -= 8) s.push_back(static_cast<uint8_t>(n.size() >> shift));
            s.insert(s.end(), n.begin(), n.end());
        }
        s.insert(s.end(), tail.begin(), tail.end());
        return s;
    };
    auto run = [&](const Bytes& s, bool hdr10p) {
        nal::LengthPrefixedOps ops;
        ops.strip_rpu = true;
        ops.strip_hdr10plus = hdr10p;
        ops.abandon_threshold = 60;
        Bytes spill;
        nal::Writer w(nullptr, 0, spill);
        return nal::process_length_prefixed(s.data(), s.size(), 4, ops, {}, kFake, w, ws);
    };
    const Bytes slice = {0x02, 0x01, 0xAA, 0xBB};
    const Bytes rpu = {0x7C, 0x01, 0x19, 0x22};
    const Bytes hdr10plus_sei = {0x4E, 0x01, 0x04, 0x05, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x80};
    nal::LengthPrefixedResult r = run(lp({slice, rpu}), false);
    CHECK(r.changed && r.rpu_stripped, "rpu_stripped: RPU drop");
    r = run(lp({slice}, {0x00, 0x07}), false);
    CHECK(!r.changed && !r.rpu_stripped, "rpu_stripped: short tail only");
    r = run(lp({slice, {}, rpu}), false);
    CHECK(!r.changed && !r.rpu_stripped, "rpu_stripped: length-0 abort");
    r = run(lp({hdr10plus_sei, slice}), true);
    CHECK(r.changed && !r.rpu_stripped, "rpu_stripped: HDR10+-only change");
    r = run(lp({hdr10plus_sei, slice, rpu}), true);
    CHECK(r.changed && r.rpu_stripped, "rpu_stripped: RPU drop + HDR10+");
    std::printf("rpu_stripped fixed: OK\n");
}

// Converter call/success counts feed DoviBridge's conversion counters (Diagnostics, DV7 budget).
static void check_conversion_counts() {
    nal::Workspace ws;
    Bytes s;
    for (const Bytes& n : {Bytes{0x02, 0x01, 0xAA}, Bytes{0x7C, 0x01, 0x19, 0x01}, Bytes{0x7C, 0x01, 0xFF, 0x02},
                           Bytes{0x7C, 0x01, 0x19, 0x03}}) {
        for (int shift = 24; shift >= 0; shift -= 8) s.push_back(static_cast<uint8_t>(n.size() >> shift));
        s.insert(s.end(), n.begin(), n.end());
    }
    nal::LengthPrefixedOps ops;
    ops.convert = true;
    ops.mode = 2;
    ops.abandon_threshold = 60;
    Bytes spill;
    nal::Writer w(nullptr, 0, spill);
    nal::LengthPrefixedResult r = nal::process_length_prefixed(s.data(), s.size(), 4, ops, {}, kFake, w, ws);
    CHECK(r.convert_calls == 3 && r.converted == 2 && r.rpu_drops == 1 && r.state.consecutive == 0,
          "counts: 2 ok + 1 failed RPU (calls %u converted %u drops %u)", r.convert_calls, r.converted, r.rpu_drops);
    nal::RpuFailureState abandoned;
    abandoned.abandoned = true;
    abandoned.consecutive = 70;
    r = nal::process_length_prefixed(s.data(), s.size(), 4, ops, abandoned, kFake, w, ws);
    CHECK(r.convert_calls == 0 && r.converted == 0 && r.rpu_drops == 3 && r.state.consecutive == 73,
          "counts: abandoned (calls %u drops %u)", r.convert_calls, r.rpu_drops);
    std::printf("conversion counts fixed: OK\n");
}

// ── Timing (Spec 21 § Measurements frames; same bytes as DolbyVisionGoldenCorpusTest.timingFrame) ──

struct KotlinXorshift {
    int32_t state;
    uint8_t next() {
        uint32_t s = static_cast<uint32_t>(state);
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        state = static_cast<int32_t>(s);
        return static_cast<uint8_t>(s);
    }
};

static Bytes timing_frame(size_t slice, size_t el, bool annexb) {
    KotlinXorshift x{1};
    auto nal = [&](int type, int layer, size_t size) {
        Bytes n(size);
        n[0] = static_cast<uint8_t>(((type & 0x3F) << 1) | ((layer >> 5) & 1));
        n[1] = static_cast<uint8_t>(((layer & 0x1F) << 3) | 1);
        for (size_t i = 2; i < size; ++i) n[i] = x.next();
        return n;
    };
    std::vector<Bytes> nals = {nal(32, 0, 24), nal(33, 0, 64), nal(34, 0, 16)};
    for (int i = 0; i < 4; ++i) nals.push_back(nal(19, 0, slice));
    nals.push_back(nal(1, 1, el));
    Bytes rpu = nal(62, 0, 400);
    rpu[2] = 0x19;
    nals.push_back(rpu);
    Bytes s;
    for (const Bytes& n : nals) {
        if (annexb) s.insert(s.end(), {0, 0, 0, 1});
        else for (int shift = 24; shift >= 0; shift -= 8) s.push_back(static_cast<uint8_t>(n.size() >> shift));
        s.insert(s.end(), n.begin(), n.end());
    }
    return s;
}

template <typename F>
static void time_it(const char* label, const Bytes& frame, F&& body) {
    const int iterations = frame.size() > 1000000 ? 200 : 600;
    for (int i = 0; i < iterations / 2; ++i) body();
    std::vector<long long> ns(iterations);
    for (int i = 0; i < iterations; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        body();
        ns[i] = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
    }
    std::sort(ns.begin(), ns.end());
    std::printf("DV_TIMING native %s bytes=%zu medianUs=%lld p90Us=%lld\n", label, frame.size(),
                ns[iterations / 2] / 1000, ns[iterations * 9 / 10] / 1000);
}

static void run_timing() {
    nal::Workspace ws;
    for (const bool annexb : {false, true}) {
        for (const auto& [name, slice] : {std::make_pair("avg400KB", size_t(80000)), std::make_pair("I1.5MB", size_t(300000))}) {
            const Bytes frame = timing_frame(slice, slice, annexb);
            Bytes out(frame.size() + 4096);
            for (const bool convert : {true, false}) {
                const std::string label =
                    std::string(annexb ? "annexb " : "mkv ") + (convert ? "convert_manual " : "strip ") + name;
                if (annexb) {
                    nal::AnnexBOps ops;
                    ops.convert = convert;
                    ops.strip_rpu = !convert;
                    ops.mode = 2;
                    time_it(label.c_str(), frame, [&] {
                        nal::Writer w(out.data(), out.size(), ws.spill);
                        nal::process_annexb(frame.data(), frame.size(), ops, kFake, w, ws);
                    });

                } else {
                    nal::LengthPrefixedOps ops;
                    ops.convert = convert;
                    ops.strip_rpu = !convert;
                    ops.mode = 2;
                    ops.abandon_threshold = 60;
                    time_it(label.c_str(), frame, [&] {
                        nal::Writer w(out.data(), out.size(), ws.spill);
                        nal::process_length_prefixed(frame.data(), frame.size(), 4, ops, {}, kFake, w, ws);
                    });
                }
            }
            if (annexb) {
                // The two full-frame copies the pre-N3 JNI wrapper made (sample in, output out).
                Bytes in_copy(frame.size());
                time_it((std::string("avoided 2x frame copy ") + name).c_str(), frame, [&] {
                    std::memcpy(in_copy.data(), frame.data(), frame.size());
                    std::memcpy(out.data(), in_copy.data(), frame.size());
                    asm volatile("" ::"r"(out.data()) : "memory");
                });
            }
        }
    }
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <dv-golden dir> [--timing]\n", argv[0]);
        return 2;
    }
    const std::string dir = argv[1];
    if (argc > 2 && std::strcmp(argv[2], "--timing") == 0) {
        run_timing();
        return 0;
    }
    check_goldens(dir);
    check_annexb_fixed();
    check_rpu_stripped();
    check_conversion_counts();
    check_annexb(dir);
    if (g_failures) {
        std::fprintf(stderr, "dv-native: %d failure(s)\n", g_failures);
        return 1;
    }
    std::printf("dv-native: PASS\n");
    return 0;
}
