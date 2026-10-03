// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP
#define BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP

#include <cstddef>
#include <cstdint>

namespace borderkeys {

/**
 * The trained [TcnEncoder]'s weights as a fixed set of named arrays, matching
 * `tools/swipe_model/model.py`. A `.bkw` file is a header followed by every array below in
 * declaration order, as little-endian float32, the order `tools/swipe_model/export_weights.py`
 * writes them in.
 */
class TcnWeights {
public:
    static constexpr int kKernelSize = 7;         // == TcnEncoder::kKernelSize
    static constexpr int kTrunk = 128;             // == TcnEncoder::kTrunkChannels
    static constexpr int kExpanded = 512;          // == TcnEncoder::kExpandedChannels
    static constexpr int kBlockWidth = 256;        // == TcnEncoder::kBlockChannels
    static constexpr int kSeReduced = 32;          // == TcnEncoder::kSeReducedChannels
    static constexpr int kNumBlocks = 5;           // == TcnEncoder::kNumBlocks
    static constexpr int kAdapterChannels = 256;   // == TcnEncoder::kAdapterChannels
    static constexpr int kAdapterKernel = 2;
    static constexpr int kInputFeatures = 8;       // == kTcnFeatureDim
    static constexpr int kSpectralDim = 64;        // == TcnEncoder::kSpectralDim
    static constexpr int kKeyEmbedHidden = 96;     // == model.py's KEY_EMBED_HIDDEN

    /** One dilated ConvNeXt-style block's weights, in TcnEncoder's pipeline order. */
    struct Block {
        float depthwiseWeight[kKernelSize * kTrunk];
        float depthwiseBias[kTrunk];
        float bnScale[kTrunk];
        float bnBias[kTrunk];
        float expandWeight[kTrunk * kExpanded];
        float expandBias[kExpanded];
        float grnScale[kBlockWidth];
        float grnBias[kBlockWidth];
        float projectWeight[kBlockWidth * kTrunk];
        float projectBias[kTrunk];
        float seReduceWeight[kTrunk * kSeReduced];
        float seReduceBias[kSeReduced];
        float seExpandWeight[kSeReduced * kTrunk];
        float seExpandBias[kTrunk];
    };

    float inputEmbedWeight[kInputFeatures * kTrunk];
    float inputEmbedBias[kTrunk];
    Block blocks[kNumBlocks];
    float adapterWeight[kAdapterKernel * kTrunk * kAdapterChannels];
    float adapterBias[kAdapterChannels];
    float adapterBnScale[kAdapterChannels];
    float adapterBnBias[kAdapterChannels];
    float intentionWeight[kAdapterChannels];
    float intentionBias;
    float spectralWeight[kAdapterChannels * kSpectralDim];
    float spectralBias[kSpectralDim];

    /**
     * The key-embedding MLP: `(u, v, 64 cosine features)` -> `Linear(66,96)` -> GELU ->
     * `Linear(96,64)`, the per-key vector `TcnCtcDecoder::keyLogProbsFor` scores against.
     */
    float keyEmbedHiddenWeight[(2 + kSpectralDim) * kKeyEmbedHidden];
    float keyEmbedHiddenBias[kKeyEmbedHidden];
    float keyEmbedOutputWeight[kKeyEmbedHidden * kSpectralDim];
    float keyEmbedOutputBias[kSpectralDim];

    static constexpr uint32_t kMagic = 0x3157424Bu;  // 'B' 'K' 'W' '1', little-endian
    // The weights format versions: the payload as float32, or as IEEE half floats; any other is refused.
    static constexpr uint32_t kVersion = 3u;
    static constexpr uint32_t kVersionHalf = 4u;

    /** The architecture a file was exported for, written by `tools/swipe_model/export_weights.py`
     *  from `architecture.py`, in this declaration order. Checked field by field at load. */
    static constexpr int kDescriptorFields = 12;

    /** Header: magic, version, the descriptor, the payload's float count, one reserved word. */
    static constexpr size_t kHeaderBytes = sizeof(uint32_t) * (2 + kDescriptorFields + 2);

    /** What in `data` does not match this architecture's header, or null when nothing. */
    static const char* describeMismatch(const uint8_t* data, size_t length);

    /** Writes the header this architecture expects into [kHeaderBytes] of `out`, for [version]. */
    static void writeHeader(uint8_t* out, uint32_t version = kVersion);

    /**
     * Reads a whole `.bkw` file's bytes into this object; false, with nothing loaded, for
     * anything [describeMismatch] names.
     */
    bool loadFromBytes(const uint8_t* data, size_t length);
};

/** How many floats a `.bkw` file carries after its header: every float in [TcnWeights]. */
inline constexpr size_t kTcnWeightsFloatCount = sizeof(TcnWeights) / sizeof(float);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP
