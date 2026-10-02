// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "shark2_decoder.hpp"

#include "../marks.hpp"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

namespace borderkeys {
namespace {

// How far from the gesture's first and last point a word's first and last key may be, in key
// widths.
constexpr float kEndpointRadius = 1.7f;

// How close the path must come to a key's centre, in key widths, for a word using that key to
// stay reachable in the trie descent.
constexpr float kTouchRadius = 1.1f;

// The band the template's path length must be in, relative to the gesture's.
constexpr float kMinLengthRatio = 0.35f;
constexpr float kMaxLengthRatio = 2.60f;

// The weights turning the shape distance (in normalised bounding-box units) and the location
// distance (in key widths) into log-probability terms.
constexpr float kShapeWeight = 16.0f;
constexpr float kLocationWeight = 8.0f;

// Trie nodes one gesture may visit.
constexpr int kVisitBudget = 60000;

constexpr int8_t kNoOccurrence = static_cast<int8_t>(kResampleCount);

}  // namespace

void Shark2Decoder::setLayout(const KeyGeometry& geometry) {
    geometry_ = &geometry;
    // The cached templates are rebuilt for the new key centres.
    templates_.setGeometry(&geometry);
}

void Shark2Decoder::buildTouchSequence() {
    const int slots = geometry_->keyCount();
    for (int i = 0; i < kResampleCount; ++i) {
        touchedSlot_[i] = static_cast<int8_t>(geometry_->nearestSlot(pathX_[i], pathY_[i]));
    }

    const float radius = kTouchRadius * geometry_->keyWidth();
    const float radiusSquared = radius * radius;

    // Built backwards in one pass: position i's answer is either i or position i + 1's.
    for (int slot = 0; slot < KeyGeometry::kMaxKeys; ++slot) {
        nextOccurrence_[kResampleCount][slot] = kNoOccurrence;
    }
    for (int position = kResampleCount - 1; position >= 0; --position) {
        std::memcpy(nextOccurrence_[position], nextOccurrence_[position + 1],
                    sizeof(nextOccurrence_[0]));
        const int slot = touchedSlot_[position];
        if (slot >= 0 && slot < slots) {
            nextOccurrence_[position][slot] = static_cast<int8_t>(position);
        }
        // Every key the finger passes within kTouchRadius of, not only the nearest.
        for (int other = 0; other < slots; ++other) {
            if (other == slot) {
                continue;
            }
            float centreX = 0.f;
            float centreY = 0.f;
            if (!geometry_->centreOf(geometry_->codeAt(other), &centreX, &centreY)) {
                continue;
            }
            const float dx = centreX - pathX_[position];
            const float dy = centreY - pathY_[position];
            if (dx * dx + dy * dy <= radiusSquared) {
                nextOccurrence_[position][other] = static_cast<int8_t>(position);
            }
        }
    }
}

bool Shark2Decoder::passesLengthBand(float templateLength) const {
    if (gestureLength_ <= 0.f) {
        return false;
    }
    const float ratio = templateLength / gestureLength_;
    return ratio >= kMinLengthRatio && ratio <= kMaxLengthRatio;
}

float Shark2Decoder::shapeDistance(const float* candidateShapeX, const float* candidateShapeY) const {
    float total = 0.f;
    for (int i = 0; i < kResampleCount; ++i) {
        const float dx = shapeX_[i] - candidateShapeX[i];
        const float dy = shapeY_[i] - candidateShapeY[i];
        total += std::sqrt(dx * dx + dy * dy);
    }
    return total / static_cast<float>(kResampleCount);
}

float Shark2Decoder::locationDistance(const float* candidateLocationX,
                                      const float* candidateLocationY) const {
    const float keyWidth = geometry_->keyWidth();
    if (!(keyWidth > 0.f)) {
        return 0.f;
    }
    float total = 0.f;
    for (int i = 0; i < kResampleCount; ++i) {
        const float dx = pathX_[i] - candidateLocationX[i];
        const float dy = pathY_[i] - candidateLocationY[i];
        total += std::sqrt(dx * dx + dy * dy);
    }
    return total / (static_cast<float>(kResampleCount) * keyWidth);
}

float Shark2Decoder::bestGeometryLogProb(const TemplateCache::Entry& candidate) const {
    float best = -std::numeric_limits<float>::infinity();
    if (passesLengthBand(candidate.length)) {
        const float shape = shapeDistance(candidate.shapeX, candidate.shapeY);
        const float location = locationDistance(candidate.locationX, candidate.locationY);
        best = -kShapeWeight * shape - kLocationWeight * location;
    }
    if (candidate.hasLoop && passesLengthBand(candidate.loopLength)) {
        const float shape = shapeDistance(candidate.loopShapeX, candidate.loopShapeY);
        const float location = locationDistance(candidate.loopLocationX, candidate.loopLocationY);
        const float loop = -kShapeWeight * shape - kLocationWeight * location;
        if (loop > best) {
            best = loop;
        }
    }
    return best;
}

void Shark2Decoder::walk(int packIndex, const PackedTrie& trie, int32_t node, int position,
                         int depth, uint32_t* letters, TopK<Candidate>& heap, int marksUsed) {
    if (visitBudget_ <= 0 || depth >= kMaxWordLetters) {
        return;
    }
    --visitBudget_;
    ++lastVisitedNodes_;

    // A word ends here. Everything below decides whether it is worth measuring.
    const int32_t firstIndex = trie.terminalWordIndex(node);
    if (firstIndex >= 0 && depth >= 2) {
        const float endX = pathX_[kResampleCount - 1];
        const float endY = pathY_[kResampleCount - 1];
        float lastX = 0.f;
        float lastY = 0.f;
        const float radius = kEndpointRadius * geometry_->keyWidth();
        if (geometry_->centreOf(letters[depth - 1], &lastX, &lastY)) {
            const float dx = lastX - endX;
            const float dy = lastY - endY;
            const int32_t wordIndex =
                (dx * dx + dy * dy <= radius * radius)
                    ? scorer_.offeredSpelling(packIndex, static_cast<uint32_t>(firstIndex))
                    : -1;
            if (wordIndex >= 0) {
                const uint32_t cacheKey =
                    (static_cast<uint32_t>(packIndex) << 30) | static_cast<uint32_t>(firstIndex);
                const TemplateCache::Entry* candidate =
                    templates_.templateFor(cacheKey, letters, depth);
                const float geometryLogProb =
                    (candidate != nullptr) ? bestGeometryLogProb(*candidate)
                                           : -std::numeric_limits<float>::infinity();
                if (std::isfinite(geometryLogProb)) {
                    ++lastScoredWords_;
                    float score = scorer_.packWeightLog(packIndex) +
                                  scorer_.contextLogProb(packIndex,
                                                         static_cast<uint32_t>(wordIndex)) +
                                  geometryLogProb - kSwipeMarkCost * static_cast<float>(marksUsed);
                    uint32_t textLength = 0;
                    const char* const text =
                        trie.wordText(static_cast<uint32_t>(wordIndex), &textLength);
                    if (text != nullptr && textLength != 0) {
                        score += scorer_.userBoost(text, textLength);
                        heap.offer(Candidate{packIndex, wordIndex, score});
                    }
                }
            }
        }
    }

    if (position >= kResampleCount) {
        return;
    }

    extend(packIndex, trie, node, position, depth, letters, heap, marksUsed);

    // One mark between two letters: the trie's mark child, with no key of its own crossed.
    if (marksUsed == 0 && depth >= 1) {
        for (const uint32_t mark : kMarkCodePoints) {
            const int symbol = trie.symbolFor(mark);
            if (symbol <= 0 || visitBudget_ <= 0) {
                continue;
            }
            const int32_t child = trie.walk(node, symbol);
            if (child >= 0) {
                extend(packIndex, trie, child, position, depth, letters, heap, 1);
            }
        }
    }
}

void Shark2Decoder::extend(int packIndex, const PackedTrie& trie, int32_t node, int position,
                           int depth, uint32_t* letters, TopK<Candidate>& heap, int marksUsed) {
    const int slots = geometry_->keyCount();
    const int8_t* const reachable = nextOccurrence_[position];
    for (int slot = 0; slot < slots; ++slot) {
        const int8_t next = reachable[slot];
        if (next >= kNoOccurrence) {
            continue;  // the finger never crosses this key again
        }
        const uint32_t codePoint = geometry_->codeAt(slot);
        const int symbol = trie.symbolFor(codePoint);
        if (symbol <= 0) {
            continue;
        }
        const int32_t child = trie.walk(node, symbol);
        if (child < 0) {
            continue;
        }
        letters[depth] = codePoint;
        walk(packIndex, trie, child, next, depth + 1, letters, heap, marksUsed);
        if (visitBudget_ <= 0) {
            return;
        }
    }
}

int Shark2Decoder::decode(const float* xs, const float* ys, const int64_t* ts, int count,
                          Candidate* out, int maxOut) {
    lastVisitedNodes_ = 0;
    lastScoredWords_ = 0;
    (void)ts;  // tier A does not use time

    if (geometry_ == nullptr || !geometry_->isSet() || xs == nullptr || ys == nullptr ||
        out == nullptr || maxOut <= 0 || count < 2) {
        return 0;
    }
    const int points = (count > kMaxRawPoints) ? kMaxRawPoints : count;

    savitzkyGolaySmooth(xs, points, smoothX_);
    savitzkyGolaySmooth(ys, points, smoothY_);
    if (!resamplePath(smoothX_, smoothY_, points, pathX_, pathY_, kResampleCount)) {
        return 0;
    }
    gestureLength_ = pathLength(pathX_, pathY_, kResampleCount);
    normaliseShape(pathX_, pathY_, kResampleCount, shapeX_, shapeY_);
    buildTouchSequence();

    TopK<Candidate> heap;
    heap.reset(heapStorage_, static_cast<int>(sizeof(heapStorage_) / sizeof(heapStorage_[0])));
    visitBudget_ = kVisitBudget;

    for (int packIndex = 0; packIndex < scorer_.packCount(); ++packIndex) {
        searchPack(packIndex, heap);
    }

    Candidate drained[sizeof(heapStorage_) / sizeof(heapStorage_[0])];
    const int drainedCount = heap.drainSorted(drained, static_cast<int>(sizeof(drained) /
                                                                       sizeof(drained[0])));
    const int written = (drainedCount < maxOut) ? drainedCount : maxOut;
    for (int i = 0; i < written; ++i) {
        out[i] = drained[i];
    }
    return written;
}

void Shark2Decoder::searchPack(int packIndex, TopK<Candidate>& heap) {
    const PackedTrie* const trie = scorer_.activeTrie(packIndex);
    if (trie == nullptr) {
        return;
    }

    // Only keys within kEndpointRadius of where the finger landed can start the word.
    const float startX = pathX_[0];
    const float startY = pathY_[0];
    const float radius = kEndpointRadius * geometry_->keyWidth();
    const float radiusSquared = radius * radius;

    uint32_t letters[kMaxWordLetters];
    const int slots = geometry_->keyCount();
    for (int slot = 0; slot < slots; ++slot) {
        const int8_t first = nextOccurrence_[0][slot];
        if (first >= kNoOccurrence) {
            continue;
        }
        const uint32_t codePoint = geometry_->codeAt(slot);
        float centreX = 0.f;
        float centreY = 0.f;
        if (!geometry_->centreOf(codePoint, &centreX, &centreY)) {
            continue;
        }
        const float dx = centreX - startX;
        const float dy = centreY - startY;
        if (dx * dx + dy * dy > radiusSquared) {
            continue;
        }
        const int symbol = trie->symbolFor(codePoint);
        if (symbol <= 0) {
            continue;
        }
        const int32_t child = trie->walk(trie->root(), symbol);
        if (child < 0) {
            continue;
        }
        letters[0] = codePoint;
        walk(packIndex, *trie, child, first, 1, letters, heap, 0);
        if (visitBudget_ <= 0) {
            return;
        }
    }
}

}  // namespace borderkeys
