// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_encoder.hpp"

#include <cmath>
#include <cstring>

namespace borderkeys {
namespace {

inline float sigmoid(float x) { return 1.f / (1.f + std::exp(-x)); }

}  // namespace

void TcnEncoder::forward(const float* features, float* outIntention, float* outSpectral) {
    // Input embedding: a 1x1 conv is a per-timestep linear layer across channels.
    const auto& w = *weights_;
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const f = features + t * kTcnFeatureDim;
        float* const out = trunk_ + t * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            float acc = w.inputEmbedBias[c];
            for (int i = 0; i < kTcnFeatureDim; ++i) {
                acc += f[i] * w.inputEmbedWeight[i * kTrunkChannels + c];
            }
            out[c] = acc;
        }
    }

    for (int b = 0; b < kNumBlocks; ++b) {
        runBlock(b, trunk_);
    }

    runAdapter(trunk_, adapted_);

    for (int t = 0; t < kOutputTimesteps; ++t) {
        const float* const a = adapted_ + t * kAdapterChannels;
        float intentionAcc = w.intentionBias;
        for (int c = 0; c < kAdapterChannels; ++c) {
            intentionAcc += a[c] * w.intentionWeight[c];
        }
        outIntention[t] = sigmoid(intentionAcc);

        float* const spectralOut = outSpectral + t * kSpectralDim;
        for (int s = 0; s < kSpectralDim; ++s) {
            float acc = w.spectralBias[s];
            for (int c = 0; c < kAdapterChannels; ++c) {
                acc += a[c] * w.spectralWeight[c * kSpectralDim + s];
            }
            spectralOut[s] = acc;
        }
    }
}

