// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_DECODER_HPP
#define BORDERKEYS_GESTURE_DECODER_HPP

#include <cstdint>

#include "../candidate.hpp"
#include "../packed_trie.hpp"
#include "../proximity.hpp"

namespace borderkeys {

// What a gesture decoder needs from the rest of the engine, expressed without it knowing what
// an engine is.
//
// The decoder scores words; the language packs, their weights and the n-gram context belong to
// the engine. Passing them through an interface rather than a pointer to Engine is what lets a
// decoder be built and tested against a stub, and what keeps the header cycle from existing.
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
};

// The contract that makes the two decoding tiers interchangeable.
//
// Tier A is geometric and ships in every build. Tier B is neural and exists only in the `plus`
// flavor's binary at all -- a compile-time flag (BORDERKEYS_NEURAL_SWIPE), not a runtime one, so
// `core` has no trace of it regardless of any setting. Within a `plus` build, which one answers
// a given request is a runtime choice (Engine::setSwipeModelEnabled, an "experimental swipe
// model" preference, off by default) -- so there is an `if (neural)`, but it is one branch per
// gesture, not one per finger movement, and it does not exist at all in `core`.
//
// `decode` takes raw touch samples and fills `out` with candidates, best first, returning how
// many were written. It allocates nothing: every buffer it needs was claimed when the decoder
// was built.
class GestureDecoder {
public:
    virtual ~GestureDecoder() = default;

    /** Called whenever the keyboard is measured. Invalidates any cached templates. */
    virtual void setLayout(const KeyGeometry& geometry) = 0;

    virtual int decode(const float* xs, const float* ys, const int64_t* ts, int count,
                       Candidate* out, int maxOut) = 0;

    /** Human-readable name, for traces and for the About screen's attribution requirements. */
    virtual const char* name() const = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_DECODER_HPP
