// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_ENCODER_HPP
#define BORDERKEYS_GESTURE_TCN_ENCODER_HPP

#include "tcn_features.hpp"
#include "tcn_weights.hpp"

namespace borderkeys {

// The layout-agnostic swipe encoder of "FUTO Swipe: Layout-Agnostic Neural Swipe Decoding"
// (arXiv:2606.25247), as hand-written inference over the weights tools/swipe_model/ trains; see
// docs/licensing.md section 2.5. The constants match `tools/swipe_model/model.py`:
//
//   input(8) --1x1--> trunk(128)
//     -> 5x [dilated depthwise conv(k=7) -> batchnorm -> 1x1 expand(128->512) -> GLU(->256)
//            -> global response norm -> 1x1 project(256->128) -> squeeze-excite -> +residual]
//        dilations, in order: 1, 2, 3, 5, 8
//     -> adapter: stride-2 kernel-2 conv (128->256) + batchnorm      [T: 64 -> 32]
//     -> two heads on the 256-wide adapter output, per timestep:
//          intention scalar  (256 -> 1, sigmoid)
//          spectral coeffs   (256 -> 64)
//
// Per-key logits are computed in tcn_ctc_decoder.hpp, from the spectral output and a per-layout
// basis.
class TcnEncoder {
public:
    static constexpr int kTrunkChannels = 128;
    static constexpr int kExpandedChannels = kTrunkChannels * 4;  // pre-GLU
    static constexpr int kBlockChannels = kExpandedChannels / 2;  // post-GLU working width
    static constexpr int kKernelSize = 7;
    static constexpr int kSeReducedChannels = kTrunkChannels / 4;
    static constexpr int kNumBlocks = 5;
    static constexpr int kDilations[kNumBlocks] = {1, 2, 3, 5, 8};
    static constexpr int kAdapterChannels = kBlockChannels;  // 256
    static constexpr int kOutputTimesteps = kTcnTimesteps / 2;  // 32, after the stride-2 adapter
    static constexpr int kSpectralDim = 64;  // 8x8 2D DCT coefficients, see tcn_ctc_decoder.hpp

    /** Binds the weights this forward pass reads. Must outlive every call to [forward]. */
    void setWeights(const TcnWeights* weights) { weights_ = weights; }
    bool hasWeights() const { return weights_ != nullptr; }

    /**
     * Runs the encoder over [kTcnTimesteps] interleaved frames of [kTcnFeatureDim] features, as
     * [buildTcnFeatures] produces, into `outIntention` (kOutputTimesteps floats) and `outSpectral`
     * (kOutputTimesteps * kSpectralDim floats, interleaved per timestep). Allocates nothing.
     */
    void forward(const float* features, float* outIntention, float* outSpectral);

private:
    void runBlock(int blockIndex, float* trunk);
    void runAdapter(const float* trunk, float* outAdapted);

    const TcnWeights* weights_ = nullptr;

    // Working buffers, claimed once.
    float trunk_[kTcnTimesteps * kTrunkChannels] = {};
    float depthwiseOut_[kTcnTimesteps * kTrunkChannels] = {};
    float expanded_[kTcnTimesteps * kExpandedChannels] = {};
    float gated_[kTcnTimesteps * kBlockChannels] = {};
    float projected_[kTcnTimesteps * kTrunkChannels] = {};
    float seScratch_[kTrunkChannels] = {};
    float adapted_[(kTcnTimesteps / 2) * kAdapterChannels] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_ENCODER_HPP
