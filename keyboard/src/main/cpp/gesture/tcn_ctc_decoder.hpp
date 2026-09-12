// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP
#define BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP

#include <cstdint>

#include "gesture_decoder.hpp"
#include "tcn_encoder.hpp"

namespace borderkeys {

/**
 * Everything downstream of [TcnEncoder]'s raw per-timestep output: the layout-agnostic spatial
 * head (the paper's "DCT basis"), the CTC emission distribution it produces, and a lexicon-
 * constrained CTC prefix-beam search over a [PackedTrie] that turns 32 timesteps of per-key
 * probabilities into ranked words -- the same role [Shark2Decoder::walk] plays for the geometric
 * engine, in the same "constrained to what the trie actually holds" family CleverKeys' own
 * `CtcBeamDecoder` belongs to.
 *
 * The spatial head is the entire reason one trained encoder works on every layout and every
 * screen size with no retraining: `Φ`, the per-key basis matrix, is rebuilt from [KeyGeometry] in
 * [setLayout] and cached; the encoder's weights never see a key position at all, only the 64-D
 * spectral pattern `c_t` that `Φ` gets evaluated against per key, per timestep
 * (`z_t = c_t · Φ^T`). Switching layouts changes `Φ`; it never touches the encoder.
 */
class TcnCtcDecoder {
public:
    static constexpr int kDctResolution = 8;  // Phi is an 8x8 = 64-term 2D cosine basis
    static constexpr int kMaxBeamWidth = 24;
    static constexpr int kMaxWordLetters = 24;  // == Shark2Decoder::kMaxWordLetters

    /** Rebuilds the per-layout basis matrix and the key-area extents [areaWidth]/[areaHeight]
     *  the caller normalises the raw gesture against -- both derived from the same key centres,
     *  so the trajectory and the basis stay in the same [0,1]^2 space. */
    void setLayout(const KeyGeometry& geometry);

    float areaWidth() const { return areaWidth_; }
    float areaHeight() const { return areaHeight_; }

    /**
     * Decodes 32 timesteps of intention gates and 64-D spectral coefficients (as [TcnEncoder]
     * produces) into ranked candidates, walking [packIndex]'s trie and asking [scorer] for the
     * language-model terms exactly like [Shark2Decoder::walk] already does. Allocates nothing.
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
    };

    /** log(sigmoid(z_t[slot] for every geometry slot)) + log(intention_t), for one timestep --
     *  the per-key character log-probability the beam search extends by. */
    void keyLogProbsFor(const float* spectralFrame, float intentionFrame, float* outLogProbs) const;

    int addOrMergeHypothesis(Hypothesis* hyps, int count, int32_t node, uint32_t lastSymbol,
                             int32_t lastSlot, float blankContribution,
                             float nonBlankContribution) const;
    int pruneToBeamWidth(Hypothesis* hyps, int count) const;

    float basis_[KeyGeometry::kMaxKeys * TcnEncoder::kSpectralDim] = {};
    int keyCount_ = 0;
    float areaWidth_ = 0.f;
    float areaHeight_ = 0.f;
    const KeyGeometry* geometry_ = nullptr;

    // Working buffers -- decode() runs inside the same real-time budget every other decoder in
    // this directory does, so nothing here is allocated per call.
    Hypothesis beamA_[kMaxBeamWidth * 4] = {};
    Hypothesis beamB_[kMaxBeamWidth * 4] = {};
    float keyLogProbs_[KeyGeometry::kMaxKeys] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_CTC_DECODER_HPP
