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
 * Tier A, in every build: geometric swipe decoding in the manner of SHARK² (Kristensson and Zhai,
 * 2004). A swipe is matched against each candidate word's path through its letters' key centres,
 * on two channels: shape, with translation and scale normalised away, and location, in absolute
 * pixels.
 */
class Shark2Decoder final : public GestureDecoder {
public:
    explicit Shark2Decoder(GestureScorer& scorer) : scorer_(scorer) {}

    void setLayout(const KeyGeometry& geometry) override;

    int decode(const float* xs, const float* ys, const int64_t* ts, int count,
               Candidate* out, int maxOut) override;

    const char* name() const override { return "SHARK2"; }

    /** Trie nodes visited by the last decode, for the replay harness. */
    int lastVisitedNodes() const { return lastVisitedNodes_; }
    int lastScoredWords() const { return lastScoredWords_; }

    /** The longest gesture the decoder will look at, in raw touch samples. */
    static constexpr int kMaxRawPoints = 1024;
    static constexpr int kMaxWordLetters = 24;

private:
    void buildTouchSequence();
    /**
     * Walks the trie recursively, letter by letter, through keys the finger crossed in the order
     * it crossed them, at most [kMaxWordLetters] deep. [marksUsed] is how many marks the path
     * has stepped through; one is allowed, between two letters.
     */
    void walk(int packIndex, const PackedTrie& trie, int32_t node, int position, int depth,
              uint32_t* letters, TopK<Candidate>& heap, int marksUsed);
    /** Continues [walk] from [node] through every key the finger crosses at or after [position]. */
    void extend(int packIndex, const PackedTrie& trie, int32_t node, int position, int depth,
                uint32_t* letters, TopK<Candidate>& heap, int marksUsed);
    bool passesLengthBand(float templateLength) const;
    float shapeDistance(const float* candidateShapeX, const float* candidateShapeY) const;
    float locationDistance(const float* candidateLocationX, const float* candidateLocationY) const;
    /** The better of the plain channels and, when the candidate has one, the loop variant --
     *  `-infinity` when neither passes the length band, so it should not be scored at all. */
    float bestGeometryLogProb(const TemplateCache::Entry& candidate) const;
    void searchPack(int packIndex, TopK<Candidate>& heap);

    GestureScorer& scorer_;
    const KeyGeometry* geometry_ = nullptr;
    TemplateCache templates_;

    // Working buffers, claimed once; `decode` allocates nothing.
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
     */
    int8_t nextOccurrence_[kResampleCount + 1][KeyGeometry::kMaxKeys] = {};

    Candidate heapStorage_[16] = {};

    int visitBudget_ = 0;
    int lastVisitedNodes_ = 0;
    int lastScoredWords_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_SHARK2_DECODER_HPP
