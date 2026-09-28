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
 * Tier B, `plus` only: the trained, layout-agnostic swipe decoder; see `docs/licensing.md`
 * section 2.5. [resampleUniformTime] and [buildTcnFeatures] prepare the encoder's input,
 * [TcnEncoder] produces per-timestep output, and [TcnCtcDecoder] turns it into ranked words across
 * the active packs through the [GestureScorer].
 */
class TcnDecoder final : public GestureDecoder {
public:
    explicit TcnDecoder(GestureScorer& scorer) : scorer_(scorer) {}

    void setLayout(const KeyGeometry& geometry) override;

    int decode(const float* xs, const float* ys, const int64_t* ts, int count, Candidate* out,
              int maxOut) override;

    const char* name() const override { return "TCN"; }

    /** Loads the trained weights; false, keeping any loaded before, for a malformed file.
     *  Decoding returns nothing until this has succeeded. */
    bool loadWeights(const uint8_t* data, size_t length);

    /** Whether [loadWeights] has succeeded at least once. */
    bool hasWeights() const { return encoder_.hasWeights(); }

    /** Microseconds the last decode spent in the encoder, and in the word search over the tries. */
    int64_t lastEncoderMicros() const { return lastEncoderMicros_; }
    int64_t lastSearchMicros() const { return lastSearchMicros_; }

private:
    GestureScorer& scorer_;
    TcnWeights weights_;
    TcnEncoder encoder_;
    TcnCtcDecoder ctcDecoder_;

    // The layout, for loadWeights() to rebuild ctcDecoder_'s basis against.
    const KeyGeometry* lastGeometry_ = nullptr;
    int64_t lastEncoderMicros_ = 0;
    int64_t lastSearchMicros_ = 0;

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
