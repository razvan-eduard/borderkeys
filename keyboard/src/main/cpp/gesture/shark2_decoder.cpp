// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "shark2_decoder.hpp"

#include <cmath>
#include <cstdint>
#include <cstring>

namespace borderkeys {
namespace {

// How far from the gesture's first and last point a word's first and last key may be, in key
// widths. This is the cheapest prune there is and it removes almost everything: a swipe that
// starts on "t" is not the word "apple", and deciding that costs one distance comparison
// instead of a trie walk.
constexpr float kEndpointRadius = 1.35f;

// The template's path length must be within this band of the gesture's. A word twice as long as
// what the finger drew was not what the finger drew.
constexpr float kMinLengthRatio = 0.35f;
constexpr float kMaxLengthRatio = 2.60f;

// Turning the two distance channels into something that adds to a log-probability.
//
// Shape distance is in units of the normalised bounding box, so it runs roughly 0 to 0.5;
// location distance is in key widths and runs roughly 0 to 3. The weights put a plausible
// mismatch on each channel at about one order of magnitude of probability, which is what makes
// the geometry and the language model comparable rather than one of them decorative.
constexpr float kShapeWeight = 11.0f;
constexpr float kLocationWeight = 2.2f;

// Trie nodes one gesture may visit. The budget, not a timer, is what holds the thirty
// millisecond target: a wall-clock check would make the answer depend on how busy the device
// was, so two identical swipes could decode differently.
constexpr int kVisitBudget = 60000;

constexpr int8_t kNoOccurrence = static_cast<int8_t>(kResampleCount);

}  // namespace

void Shark2Decoder::setLayout(const KeyGeometry& geometry) {
    geometry_ = &geometry;
    // Every cached template is a path through key centres that have just moved.
    templates_.setGeometry(&geometry);
}

void Shark2Decoder::buildTouchSequence() {
    const int slots = geometry_->keyCount();
    for (int i = 0; i < kResampleCount; ++i) {
        touchedSlot_[i] = static_cast<int8_t>(geometry_->nearestSlot(pathX_[i], pathY_[i]));
    }

    // Built backwards in one pass: the answer for position i is either "here" or the answer for
    // i + 1. Quadratic in the obvious formulation, linear in this one.
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
    }
}

bool Shark2Decoder::passesLengthBand(float templateLength) const {
    if (gestureLength_ <= 0.f) {
        return false;
    }
    const float ratio = templateLength / gestureLength_;
    return ratio >= kMinLengthRatio && ratio <= kMaxLengthRatio;
}

float Shark2Decoder::shapeDistance(const TemplateCache::Entry& candidate) const {
    float total = 0.f;
    for (int i = 0; i < kResampleCount; ++i) {
        const float dx = shapeX_[i] - candidate.shapeX[i];
        const float dy = shapeY_[i] - candidate.shapeY[i];
        total += std::sqrt(dx * dx + dy * dy);
    }
    return total / static_cast<float>(kResampleCount);
}

float Shark2Decoder::locationDistance(const TemplateCache::Entry& candidate) const {
    const float keyWidth = geometry_->keyWidth();
    if (!(keyWidth > 0.f)) {
        return 0.f;
    }
    float total = 0.f;
    for (int i = 0; i < kResampleCount; ++i) {
        const float dx = pathX_[i] - candidate.locationX[i];
        const float dy = pathY_[i] - candidate.locationY[i];
        total += std::sqrt(dx * dx + dy * dy);
    }
    return total / (static_cast<float>(kResampleCount) * keyWidth);
}

void Shark2Decoder::walk(int packIndex, const PackedTrie& trie, int32_t node, int position,
                         int depth, uint32_t* letters, TopK<Candidate>& heap) {
    if (visitBudget_ <= 0 || depth >= kMaxWordLetters) {
        return;
    }
    --visitBudget_;
    ++lastVisitedNodes_;

    // A word ends here. Everything below decides whether it is worth measuring.
    const int32_t wordIndex = trie.terminalWordIndex(node);
    if (wordIndex >= 0 && depth >= 2) {
        const float endX = pathX_[kResampleCount - 1];
        const float endY = pathY_[kResampleCount - 1];
        float lastX = 0.f;
        float lastY = 0.f;
        const float radius = kEndpointRadius * geometry_->keyWidth();
        if (geometry_->centreOf(letters[depth - 1], &lastX, &lastY)) {
            const float dx = lastX - endX;
            const float dy = lastY - endY;
            if (dx * dx + dy * dy <= radius * radius) {
                const uint32_t cacheKey =
                    (static_cast<uint32_t>(packIndex) << 30) | static_cast<uint32_t>(wordIndex);
                const TemplateCache::Entry* candidate =
                    templates_.templateFor(cacheKey, letters, depth);
                if (candidate != nullptr && passesLengthBand(candidate->length)) {
                    ++lastScoredWords_;
                    const float shape = shapeDistance(*candidate);
                    const float location = locationDistance(*candidate);
                    float score = scorer_.packWeightLog(packIndex) +
                                  scorer_.contextLogProb(packIndex,
                                                         static_cast<uint32_t>(wordIndex)) -
                                  kShapeWeight * shape - kLocationWeight * location;
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
        walk(packIndex, trie, child, next, depth + 1, letters, heap);
        if (visitBudget_ <= 0) {
            return;
        }
    }
}

int Shark2Decoder::decode(const float* xs, const float* ys, const int64_t* ts, int count,
                          Candidate* out, int maxOut) {
    lastVisitedNodes_ = 0;
    lastScoredWords_ = 0;
    (void)ts;  // tier A is time-invariant; the neural tier uses velocity and acceleration

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

    // The first pruning stage, applied at the root so it costs nothing per candidate: only keys
    // near where the finger landed can start the word. On a QWERTY layout that is three or four
    // keys out of thirty, and it removes almost the whole dictionary before a single trie
    // transition is taken.
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
        walk(packIndex, *trie, child, first, 1, letters, heap);
        if (visitBudget_ <= 0) {
            return;
        }
    }
}

}  // namespace borderkeys
