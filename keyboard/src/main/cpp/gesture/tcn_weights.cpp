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

}  // namespace

void TcnWeights::writeHeader(uint8_t* out) {
    const uint32_t words[] = {kMagic, kVersion};
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
    if (readWord(data, 1) != kVersion) {
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
    if (length != kHeaderBytes + kTcnWeightsFloatCount * sizeof(float)) {
        return "file length";
    }
    return nullptr;
}

bool TcnWeights::loadFromBytes(const uint8_t* data, size_t length) {
    if (describeMismatch(data, length) != nullptr) {
        return false;
    }
    std::memcpy(this, data + kHeaderBytes, sizeof(TcnWeights));
    return true;
}

}  // namespace borderkeys
