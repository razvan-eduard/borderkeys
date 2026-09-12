// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_ctc_decoder.hpp"

#include <cmath>
#include <cstring>
#include <limits>

#include "../topk.hpp"

namespace borderkeys {
namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kNegInf = -std::numeric_limits<float>::infinity();

float logSumExp(float a, float b) {
    if (a == kNegInf) return b;
    if (b == kNegInf) return a;
    const float hi = a > b ? a : b;
    const float lo = a > b ? b : a;
    return hi + std::log1p(std::exp(lo - hi));
}

/** log(sigmoid(x)), the numerically stable way: never exponentiates a large positive number. */
float logSigmoid(float x) {
    return x < 0.f ? x - std::log1p(std::exp(x)) : -std::log1p(std::exp(-x));
}

}  // namespace

void TcnCtcDecoder::setLayout(const KeyGeometry& geometry) {
    geometry_ = &geometry;
    keyCount_ = geometry.keyCount();
    if (keyCount_ > KeyGeometry::kMaxKeys) {
        keyCount_ = KeyGeometry::kMaxKeys;
    }

    // The key-area extent both the trajectory (tcn_decoder.cpp, via resampleUniformTime) and this
    // basis are normalised against. Origin stays at (0,0) -- the same key-area-local frame every
    // decoder in this directory already shares -- rather than re-deriving one from the tightest
    // bounding box, so a gesture and a key position measured against the same geometry are always
    // comparable even before either is normalised.
    float maxX = 0.f;
    float maxY = 0.f;
    for (int slot = 0; slot < keyCount_; ++slot) {
        const uint32_t codePoint = geometry.codeAt(slot);
        float cx = 0.f;
        float cy = 0.f;
        if (!geometry.centreOf(codePoint, &cx, &cy)) {
            continue;
        }
        const float right = cx + geometry.keyWidth() * 0.5f;
        const float bottom = cy + geometry.keyHeight() * 0.5f;
        if (right > maxX) maxX = right;
        if (bottom > maxY) maxY = bottom;
    }
    areaWidth_ = (maxX > 0.f) ? maxX : 1.f;
    areaHeight_ = (maxY > 0.f) ? maxY : 1.f;

    // Phi[slot][(u,v)] = cos(pi*u*u_k) * cos(pi*v*v_k) -- the 2D separable cosine basis the
    // encoder's spectral output is evaluated against. Rebuilt here and only here: the encoder
    // never sees a key position, this matrix is the entire layout-agnosticism mechanism.
    for (int slot = 0; slot < keyCount_; ++slot) {
        const uint32_t codePoint = geometry.codeAt(slot);
        float cx = 0.f;
        float cy = 0.f;
        if (!geometry.centreOf(codePoint, &cx, &cy)) {
            std::memset(basis_ + slot * TcnEncoder::kSpectralDim, 0,
                       sizeof(float) * TcnEncoder::kSpectralDim);
            continue;
        }
        const float u = cx / areaWidth_;
        const float v = cy / areaHeight_;
        float* const row = basis_ + slot * TcnEncoder::kSpectralDim;
        for (int du = 0; du < kDctResolution; ++du) {
            const float cosU = std::cos(kPi * static_cast<float>(du) * u);
            for (int dv = 0; dv < kDctResolution; ++dv) {
                row[du * kDctResolution + dv] = cosU * std::cos(kPi * static_cast<float>(dv) * v);
            }
        }
    }
}

void TcnCtcDecoder::keyLogProbsFor(const float* spectralFrame, float intentionFrame,
                                   float* outLogProbs) const {
    const float logIntention = std::log(intentionFrame > 1e-6f ? intentionFrame : 1e-6f);
    for (int slot = 0; slot < keyCount_; ++slot) {
        const float* const row = basis_ + slot * TcnEncoder::kSpectralDim;
        float z = 0.f;
        for (int d = 0; d < TcnEncoder::kSpectralDim; ++d) {
            z += spectralFrame[d] * row[d];
        }
        outLogProbs[slot] = logSigmoid(z) + logIntention;
    }
}

int TcnCtcDecoder::addOrMergeHypothesis(Hypothesis* hyps, int count, int32_t node,
                                        uint32_t lastSymbol, int32_t lastSlot,
                                        float blankContribution, float nonBlankContribution) const {
    for (int i = 0; i < count; ++i) {
        if (hyps[i].node == node && hyps[i].lastSymbol == lastSymbol) {
            hyps[i].logProbBlank = logSumExp(hyps[i].logProbBlank, blankContribution);
            hyps[i].logProbNonBlank = logSumExp(hyps[i].logProbNonBlank, nonBlankContribution);
            return count;
        }
    }
    if (count >= kMaxBeamWidth * 4) {
        return count;  // dropped: the array is already carrying more than pruneToBeamWidth keeps
    }
    hyps[count] = Hypothesis{node, lastSymbol, lastSlot, blankContribution, nonBlankContribution};
    return count + 1;
}

