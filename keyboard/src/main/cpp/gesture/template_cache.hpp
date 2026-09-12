// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TEMPLATE_CACHE_HPP
#define BORDERKEYS_GESTURE_TEMPLATE_CACHE_HPP

#include <cstdint>

#include "../proximity.hpp"
#include "resample.hpp"

namespace borderkeys {

/**
 * The ideal trajectory for a word: the polyline through the centres of its letters' keys,
 * resampled to [kResampleCount] points, in both the absolute and the shape-normalised form.
 *
 * Built on demand and cached. Not precomputed for the dictionary: a hundred thousand words at
 * a kilobyte each is a hundred megabytes to hold something that is regenerated in a few hundred
 * operations, and which is invalidated wholesale every time the keyboard is resized.
 *
 * The cache is direct-mapped rather than an LRU. A collision costs one rebuild, which is
 * cheaper than the bookkeeping an LRU would do on every hit -- and the access pattern here is a
 * few hundred distinct words per gesture, not a working set worth modelling.
 */
class TemplateCache {
public:
    struct Entry {
        uint32_t key;
        bool valid;
        float locationX[kResampleCount];
        float locationY[kResampleCount];
        float shapeX[kResampleCount];
        float shapeY[kResampleCount];
        /** Path length in pixels, for the length-band prune. */
        float length;

        /**
         * A second candidate trajectory for a word with an adjacent repeated letter: the same
         * path, but tracing a small loop at each doubled letter instead of just passing over it
         * once. Some people swipe a double letter that way on purpose, to disambiguate it from
         * the single-letter word the same path would otherwise trace -- this is what lets that
         * gesture still match. Scored alongside the plain channels above and the better of the
         * two wins; [hasLoop] is false, and the loop arrays unused, for every other word.
         */
        bool hasLoop;
        float loopLocationX[kResampleCount];
        float loopLocationY[kResampleCount];
        float loopShapeX[kResampleCount];
        float loopShapeY[kResampleCount];
        float loopLength;
    };

    /** Invalidates everything: a template is only meaningful for one key arrangement. */
    void setGeometry(const KeyGeometry* geometry);

    void clear();

    /**
     * Returns the template for a word given its folded letters, building it if absent.
     *
     * `cacheKey` should be unique per (pack, word). Returns null when the word contains a
     * character that is not on the keyboard, which is the honest answer: a word that cannot be
     * drawn cannot have been swiped.
     */
    const Entry* templateFor(uint32_t cacheKey, const uint32_t* letters, int letterCount);

    int rebuilds() const { return rebuilds_; }

private:
    static constexpr int kCapacity = 256;
    static constexpr int kMaxLetters = 32;
    /** Room for a small loop (4 extra points) at every letter, the worst case of every letter in
     *  a [kMaxLetters]-long word doubling the one before it. */
    static constexpr int kMaxLoopPoints = kMaxLetters * 5;

    bool build(Entry& entry, const uint32_t* letters, int letterCount) const;
    /** Builds the loop variant into `entry`. `points{X,Y}` are the same letter centres `build`
     *  already resolved, including the coincident duplicate at each doubled letter. */
    bool buildLoopVariant(Entry& entry, const float* pointsX, const float* pointsY,
                          int written) const;

    const KeyGeometry* geometry_ = nullptr;
    Entry entries_[kCapacity] = {};
    int rebuilds_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TEMPLATE_CACHE_HPP
