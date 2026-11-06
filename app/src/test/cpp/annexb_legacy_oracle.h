// Spec 21 N3 test oracle: the pre-N3 Annex-B walk of dovi_bridge.cpp (nativeProcessVideoSample at
// 631bc91ff), extracted verbatim by script; only the JNI copy-in/copy-out and the libdovi call
// were replaced (marked "was:"). FROZEN: never edit to match the new core; the harness diffs
// the core against it.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace legacy {

using Converter = bool (*)(const uint8_t* nal, size_t len, int mode, std::vector<uint8_t>& out);

struct Result {
    bool changed = false;
    std::vector<uint8_t> output;
    uint32_t rpuDrops = 0;
};

static std::vector<uint8_t> unescape_rbsp(const uint8_t* data, size_t nalOffset, size_t nalSize) {
    std::vector<uint8_t> rbsp;
    if (nalSize < 3) {
        for (size_t i = 0; i < nalSize; ++i) rbsp.push_back(data[nalOffset + i]);
        return rbsp;
    }
    rbsp.reserve(nalSize);
    rbsp.push_back(data[nalOffset]);
    rbsp.push_back(data[nalOffset + 1]);
    size_t i = nalOffset + 2;
    size_t end = nalOffset + nalSize;
    while (i < end) {
        if (i + 2 < end && data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x03) {
            rbsp.push_back(0x00);
            rbsp.push_back(0x00);
            i += 3;
        } else {
            rbsp.push_back(data[i]);
            i++;
        }
    }
    return rbsp;
}

static std::vector<uint8_t> escape_rbsp(const std::vector<uint8_t>& rbsp) {
    std::vector<uint8_t> escaped;
    if (rbsp.size() <= 2) return rbsp;
    escaped.reserve(rbsp.size() * 3 / 2);
    escaped.push_back(rbsp[0]);
    escaped.push_back(rbsp[1]);

    int consecutiveZeros = 0;
    if (rbsp[0] == 0) consecutiveZeros++;
    if (rbsp[1] == 0) {
        consecutiveZeros = (consecutiveZeros == 1) ? 2 : 1;
    } else {
        consecutiveZeros = 0;
    }

    for (size_t j = 2; j < rbsp.size(); ++j) {
        uint8_t b = rbsp[j];
        if (consecutiveZeros == 2 && b <= 3) {
            escaped.push_back(0x03);
            consecutiveZeros = 0;
        }
        escaped.push_back(b);
        if (b == 0) {
            consecutiveZeros++;
        } else {
            consecutiveZeros = 0;
        }
    }
    return escaped;
}

static bool matchesSignature(const std::vector<uint8_t>& rbspData, size_t payloadStart, const uint8_t* sig, size_t sigLen) {
    if (payloadStart + sigLen > rbspData.size()) return false;
    for (size_t i = 0; i < sigLen; ++i) {
        if (rbspData[payloadStart + i] != sig[i]) return false;
    }
    return true;
}

static bool filterSeiNal(const uint8_t* data, size_t nalOffset, size_t nalSize, std::vector<uint8_t>& filteredNal) {
    if (nalSize < 3) return false;

    std::vector<uint8_t> rbspData = unescape_rbsp(data, nalOffset, nalSize);
    size_t rbspEnd = rbspData.size();

    std::vector<uint8_t> outRbsp;
    outRbsp.push_back(rbspData[0]);
    outRbsp.push_back(rbspData[1]);

    size_t pos = 2;
    bool hasHdr10Plus = false;
    const uint8_t HDR10_PLUS_SIG[] = {0xB5, 0x00, 0x3C, 0x00, 0x01};
    const size_t HDR10_PLUS_SIG_LEN = 5;

    while (pos < rbspEnd) {
        if (rbspEnd - pos == 1 && rbspData[pos] == 0x80) break;

        size_t msgStart = pos;

        uint32_t payloadType = 0;
        while (pos < rbspEnd) {
            uint8_t b = rbspData[pos++];
            payloadType += b;
            if (b != 0xFF) break;
        }

        uint32_t payloadSize = 0;
        while (pos < rbspEnd) {
            uint8_t b = rbspData[pos++];
            payloadSize += b;
            if (b != 0xFF) break;
        }

        if (pos + payloadSize > rbspEnd) return false;
        size_t payloadStart = pos;
        pos += payloadSize;
        size_t msgEnd = pos;

        if (payloadType == 4 && 
            payloadSize >= HDR10_PLUS_SIG_LEN &&
            matchesSignature(rbspData, payloadStart, HDR10_PLUS_SIG, HDR10_PLUS_SIG_LEN)
        ) {
            hasHdr10Plus = true;
        } else {
            outRbsp.insert(outRbsp.end(), rbspData.begin() + msgStart, rbspData.begin() + msgEnd);
        }
    }

    if (!hasHdr10Plus) return false;
    if (outRbsp.size() <= 2) {
        filteredNal.clear();
        return true;
    }

    outRbsp.push_back(0x80);
    filteredNal = escape_rbsp(outRbsp);
    return true;
}

