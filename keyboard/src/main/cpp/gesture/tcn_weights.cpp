// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_weights.hpp"

#include <cstring>

#include "tcn_features.hpp"

namespace borderkeys {
namespace {

uint32_t readWord(const uint8_t* data, size_t index) {
    uint32_t value = 0;
    std::memcpy(&value, data + index * sizeof(uint32_t), sizeof(value));
    return value;
}

struct DescriptorField {
    const char* name;
    uint32_t expected;
};

// The order export_weights.py writes them in, which is architecture.py's DESCRIPTOR_FIELDS.
const DescriptorField kDescriptor[TcnWeights::kDescriptorFields] = {
    {"inputFeatures", kTcnFeatureDim},
    {"timesteps", kTcnTimesteps},
    {"trunk", TcnWeights::kTrunk},
    {"expanded", TcnWeights::kExpanded},
    {"blockWidth", TcnWeights::kBlockWidth},
    {"kernelSize", TcnWeights::kKernelSize},
    {"seReduced", TcnWeights::kSeReduced},
    {"numBlocks", TcnWeights::kNumBlocks},
    {"adapterChannels", TcnWeights::kAdapterChannels},
    {"adapterKernel", TcnWeights::kAdapterKernel},
    {"spectralDim", TcnWeights::kSpectralDim},
    {"keyEmbedHidden", TcnWeights::kKeyEmbedHidden},
};

/** An IEEE 754 half float's bits as a float. */
float halfToFloat(uint16_t bits) {
    const uint32_t sign = static_cast<uint32_t>(bits & 0x8000u) << 16;
    uint32_t exponent = (bits >> 10) & 0x1Fu;
    uint32_t mantissa = bits & 0x3FFu;
    uint32_t out;
    if (exponent == 0) {
        if (mantissa == 0) {
            out = sign;
        } else {
            // Subnormal: normalised into float's range.
            exponent = 127 - 15 + 1;
            while ((mantissa & 0x400u) == 0) {
                mantissa <<= 1;
                --exponent;
            }
            mantissa &= 0x3FFu;
            out = sign | (exponent << 23) | (mantissa << 13);
        }
    } else if (exponent == 0x1Fu) {
        out = sign | 0x7F800000u | (mantissa << 13);
    } else {
        out = sign | ((exponent + 127 - 15) << 23) | (mantissa << 13);
    }
    float value;
    std::memcpy(&value, &out, sizeof(value));
    return value;
}

}  // namespace

void TcnWeights::writeHeader(uint8_t* out, uint32_t version) {
    const uint32_t words[] = {kMagic, version};
    std::memcpy(out, words, sizeof(words));
    for (int i = 0; i < kDescriptorFields; ++i) {
        const uint32_t value = kDescriptor[i].expected;
        std::memcpy(out + (2 + static_cast<size_t>(i)) * sizeof(uint32_t), &value, sizeof(value));
    }
    const uint32_t trailer[] = {static_cast<uint32_t>(kTcnWeightsFloatCount), 0u};
    std::memcpy(out + (2 + kDescriptorFields) * sizeof(uint32_t), trailer, sizeof(trailer));
}

const char* TcnWeights::describeMismatch(const uint8_t* data, size_t length) {
    if (data == nullptr) {
        return "no data";
    }
    if (length < kHeaderBytes) {
        return "shorter than the header";
    }
    if (readWord(data, 0) != kMagic) {
        return "magic";
    }
    const uint32_t version = readWord(data, 1);
    if (version != kVersion && version != kVersionHalf) {
        return "version";
    }
    for (int i = 0; i < kDescriptorFields; ++i) {
        if (readWord(data, 2 + static_cast<size_t>(i)) != kDescriptor[i].expected) {
            return kDescriptor[i].name;
        }
    }
    if (readWord(data, 2 + kDescriptorFields) != kTcnWeightsFloatCount) {
        return "float count";
    }
    const size_t valueBytes = (version == kVersionHalf) ? sizeof(uint16_t) : sizeof(float);
    if (length != kHeaderBytes + kTcnWeightsFloatCount * valueBytes) {
        return "file length";
    }
    return nullptr;
}

bool TcnWeights::loadFromBytes(const uint8_t* data, size_t length) {
    if (describeMismatch(data, length) != nullptr) {
        return false;
    }
    if (readWord(data, 1) == kVersionHalf) {
        auto* const values = reinterpret_cast<float*>(this);
        const uint8_t* const payload = data + kHeaderBytes;
        for (size_t i = 0; i < kTcnWeightsFloatCount; ++i) {
            uint16_t bits = 0;
            std::memcpy(&bits, payload + i * sizeof(uint16_t), sizeof(bits));
            values[i] = halfToFloat(bits);
        }
        return true;
    }
    std::memcpy(this, data + kHeaderBytes, sizeof(TcnWeights));
    return true;
}

}  // namespace borderkeys
