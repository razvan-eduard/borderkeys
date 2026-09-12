// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_ENCODER_HPP
#define BORDERKEYS_GESTURE_TCN_ENCODER_HPP

#include "tcn_features.hpp"
#include "tcn_weights.hpp"

namespace borderkeys {

// The layout-agnostic swipe encoder from "FUTO Swipe: Layout-Agnostic Neural Swipe Decoding"
// (arXiv:2606.25247), reimplemented as hand-written inference over BorderKeys' own from-scratch
// weights -- see docs/licensing.md section 2.5. Nothing here loads or was derived from FUTO's own
// released weights, which are under a non-free licence; [TcnWeights] is trained fresh on the
// free, MIT-licensed swipe.futo.org corpus (tools/swipe_model/).
//
// Architecture, reconstructed from the paper to match its reported 635K-parameter count (the
// source states the block structure and the total; kernel size, the SE bottleneck ratio and the
// adapter's output width are not given explicitly and were chosen here to land on that figure --
// `tools/swipe_model/model.py` is the single source of truth these constants must track, and is
// where to adjust them if a real training run says otherwise):
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
// Per-key logits are NOT computed here -- see [tcn_ctc_decoder.hpp]'s DCT basis matrix, built
// once per layout from [KeyGeometry] and applied to this encoder's spectral output per timestep.
// That is the entire mechanism by which one trained model serves every layout and every screen
// size with no retraining: the basis changes, the encoder never does.
class TcnEncoder {
public:
    static constexpr int kTrunkChannels = 128;
    static constexpr int kExpandedChannels = kTrunkChannels * 4;  // pre-GLU, "expansion factor 4x"
    static constexpr int kBlockChannels = kExpandedChannels / 2;  // post-GLU working width
    static constexpr int kKernelSize = 7;
    static constexpr int kSeReducedChannels = kTrunkChannels / 4;
    static constexpr int kNumBlocks = 5;
    static constexpr int kDilations[kNumBlocks] = {1, 2, 3, 5, 8};
    static constexpr int kAdapterChannels = kBlockChannels;  // 256; "widening for spatial head"
    static constexpr int kOutputTimesteps = kTcnTimesteps / 2;  // 32, after the stride-2 adapter
    static constexpr int kSpectralDim = 64;  // 8x8 2D DCT coefficients, see tcn_ctc_decoder.hpp

    /** Binds the weights this forward pass reads. Must outlive every call to [forward]. */
    void setWeights(const TcnWeights* weights) { weights_ = weights; }
    bool hasWeights() const { return weights_ != nullptr; }

    /**
     * Runs the encoder over [kTcnTimesteps] frames of [kTcnFeatureDim]-wide input (interleaved,
     * as [buildTcnFeatures] produces), writing [kOutputTimesteps] frames of intention scalars and
     * spectral coefficients.
     *
     * `outIntention` is `kOutputTimesteps` floats; `outSpectral` is
     * `kOutputTimesteps * kSpectralDim` floats, interleaved per timestep. Allocates nothing --
     * every working buffer is a member, sized at compile time, matching every other decoder in
     * this directory.
     */
    void forward(const float* features, float* outIntention, float* outSpectral);

private:
    void runBlock(int blockIndex, float* trunk);
    void runAdapter(const float* trunk, float* outAdapted);

    const TcnWeights* weights_ = nullptr;

    // Working buffers, claimed once. [kTcnTimesteps] is the widest time axis anything here runs
    // at; blocks after the adapter would use fewer, but nothing here runs after the adapter
    // except the two heads, which read `adapted_` directly.
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
