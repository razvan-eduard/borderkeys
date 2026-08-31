// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_SHARK2_DECODER_HPP
#define BORDERKEYS_GESTURE_SHARK2_DECODER_HPP

#include "../topk.hpp"
#include "gesture_decoder.hpp"
#include "resample.hpp"
#include "template_cache.hpp"

#include <cstdint>

namespace borderkeys {

/**
 * Tier A: geometric swipe decoding, in the manner of SHARK² (Kristensson and Zhai, 2004).
 *
 * The idea it is built on is that a word has an ideal trajectory -- the polyline through the
 * centres of its letters -- and a swipe is a noisy instance of one of them. Decoding is then a
 * nearest-template search, made tractable by throwing away almost every word before measuring
 * anything.
 *
 * Two score channels, and both are needed:
 *
 *  * **Shape**, after translation and scale are normalised away. This is what recognises the
 *    same word swiped larger, smaller, or slightly off to one side.
 *  * **Location**, in absolute pixels with no normalisation at all. This is what stops the
 *    shape channel from being fooled: "were" and "tie" trace nearly the same figure in nearly
 *    the same proportions, in different places on the keyboard. Shape alone cannot separate
 *    them and does not try to.
 *
 * The expected result is about 80% top-1 on English QWERTY. That is the figure the literature
 * reports for template matching and it is not a placeholder for something better -- it is the
 * number tier B has to beat to justify its weights.
 *
 * Ships in every build, including the free one. Tier B replaces it only where it exists.
 */
class Shark2Decoder final : public GestureDecoder {
public:
    explicit Shark2Decoder(GestureScorer& scorer) : scorer_(scorer) {}

    void setLayout(const KeyGeometry& geometry) override;

    int decode(const float* xs, const float* ys, const int64_t* ts, int count,
               Candidate* out, int maxOut) override;

    const char* name() const override { return "SHARK2"; }

    /** Trie nodes visited by the last decode. Read by the replay harness, not by the keyboard. */
    int lastVisitedNodes() const { return lastVisitedNodes_; }
    int lastScoredWords() const { return lastScoredWords_; }

    /** The longest gesture the decoder will look at, in raw touch samples. */
    static constexpr int kMaxRawPoints = 1024;
    static constexpr int kMaxWordLetters = 24;

private:
    void buildTouchSequence();
    /**
     * Walks the trie letter by letter, constrained to keys the finger actually crossed and in
     * the order it crossed them.
     *
     * Recursive rather than an explicit stack, and deliberately: the letters taken so far are
     * the path to the current node, and a LIFO stack does not preserve that -- a sibling
     * subtree overwrites the array before the next branch is popped. The call stack does
     * preserve it, and the depth is capped at [kMaxWordLetters], so the recursion is bounded by
     * construction rather than by hope.
     */
    void walk(int packIndex, const PackedTrie& trie, int32_t node, int position, int depth,
              uint32_t* letters, TopK<Candidate>& heap);
    bool passesLengthBand(float templateLength) const;
    float shapeDistance(const TemplateCache::Entry& candidate) const;
    float locationDistance(const TemplateCache::Entry& candidate) const;
    void searchPack(int packIndex, TopK<Candidate>& heap);

    GestureScorer& scorer_;
    const KeyGeometry* geometry_ = nullptr;
    TemplateCache templates_;

    // Working buffers, claimed once. `decode` is called on the prediction thread inside a
    // thirty-millisecond budget and allocates nothing.
    float smoothX_[kMaxRawPoints] = {};
    float smoothY_[kMaxRawPoints] = {};
    float pathX_[kResampleCount] = {};
    float pathY_[kResampleCount] = {};
    float shapeX_[kResampleCount] = {};
    float shapeY_[kResampleCount] = {};
    float gestureLength_ = 0.f;

    /** Which key each resampled point is nearest to. */
    int8_t touchedSlot_[kResampleCount] = {};
    /**
     * `nextOccurrence_[position][slot]` is the first index at or after `position` where the
     * gesture passes over `slot`, or kResampleCount when it never does again.
     *
     * This table is the third pruning stage made cheap. Without it, asking "could the next
     * letter of this candidate still be reached" would rescan the trajectory at every trie
     * node; with it, the answer is one array read, and a letter the finger never crossed is
     * rejected before the trie is even touched.
     */
    int8_t nextOccurrence_[kResampleCount + 1][KeyGeometry::kMaxKeys] = {};

    Candidate heapStorage_[16] = {};

    int visitBudget_ = 0;
    int lastVisitedNodes_ = 0;
    int lastScoredWords_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_SHARK2_DECODER_HPP
