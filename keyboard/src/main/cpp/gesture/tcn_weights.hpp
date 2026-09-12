// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP
#define BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP

#include <cstddef>
#include <cstdint>

namespace borderkeys {

/**
 * The trained [TcnEncoder]'s weights, as a fixed set of named arrays.
 *
 * One call site writes them in this exact declaration order (`tools/swipe_model/
 * export_weights.py`), one reads them back ([loadFromBytes] below), and there is no third format
 * anywhere -- no ONNX, no protobuf, nothing this project doesn't already read and write on its
 * own for the dictionary pack format (`bkd_format.hpp`). Every constant here has a named
 * counterpart in `TcnEncoder`; the two must never drift, which is why `tools/swipe_model/
 * model.py` -- not this file -- is the single source of truth both sides are generated to match.
 *
 * The `.bkw` file is an 8-byte header (magic, version, both little-endian u32) followed by every
 * array below, concatenated in declaration order, as little-endian float32. Fixed total size
 * rather than a section table the way `.bkd` needs one: nothing here has a length that depends on
 * the data, unlike a dictionary's word count, because the architecture is fixed at compile time.
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

    /** One dilated ConvNeXt-style block's weights -- see TcnEncoder's own doc for the pipeline
     *  each of these feeds into, in this same order. */
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

    static constexpr uint32_t kMagic = 0x3157424Bu;  // 'B' 'K' 'W' '1', little-endian -- same
                                                      // convention as bkd_format.hpp's kBkdMagic
    static constexpr uint32_t kVersion = 1u;
    static constexpr size_t kHeaderBytes = sizeof(uint32_t) * 2;

    /**
     * Reads a whole `.bkw` file's bytes into this object.
     *
     * Returns false for a wrong magic, a wrong version, or a length that is not exactly the
     * header plus this architecture's weight count -- never a partial or best-effort load. A
     * wrong-shaped weight is not "worse suggestions": every read of it downstream has to stay
     * either correct or bounds-safe, and rejecting anything but an exact match is the one check
     * that keeps that true, the same rule `bkd_format.hpp` states for the dictionary format.
     */
    bool loadFromBytes(const uint8_t* data, size_t length);
};

/** Every float in [TcnWeights], in one number: it holds nothing else, so `sizeof / sizeof(float)`
 *  is exactly the count a `.bkw` file must carry after its header, by construction rather than by
 *  a maintained constant that could drift from the struct it is meant to describe. Declared after
 *  the class rather than inside it -- a static member's initialiser cannot take the `sizeof` of
 *  its own still-incomplete enclosing type. */
inline constexpr size_t kTcnWeightsFloatCount = sizeof(TcnWeights) / sizeof(float);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_WEIGHTS_HPP
