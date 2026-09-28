// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_DECODER_HPP
#define BORDERKEYS_GESTURE_DECODER_HPP

#include <cstdint>

#include "../candidate.hpp"
#include "../packed_trie.hpp"
#include "../proximity.hpp"

namespace borderkeys {

// What a gesture decoder needs from the engine: the packs, their weights and the n-gram context.
class GestureScorer {
public:
    virtual ~GestureScorer() = default;

    virtual int packCount() const = 0;
    /** The trie of an active pack, or null when the pack is closed or switched off. */
    virtual const PackedTrie* activeTrie(int packIndex) const = 0;
    virtual float packWeightLog(int packIndex) const = 0;
    /** Stupid-backoff log-probability of a word in the current context. */
    virtual float contextLogProb(int packIndex, uint32_t wordIndex) const = 0;
    virtual float userBoost(const char* text, uint32_t length) const = 0;
    /**
     * The first spelling of the folded-key run starting at [firstIndex] that is not blocked, or
     * -1 when every one is.
     */
    virtual int32_t offeredSpelling(int packIndex, uint32_t firstIndex) const = 0;
};

// The interface of the two decoding tiers. `decode` takes raw touch samples and fills `out` with
// candidates, best first, returning how many were written; it allocates nothing.
class GestureDecoder {
public:
    virtual ~GestureDecoder() = default;

    /** Called whenever the keyboard is measured. Invalidates any cached templates. */
    virtual void setLayout(const KeyGeometry& geometry) = 0;

    virtual int decode(const float* xs, const float* ys, const int64_t* ts, int count,
                       Candidate* out, int maxOut) = 0;

    /** A human-readable name, for traces and the About screen. */
    virtual const char* name() const = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_DECODER_HPP