int TcnCtcDecoder::pruneToBeamWidth(Hypothesis* hyps, int count) const {
    // Small, fixed-size selection sort by total log-probability descending -- count is bounded by
    // kMaxBeamWidth*4, so this is at most a few hundred comparisons, cheaper than the bookkeeping
    // a heap would need for a beam this size.
    const int kept = (count < kMaxBeamWidth) ? count : kMaxBeamWidth;
    for (int i = 0; i < kept; ++i) {
        int best = i;
        float bestScore = logSumExp(hyps[i].logProbBlank, hyps[i].logProbNonBlank);
        for (int j = i + 1; j < count; ++j) {
            const float score = logSumExp(hyps[j].logProbBlank, hyps[j].logProbNonBlank);
            if (score > bestScore) {
                best = j;
                bestScore = score;
            }
        }
        if (best != i) {
            const Hypothesis tmp = hyps[i];
            hyps[i] = hyps[best];
            hyps[best] = tmp;
        }
    }
    return kept;
}

int TcnCtcDecoder::decode(const float* intention, const float* spectral, const PackedTrie& trie,
                          int packIndex, GestureScorer& scorer, Candidate* out, int maxOut) {
    if (geometry_ == nullptr || keyCount_ <= 0 || out == nullptr || maxOut <= 0) {
        return 0;
    }

    Hypothesis* current = beamA_;
    Hypothesis* next = beamB_;
    current[0] = Hypothesis{trie.root(), 0, -1, 0.f, kNegInf};  // empty prefix, probability 1
    int currentCount = 1;

    for (int t = 0; t < TcnEncoder::kOutputTimesteps; ++t) {
        keyLogProbsFor(spectral + t * TcnEncoder::kSpectralDim, intention[t], keyLogProbs_);
        const float blankLogProb = std::log1p(-(intention[t] > 0.999999f ? 0.999999f : intention[t]));

        int nextCount = 0;
        for (int i = 0; i < currentCount; ++i) {
            const Hypothesis& h = current[i];
            const float total = logSumExp(h.logProbBlank, h.logProbNonBlank);

            // Stay via blank: the prefix is unchanged.
            nextCount = addOrMergeHypothesis(next, nextCount, h.node, h.lastSymbol, h.lastSlot,
                                             /*blank=*/total + blankLogProb, kNegInf);

            // Repeat the last emitted symbol without a blank in between: CTC collapses this into
            // the SAME prefix, one instance of the letter, not two.
            if (h.lastSymbol != 0) {
                nextCount = addOrMergeHypothesis(next, nextCount, h.node, h.lastSymbol, h.lastSlot,
                                                 kNegInf, h.logProbNonBlank + keyLogProbs_[h.lastSlot]);
            }

            // Extend via every key the trie can actually follow from here.
            for (int slot = 0; slot < keyCount_; ++slot) {
                const uint32_t codePoint = geometry_->codeAt(slot);
                const int symbol = trie.symbolFor(codePoint);
                if (symbol <= 0) {
                    continue;
                }
                const int32_t child = trie.walk(h.node, symbol);
                if (child < 0) {
                    continue;
                }
                const float charLogProb = keyLogProbs_[slot];
                if (static_cast<uint32_t>(symbol) == h.lastSymbol) {
                    // The same letter again, but as a NEW instance: only reachable by having
                    // passed through a blank first, which is exactly what h.logProbBlank tracks.
                    nextCount = addOrMergeHypothesis(next, nextCount, child,
                                                     static_cast<uint32_t>(symbol), slot, kNegInf,
                                                     h.logProbBlank + charLogProb);
                } else {
                    nextCount = addOrMergeHypothesis(next, nextCount, child,
                                                     static_cast<uint32_t>(symbol), slot, kNegInf,
                                                     total + charLogProb);
                }
            }
        }

        currentCount = pruneToBeamWidth(next, nextCount);
        Hypothesis* const swap = current;
        current = next;
        next = swap;
        // `next` (== the old `current`) is overwritten from scratch next iteration; nothing in it
        // needs clearing.
    }

    TopK<Candidate> heap;
    Candidate heapStorage[16];
    heap.reset(heapStorage, static_cast<int>(sizeof(heapStorage) / sizeof(heapStorage[0])));
    for (int i = 0; i < currentCount; ++i) {
        const int32_t wordIndex = trie.terminalWordIndex(current[i].node);
        if (wordIndex < 0) {
            continue;
        }
        uint32_t textLength = 0;
        const char* const text = trie.wordText(static_cast<uint32_t>(wordIndex), &textLength);
        if (text == nullptr || textLength == 0) {
            continue;
        }
        const float ctcScore = logSumExp(current[i].logProbBlank, current[i].logProbNonBlank);
        float score = ctcScore + scorer.packWeightLog(packIndex) +
                      scorer.contextLogProb(packIndex, static_cast<uint32_t>(wordIndex)) +
                      scorer.userBoost(text, textLength);
        heap.offer(Candidate{packIndex, wordIndex, score});
    }

    Candidate drained[16];
    const int drainedCount = heap.drainSorted(drained, static_cast<int>(sizeof(drained) / sizeof(drained[0])));
    const int written = (drainedCount < maxOut) ? drainedCount : maxOut;
    for (int i = 0; i < written; ++i) {
        out[i] = drained[i];
    }
    return written;
}

}  // namespace borderkeys