void TcnEncoder::runBlock(int blockIndex, float* trunk) {
    const TcnWeights::Block& block = weights_->blocks[blockIndex];
    const int dilation = kDilations[blockIndex];
    constexpr int kHalfKernel = kKernelSize / 2;

    // Dilated depthwise conv1d, zero-padded so the time axis stays kTcnTimesteps: each channel
    // has its own kKernelSize taps, independent of every other channel -- the "depthwise" half of
    // a depthwise-separable convolution, matching the ConvNeXt block this is patterned on.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        for (int c = 0; c < kTrunkChannels; ++c) {
            float acc = block.depthwiseBias[c];
            for (int k = 0; k < kKernelSize; ++k) {
                const int sourceT = t + (k - kHalfKernel) * dilation;
                if (sourceT < 0 || sourceT >= kTcnTimesteps) {
                    continue;  // zero-padded: contributes nothing
                }
                acc += trunk[sourceT * kTrunkChannels + c] * block.depthwiseWeight[k * kTrunkChannels + c];
            }
            // Batch normalisation folded to a single affine transform: this is an inference-only
            // engine, so there is no running mean/variance to track, only the scale and bias
            // tools/swipe_model/export_weights.py folds them into at export time.
            depthwiseOut_[t * kTrunkChannels + c] = acc * block.bnScale[c] + block.bnBias[c];
        }
    }

    // 1x1 expand (trunk -> 4x trunk) then GLU halves it back down: gated[g] = A[g] * sigmoid(B[g])
    // where A is the first half of the expansion and B the second -- the standard split, and the
    // one tools/swipe_model/model.py must produce weights in.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const in = depthwiseOut_ + t * kTrunkChannels;
        float* const expanded = expanded_ + t * kExpandedChannels;
        for (int e = 0; e < kExpandedChannels; ++e) {
            float acc = block.expandBias[e];
            for (int c = 0; c < kTrunkChannels; ++c) {
                acc += in[c] * block.expandWeight[c * kExpandedChannels + e];
            }
            expanded[e] = acc;
        }
        float* const gated = gated_ + t * kBlockChannels;
        for (int g = 0; g < kBlockChannels; ++g) {
            gated[g] = expanded[g] * sigmoid(expanded[kBlockChannels + g]);
        }
    }

    // Global response normalisation (ConvNeXt V2): each channel's activation is rescaled by how
    // large its own response is (an L2 norm over the whole gesture) relative to the AVERAGE
    // response across every channel -- a channel that is unusually active for this particular
    // swipe is amplified, one that is unusually quiet is damped, both relative to its peers
    // rather than to a fixed running statistic.
    float channelNorm[kBlockChannels];
    float normSum = 0.f;
    for (int g = 0; g < kBlockChannels; ++g) {
        float sumSquares = 0.f;
        for (int t = 0; t < kTcnTimesteps; ++t) {
            const float v = gated_[t * kBlockChannels + g];
            sumSquares += v * v;
        }
        channelNorm[g] = std::sqrt(sumSquares);
        normSum += channelNorm[g];
    }
    const float meanNorm = normSum / static_cast<float>(kBlockChannels);
    constexpr float kGrnEpsilon = 1e-6f;
    for (int t = 0; t < kTcnTimesteps; ++t) {
        float* const g = gated_ + t * kBlockChannels;
        for (int c = 0; c < kBlockChannels; ++c) {
            const float scale = channelNorm[c] / (meanNorm + kGrnEpsilon);
            g[c] = block.grnScale[c] * (g[c] * scale) + block.grnBias[c] + g[c];
        }
    }

    // 1x1 projection back to trunk width.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const in = gated_ + t * kBlockChannels;
        float* const out = projected_ + t * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            float acc = block.projectBias[c];
            for (int g = 0; g < kBlockChannels; ++g) {
                acc += in[g] * block.projectWeight[g * kTrunkChannels + c];
            }
            out[c] = acc;
        }
    }

    // Squeeze-excite: squeeze by average-pooling across the whole gesture, reduce, expand back to
    // a per-channel gate, apply before the residual sum -- letting the block learn to weight some
    // channels more than others for this particular swipe, cheaply, since the bottleneck is a
    // quarter of the trunk width.
    float pooled[kTrunkChannels];
    for (int c = 0; c < kTrunkChannels; ++c) {
        float sum = 0.f;
        for (int t = 0; t < kTcnTimesteps; ++t) {
            sum += projected_[t * kTrunkChannels + c];
        }
        pooled[c] = sum / static_cast<float>(kTcnTimesteps);
    }
    float reduced[kSeReducedChannels];
    for (int r = 0; r < kSeReducedChannels; ++r) {
        float acc = block.seReduceBias[r];
        for (int c = 0; c < kTrunkChannels; ++c) {
            acc += pooled[c] * block.seReduceWeight[c * kSeReducedChannels + r];
        }
        reduced[r] = acc > 0.f ? acc : 0.f;  // ReLU
    }
    for (int c = 0; c < kTrunkChannels; ++c) {
        float acc = block.seExpandBias[c];
        for (int r = 0; r < kSeReducedChannels; ++r) {
            acc += reduced[r] * block.seExpandWeight[r * kTrunkChannels + c];
        }
        seScratch_[c] = sigmoid(acc);
    }

    // Residual sum: the block's own contribution is the SE-gated projection, added onto the
    // trunk this block started from.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        float* const out = trunk + t * kTrunkChannels;
        const float* const proj = projected_ + t * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            out[c] += proj[c] * seScratch_[c];
        }
    }
}

void TcnEncoder::runAdapter(const float* trunk, float* outAdapted) {
    const auto& w = *weights_;
    constexpr int kStride = 2;
    constexpr int kKernel = TcnWeights::kAdapterKernel;
    for (int t = 0; t < kOutputTimesteps; ++t) {
        float* const out = outAdapted + t * kAdapterChannels;
        for (int a = 0; a < kAdapterChannels; ++a) {
            float acc = w.adapterBias[a];
            for (int k = 0; k < kKernel; ++k) {
                const int sourceT = t * kStride + k;
                const float* const in = trunk + sourceT * kTrunkChannels;
                for (int c = 0; c < kTrunkChannels; ++c) {
                    acc += in[c] * w.adapterWeight[(k * kTrunkChannels + c) * kAdapterChannels + a];
                }
            }
            out[a] = acc * w.adapterBnScale[a] + w.adapterBnBias[a];
        }
    }
}

}  // namespace borderkeys
