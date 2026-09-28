// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP
#define BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP

#include <cstdint>

#include "gesture_decoder.hpp"
#include "tcn_encoder.hpp"

namespace borderkeys {

/**
 * Turns [TcnEncoder]'s per-timestep output into ranked words: a layout-agnostic spatial head, the
 * CTC emission distribution it yields, and a CTC prefix-beam search constrained to a
 * [PackedTrie]. The per-key basis `Φ` is built from [KeyGeometry] in [setLayout] through the
 * trained key-embedding MLP, matching `tools/swipe_model/model.py`'s `KeyEmbedding`; per key and
 * timestep, `z_t = c_t · Φ^T`.
 */
class TcnCtcDecoder {
public:
    static constexpr int kDctResolution = 8;  // the MLP's own input basis is an 8x8 2D cosine
    static constexpr int kMaxBeamWidth = 100;
    static constexpr int kMaxWordLetters = 24;  // == Shark2Decoder::kMaxWordLetters

    /** Rebuilds the per-layout basis, with [weights]' key-embedding MLP, and the key-area extents
     *  [areaWidth]/[areaHeight] the gesture is normalised against, from the key centres. */
    void setLayout(const KeyGeometry& geometry, const TcnWeights& weights);

    float areaWidth() const { return areaWidth_; }
    float areaHeight() const { return areaHeight_; }

    /** The basis [setLayout] built: [keyCount] rows of [TcnEncoder::kSpectralDim] floats. */
    const float* basis() const { return basis_; }
    int keyCount() const { return keyCount_; }

    /**
     * Decodes 32 timesteps of intention gates and 64-D spectral coefficients into ranked
     * candidates from [packIndex]'s trie, with [scorer]'s language-model terms. Allocates nothing.
     */
    int decode(const float* intention, const float* spectral, const PackedTrie& trie,
              int packIndex, GestureScorer& scorer, Candidate* out, int maxOut);

private:
    struct Hypothesis {
        int32_t node = 0;
        uint32_t lastSymbol = 0;  // 0 == no symbol emitted yet
        int32_t lastSlot = -1;    // which geometry slot produced lastSymbol, for the repeat case
        float logProbBlank = 0.f;
        float logProbNonBlank = 0.f;  // -infinity until this prefix has emitted anything
        int32_t letters = 0;          // characters emitted, for the length terms in the score
    };

    /** log(sigmoid(z_t[slot] for every geometry slot)) + log(intention_t), for one timestep --
     *  the per-key character log-probability the beam search extends by. */
    void keyLogProbsFor(const float* spectralFrame, float intentionFrame, float* outLogProbs) const;

    int addOrMergeHypothesis(Hypothesis* hyps, int count, int32_t node, uint32_t lastSymbol,
                             int32_t lastSlot, int32_t letters, float blankContribution,
                             float nonBlankContribution) const;
    int pruneToBeamWidth(Hypothesis* hyps, int count) const;

    /** Forgets every position the merge table holds; called before each timestep's extensions. */
    void clearMergeTable() const;

    float basis_[KeyGeometry::kMaxKeys * TcnEncoder::kSpectralDim] = {};
    int keyCount_ = 0;
    float areaWidth_ = 0.f;
    float areaHeight_ = 0.f;
    const KeyGeometry* geometry_ = nullptr;

    // Working buffers, reused by every call.
    Hypothesis beamA_[kMaxBeamWidth * 4] = {};
    Hypothesis beamB_[kMaxBeamWidth * 4] = {};
    float keyLogProbs_[KeyGeometry::kMaxKeys] = {};

    /** The trie symbol under each key slot, resolved once per decode. */
    int slotSymbol_[KeyGeometry::kMaxKeys] = {};

    /**
     * Where a (node, last symbol) pair already sits in the beam being built, by open
     * addressing over the pair's hash: -1 for a free cell.
     */
    static constexpr int kMergeTableSize = 2048;
    mutable int16_t mergeTable_[kMergeTableSize] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP
