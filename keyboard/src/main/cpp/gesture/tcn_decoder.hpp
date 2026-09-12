// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_DECODER_HPP
#define BORDERKEYS_GESTURE_TCN_DECODER_HPP

#include "gesture_decoder.hpp"
#include "tcn_ctc_decoder.hpp"
#include "tcn_encoder.hpp"
#include "tcn_weights.hpp"

namespace borderkeys {

/**
 * Tier B: a trained, layout-agnostic swipe decoder -- see `docs/licensing.md` section 2.5 and
 * `tools/swipe_model/` for where the weights this loads come from. `plus`-only; see
 * `BORDERKEYS_NEURAL_SWIPE` in CMakeLists.txt.
 *
 * `decode` is the same three-stage pipeline every call this class makes is named after:
 * [resampleUniformTime] + [buildTcnFeatures] turn raw touch samples into the encoder's input,
 * [TcnEncoder] turns that into per-timestep intention/spectral output, [TcnCtcDecoder] turns THAT
 * plus the active language's lexicon into ranked words -- merged across every active pack the
 * same way [Shark2Decoder::decode] already merges across packs, through the same [GestureScorer].
 */
class TcnDecoder final : public GestureDecoder {
public:
    explicit TcnDecoder(GestureScorer& scorer) : scorer_(scorer) {}

    void setLayout(const KeyGeometry& geometry) override;

    int decode(const float* xs, const float* ys, const int64_t* ts, int count, Candidate* out,
              int maxOut) override;

    const char* name() const override { return "TCN"; }

    /** Loads the trained weights this decoder runs. Returns false (and leaves any previously
     *  loaded weights in place) on a malformed file -- see [TcnWeights::loadFromBytes]. Decoding
     *  before this succeeds once returns nothing, the same as [Shark2Decoder] before
     *  [setLayout]. */
    bool loadWeights(const uint8_t* data, size_t length);

private:
    GestureScorer& scorer_;
    TcnWeights weights_;
    TcnEncoder encoder_;
    TcnCtcDecoder ctcDecoder_;

    float resampledX_[kTcnTimesteps] = {};
    float resampledY_[kTcnTimesteps] = {};
    float features_[kTcnTimesteps * kTcnFeatureDim] = {};
    float intention_[TcnEncoder::kOutputTimesteps] = {};
    float spectral_[TcnEncoder::kOutputTimesteps * TcnEncoder::kSpectralDim] = {};

    static constexpr int kMaxPerPackCandidates = 16;
    Candidate perPackScratch_[kMaxPerPackCandidates] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_DECODER_HPP
