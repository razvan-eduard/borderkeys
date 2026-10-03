// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_ctc_decoder.hpp"

#include "../marks.hpp"

#include <cmath>
#include <cstring>
#include <limits>

#include "../topk.hpp"

namespace borderkeys {
namespace {

constexpr float kPi = 3.14159265358979323846f;

// How the beam's path evidence, the word's length and its frequency combine into one score:
//
//   score = ctc / max(letters,1)^kLengthNormalisation
//         + kLengthBonus * letters
//         + kFrequencyWeight * contextLogProb
constexpr float kLengthNormalisation = 0.0f;
constexpr float kLengthBonus = 3.0f;
constexpr float kFrequencyWeight = 0.5f;
constexpr float kNegInf = -std::numeric_limits<float>::infinity();

float logSumExp(float a, float b) {
    if (a == kNegInf) return b;
    if (b == kNegInf) return a;
    const float hi = a > b ? a : b;
    const float lo = a > b ? b : a;
    return hi + std::log1p(std::exp(lo - hi));
}

/** log(sigmoid(x)), computed without exponentiating a large positive number. */
float logSigmoid(float x) {
    return x < 0.f ? x - std::log1p(std::exp(x)) : -std::log1p(std::exp(-x));
}

/** The exact (erf-based) GELU, as PyTorch's `F.gelu` computes it by default. */
float gelu(float x) {
    return 0.5f * x * (1.f + std::erf(x * 0.70710678118654752440f));  // 1/sqrt(2)
}

}  // namespace

void TcnCtcDecoder::setLayout(const KeyGeometry& geometry, const TcnWeights& weights) {
    geometry_ = &geometry;
    keyCount_ = geometry.keyCount();
    if (keyCount_ > KeyGeometry::kMaxKeys) {
        keyCount_ = KeyGeometry::kMaxKeys;
    }

    // The key-area extent, from the origin, that the trajectory and this basis are both
    // normalised against.
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

    // Phi[slot] = keyEmbed(u, v, cos(pi*du*u)*cos(pi*dv*v) for every du, dv), through the trained
    // MLP (TcnWeights::keyEmbed*).
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

        // combined = [u, v, cos(pi*du*u)*cos(pi*dv*v) for du,dv in 0..8) -- matches
        // KeyEmbedding.forward's torch.cat([key_centers_uv, cosine], dim=-1) exactly.
        float combined[2 + TcnEncoder::kSpectralDim];
        combined[0] = u;
        combined[1] = v;
        for (int du = 0; du < kDctResolution; ++du) {
            const float cosU = std::cos(kPi * static_cast<float>(du) * u);
            for (int dv = 0; dv < kDctResolution; ++dv) {
                combined[2 + du * kDctResolution + dv] =
                    cosU * std::cos(kPi * static_cast<float>(dv) * v);
            }
        }

        float hidden[TcnWeights::kKeyEmbedHidden];
        for (int h = 0; h < TcnWeights::kKeyEmbedHidden; ++h) {
            float acc = weights.keyEmbedHiddenBias[h];
            for (int i = 0; i < 2 + TcnEncoder::kSpectralDim; ++i) {
                acc += combined[i] * weights.keyEmbedHiddenWeight[i * TcnWeights::kKeyEmbedHidden + h];
            }
            hidden[h] = gelu(acc);
        }

        float* const row = basis_ + slot * TcnEncoder::kSpectralDim;
        for (int o = 0; o < TcnEncoder::kSpectralDim; ++o) {
            float acc = weights.keyEmbedOutputBias[o];
            for (int h = 0; h < TcnWeights::kKeyEmbedHidden; ++h) {
                acc += hidden[h] * weights.keyEmbedOutputWeight[h * TcnEncoder::kSpectralDim + o];
            }
            row[o] = acc;
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

void TcnCtcDecoder::clearMergeTable() const {
    for (int i = 0; i < kMergeTableSize; ++i) {
        mergeTable_[i] = -1;
    }
}

int TcnCtcDecoder::addOrMergeHypothesis(Hypothesis* hyps, int count, int32_t node,
                                        uint32_t lastSymbol, int32_t lastSlot, int32_t letters,
                                        int32_t marks, float blankContribution,
                                        float nonBlankContribution) const {
    uint32_t cell = (static_cast<uint32_t>(node) * 0x9E3779B1u) ^ (lastSymbol * 0x85EBCA6Bu);
    cell &= static_cast<uint32_t>(kMergeTableSize - 1);
    for (;;) {
        const int position = mergeTable_[cell];
        if (position < 0) {
            break;
        }
        if (hyps[position].node == node && hyps[position].lastSymbol == lastSymbol) {
            hyps[position].logProbBlank = logSumExp(hyps[position].logProbBlank, blankContribution);
            hyps[position].logProbNonBlank =
                logSumExp(hyps[position].logProbNonBlank, nonBlankContribution);
            return count;
        }
        cell = (cell + 1) & static_cast<uint32_t>(kMergeTableSize - 1);
    }
    if (count >= kMaxBeamWidth * 4) {
        return count;  // dropped: the array is full
    }
    hyps[count] = Hypothesis{node,       lastSymbol,           lastSlot,
                             blankContribution, nonBlankContribution, letters, marks};
    mergeTable_[cell] = static_cast<int16_t>(count);
    return count + 1;
}

int TcnCtcDecoder::extendBySlots(const Hypothesis& h, int32_t node, int32_t marks, float markCost,
                                 const PackedTrie& trie, Hypothesis* next, int nextCount) const {
    const float total = logSumExp(h.logProbBlank, h.logProbNonBlank);
    for (int slot = 0; slot < keyCount_; ++slot) {
        const int symbol = slotSymbol_[slot];
        if (symbol <= 0) {
            continue;
        }
        const int32_t child = trie.walk(node, symbol);
        if (child < 0) {
            continue;
        }
        const float charLogProb = keyLogProbs_[slot] - markCost;
        if (static_cast<uint32_t>(symbol) == h.lastSymbol) {
            // The same letter again, but as a NEW instance: only reachable by having passed
            // through a blank first, which is exactly what h.logProbBlank tracks.
            nextCount = addOrMergeHypothesis(next, nextCount, child, static_cast<uint32_t>(symbol),
                                             slot, h.letters + 1, marks, kNegInf,
                                             h.logProbBlank + charLogProb);
        } else {
            nextCount = addOrMergeHypothesis(next, nextCount, child, static_cast<uint32_t>(symbol),
                                             slot, h.letters + 1, marks, kNegInf,
                                             total + charLogProb);
        }
    }
    return nextCount;
}

int TcnCtcDecoder::pruneToBeamWidth(Hypothesis* hyps, int count) const {
    // Selection sort by total log-probability, descending.
    const int kept = (count < kMaxBeamWidth) ? count : kMaxBeamWidth;
    float scores[kMaxBeamWidth * 4];
    for (int i = 0; i < count; ++i) {
        scores[i] = logSumExp(hyps[i].logProbBlank, hyps[i].logProbNonBlank);
    }
    for (int i = 0; i < kept; ++i) {
        int best = i;
        float bestScore = scores[i];
        for (int j = i + 1; j < count; ++j) {
            if (scores[j] > bestScore) {
                best = j;
                bestScore = scores[j];
            }
        }
        if (best != i) {
            const Hypothesis tmp = hyps[i];
            hyps[i] = hyps[best];
            hyps[best] = tmp;
            const float tmpScore = scores[i];
            scores[i] = scores[best];
            scores[best] = tmpScore;
        }
    }
    return kept;
}

int TcnCtcDecoder::decode(const float* intention, const float* spectral, const PackedTrie& trie,
                          int packIndex, GestureScorer& scorer, Candidate* out, int maxOut) {
    if (geometry_ == nullptr || keyCount_ <= 0 || out == nullptr || maxOut <= 0) {
        return 0;
    }

    for (int slot = 0; slot < keyCount_; ++slot) {
        slotSymbol_[slot] = trie.symbolFor(geometry_->codeAt(slot));
    }
    markSymbol_[0] = trie.symbolFor(kApostrophe);
    markSymbol_[1] = trie.symbolFor(kHyphen);

    Hypothesis* current = beamA_;
    Hypothesis* next = beamB_;
    current[0] = Hypothesis{trie.root(), 0, -1, 0.f, kNegInf};  // empty prefix, probability 1
    int currentCount = 1;

    for (int t = 0; t < TcnEncoder::kOutputTimesteps; ++t) {
        keyLogProbsFor(spectral + t * TcnEncoder::kSpectralDim, intention[t], keyLogProbs_);
        const float blankLogProb = std::log1p(-(intention[t] > 0.999999f ? 0.999999f : intention[t]));

        clearMergeTable();
        int nextCount = 0;
        for (int i = 0; i < currentCount; ++i) {
            const Hypothesis& h = current[i];
            const float total = logSumExp(h.logProbBlank, h.logProbNonBlank);

            // Stay via blank: the prefix is unchanged.
            nextCount = addOrMergeHypothesis(next, nextCount, h.node, h.lastSymbol, h.lastSlot,
                                             h.letters, h.marks, /*blank=*/total + blankLogProb,
                                             kNegInf);

            // Repeat the last emitted symbol without a blank in between: CTC collapses this into
            // the SAME prefix, one instance of the letter, not two.
            if (h.lastSymbol != 0) {
                nextCount = addOrMergeHypothesis(next, nextCount, h.node, h.lastSymbol, h.lastSlot,
                                                 h.letters, h.marks, kNegInf,
                                                 h.logProbNonBlank + keyLogProbs_[h.lastSlot]);
            }

            // Extend via every key the trie can actually follow from here.
            nextCount = extendBySlots(h, h.node, h.marks, 0.f, trie, next, nextCount);

            // One mark between two letters: the trie's mark child, with no key of its own.
            if (h.marks == 0 && h.lastSymbol != 0) {
                for (const int markSymbol : markSymbol_) {
                    if (markSymbol <= 0) {
                        continue;
                    }
                    const int32_t markNode = trie.walk(h.node, markSymbol);
                    if (markNode >= 0) {
                        nextCount = extendBySlots(h, markNode, 1, kMarkStepCost, trie, next,
                                                  nextCount);
                    }
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
        const int32_t firstIndex = trie.terminalWordIndex(current[i].node);
        if (firstIndex < 0) {
            continue;
        }
        const int32_t wordIndex =
            scorer.offeredSpelling(packIndex, static_cast<uint32_t>(firstIndex));
        if (wordIndex < 0) {
            continue;
        }
        uint32_t textLength = 0;
        const char* const text = trie.wordText(static_cast<uint32_t>(wordIndex), &textLength);
        if (text == nullptr || textLength == 0) {
            continue;
        }
        const float ctcScore = logSumExp(current[i].logProbBlank, current[i].logProbNonBlank);
        const float letters = static_cast<float>(current[i].letters > 0 ? current[i].letters : 1);
        const float normalised = (kLengthNormalisation > 0.0f)
                                     ? ctcScore / std::pow(letters, kLengthNormalisation)
                                     : ctcScore;
        float score = normalised + kLengthBonus * letters + scorer.packWeightLog(packIndex) +
                      kFrequencyWeight *
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