static int findStartCode(const uint8_t* data, int from, int limit) {
    int i = from;
    while (i + 2 < limit) {
        if (data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1) return i;
            if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        i++;
    }
    return -1;
}

static int startCodeLength(const uint8_t* data, int offset, int limit) {
    if (offset + 3 < limit &&
        data[offset] == 0 &&
        data[offset + 1] == 0 &&
        data[offset + 2] == 0 &&
        data[offset + 3] == 1
    ) return 4;
    return 3;
}

inline Result processVideoSample(const uint8_t* sample, int sampleLen, bool convertDovi, int doviMode,
                                 bool stripDoviRpu, bool stripHdr10Plus, Converter converter) {
    Result result;
    std::vector<uint8_t> sampleBuffer(sample, sample + sampleLen);
    std::vector<uint8_t>& outputBuffer = result.output;

    bool changed = false;
    outputBuffer.clear();

    int scan = 0;
    while (scan < sampleLen) {
        int startCode = findStartCode(sampleBuffer.data(), scan, sampleLen);
        if (startCode < 0) {
            outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + scan, sampleBuffer.begin() + sampleLen);
            break;
        }
        int scLen = startCodeLength(sampleBuffer.data(), startCode, sampleLen);
        int nalBegin = startCode + scLen;
        int nextStartCode = findStartCode(sampleBuffer.data(), nalBegin + 2, sampleLen);
        int nalEnd = (nextStartCode < 0) ? sampleLen : nextStartCode;

        if (startCode > scan) {
            outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + scan, sampleBuffer.begin() + startCode);
        }

        if (nalBegin < nalEnd) {
            int nalSize = nalEnd - nalBegin;
            uint8_t nalHeader = sampleBuffer[nalBegin];
            int nalType = (nalHeader >> 1) & 0x3F;
            int layerId = 0;
            if (nalBegin + 1 < nalEnd) {
                layerId = ((nalHeader & 0x01) << 5) | ((sampleBuffer[nalBegin + 1] >> 3) & 0x1F);
            }

            bool shouldDrop = false;
            bool processed = false;
            std::vector<uint8_t> processedNal;

            if (convertDovi || stripDoviRpu) {
                bool isRpu = (nalType == 62);
                bool isEl = (layerId > 0) || (nalType == 63); // DV7 F3: unspec63 = single-track EL carriage at layer 0 (probe-verified)
                if (isRpu) {
                    if (stripDoviRpu) {
                        shouldDrop = true;
                    } else if (convertDovi) {
                        // convertDovi is only set when the probe saw DOVI_REAL_LINKED, so a
                        // stub build never gets here; it would forward the RPU unchanged.
if (converter != nullptr) {  // was: #if DOVI_REAL_LINKED
                        if (converter(sampleBuffer.data() + nalBegin, static_cast<size_t>(nalSize),
                                            doviMode, processedNal)) {
                            processed = true;
                        } else {
                            shouldDrop = true;
                            result.rpuDrops++;  // was: noteRpuDropOnFailure()
                        }
                        }
                    }
                } else if (isEl) {
                    shouldDrop = true;
                }
            }

            if (!shouldDrop && !processed && stripHdr10Plus) {
                if (nalType == 39 || nalType == 40) {
                    std::vector<uint8_t> filtered;
                    if (filterSeiNal(sampleBuffer.data(), nalBegin, nalSize, filtered)) {
                        processed = true;
                        processedNal = std::move(filtered);
                        if (processedNal.empty()) {
                            shouldDrop = true;
                        }
                    }
                }
            }

            if (shouldDrop) {
                changed = true;
            } else if (processed) {
                changed = true;
                outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + startCode, sampleBuffer.begin() + nalBegin);
                outputBuffer.insert(outputBuffer.end(), processedNal.begin(), processedNal.end());
            } else {
                outputBuffer.insert(outputBuffer.end(), sampleBuffer.begin() + startCode, sampleBuffer.begin() + nalEnd);
            }
        }
        scan = nalEnd;
    }

    result.changed = changed;
    return result;
}

}  // namespace legacy
