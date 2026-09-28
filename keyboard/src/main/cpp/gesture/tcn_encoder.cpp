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
            spectralOut[s] = w.spectralBias[s];
        }
        for (int c = 0; c < kAdapterChannels; ++c) {
            const float ac = a[c];
            const float* const row = w.spectralWeight + c * kSpectralDim;
            for (int s = 0; s < kSpectralDim; ++s) {
                spectralOut[s] += ac * row[s];
            }
        }
    }
}

void TcnEncoder::runBlock(int blockIndex, float* trunk) {
    const TcnWeights::Block& block = weights_->blocks[blockIndex];
    const int dilation = kDilations[blockIndex];
    constexpr int kHalfKernel = kKernelSize / 2;

    // Dilated depthwise conv1d, zero-padded to keep kTcnTimesteps: each channel has its own
    // kKernelSize taps.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        float* const out = depthwiseOut_ + t * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            out[c] = block.depthwiseBias[c];
        }
        for (int k = 0; k < kKernelSize; ++k) {
            const int sourceT = t + (k - kHalfKernel) * dilation;
            if (sourceT < 0 || sourceT >= kTcnTimesteps) {
                continue;  // zero-padded: contributes nothing
            }
            const float* const source = trunk + sourceT * kTrunkChannels;
            const float* const taps = block.depthwiseWeight + k * kTrunkChannels;
            for (int c = 0; c < kTrunkChannels; ++c) {
                out[c] += source[c] * taps[c];
            }
        }
        // Batch normalisation, as the scale and bias export_weights.py folds it into.
        for (int c = 0; c < kTrunkChannels; ++c) {
            out[c] = out[c] * block.bnScale[c] + block.bnBias[c];
        }
    }

    // 1x1 expand (trunk -> 4x trunk), then GLU halves it: gated[g] = A[g] * sigmoid(B[g]), A the
    // first half of the expansion and B the second.
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const in = depthwiseOut_ + t * kTrunkChannels;
        float* const expanded = expanded_ + t * kExpandedChannels;
        for (int e = 0; e < kExpandedChannels; ++e) {
            expanded[e] = block.expandBias[e];
        }
        for (int c = 0; c < kTrunkChannels; ++c) {
            const float ic = in[c];
            const float* const row = block.expandWeight + c * kExpandedChannels;
            for (int e = 0; e < kExpandedChannels; ++e) {
                expanded[e] += ic * row[e];
            }
        }
        float* const gated = gated_ + t * kBlockChannels;
        for (int g = 0; g < kBlockChannels; ++g) {
            gated[g] = expanded[g] * sigmoid(expanded[kBlockChannels + g]);
        }
    }

    // Global response normalisation (ConvNeXt V2): each channel scaled by its L2 norm over the
    // gesture relative to the mean norm across channels.
    float channelNorm[kBlockChannels];
    for (int g = 0; g < kBlockChannels; ++g) {
        channelNorm[g] = 0.f;
    }
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const row = gated_ + t * kBlockChannels;
        for (int g = 0; g < kBlockChannels; ++g) {
            channelNorm[g] += row[g] * row[g];
        }
    }
    float normSum = 0.f;
    for (int g = 0; g < kBlockChannels; ++g) {
        channelNorm[g] = std::sqrt(channelNorm[g]);
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
            out[c] = block.projectBias[c];
        }
        for (int g = 0; g < kBlockChannels; ++g) {
            const float ig = in[g];
            const float* const row = block.projectWeight + g * kTrunkChannels;
            for (int c = 0; c < kTrunkChannels; ++c) {
                out[c] += ig * row[c];
            }
        }
    }

    // Squeeze-excite: average-pool over the gesture, reduce, expand to a per-channel gate,
    // applied before the residual sum.
    float pooled[kTrunkChannels];
    for (int c = 0; c < kTrunkChannels; ++c) {
        pooled[c] = 0.f;
    }
    for (int t = 0; t < kTcnTimesteps; ++t) {
        const float* const row = projected_ + t * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            pooled[c] += row[c];
        }
    }
    for (int c = 0; c < kTrunkChannels; ++c) {
        pooled[c] /= static_cast<float>(kTcnTimesteps);
    }
    float reduced[kSeReducedChannels];
    for (int r = 0; r < kSeReducedChannels; ++r) {
        reduced[r] = block.seReduceBias[r];
    }
    for (int c = 0; c < kTrunkChannels; ++c) {
        const float pc = pooled[c];
        const float* const row = block.seReduceWeight + c * kSeReducedChannels;
        for (int r = 0; r < kSeReducedChannels; ++r) {
            reduced[r] += pc * row[r];
        }
    }
    for (int r = 0; r < kSeReducedChannels; ++r) {
        reduced[r] = reduced[r] > 0.f ? reduced[r] : 0.f;  // ReLU
    }
    for (int c = 0; c < kTrunkChannels; ++c) {
        seScratch_[c] = block.seExpandBias[c];
    }
    for (int r = 0; r < kSeReducedChannels; ++r) {
        const float rr = reduced[r];
        const float* const row = block.seExpandWeight + r * kTrunkChannels;
        for (int c = 0; c < kTrunkChannels; ++c) {
            seScratch_[c] += rr * row[c];
        }
    }
    for (int c = 0; c < kTrunkChannels; ++c) {
        seScratch_[c] = sigmoid(seScratch_[c]);
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
            out[a] = w.adapterBias[a];
        }
        for (int k = 0; k < kKernel; ++k) {
            const int sourceT = t * kStride + k;
            const float* const in = trunk + sourceT * kTrunkChannels;
            for (int c = 0; c < kTrunkChannels; ++c) {
                const float ic = in[c];
                const float* const row = w.adapterWeight + (k * kTrunkChannels + c) * kAdapterChannels;
                for (int a = 0; a < kAdapterChannels; ++a) {
                    out[a] += ic * row[a];
                }
            }
        }
        for (int a = 0; a < kAdapterChannels; ++a) {
            out[a] = out[a] * w.adapterBnScale[a] + w.adapterBnBias[a];
        }
    }
}

}  // namespace borderkeys
