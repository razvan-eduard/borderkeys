// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TEMPLATE_CACHE_HPP
#define BORDERKEYS_GESTURE_TEMPLATE_CACHE_HPP

#include <cstdint>

#include "../proximity.hpp"
#include "resample.hpp"

namespace borderkeys {

/**
 * Words' ideal trajectories: the polyline through the centres of a word's letters' keys,
 * resampled to [kResampleCount] points, absolute and shape-normalised. Built on demand into a
 * direct-mapped cache.
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
         * Whether the word has a second trajectory, for a doubled letter: the same path with a
         * small loop at each doubled letter. The better of the two scores.
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
     * The template for a word given its folded letters, built if absent; null when a letter is
     * not on the keyboard. `cacheKey` should be unique per (pack, word).
     */
    const Entry* templateFor(uint32_t cacheKey, const uint32_t* letters, int letterCount);

    int rebuilds() const { return rebuilds_; }

private:
    static constexpr int kCapacity = 256;
    static constexpr int kMaxLetters = 32;
    /** Room for a loop of 4 extra points at every letter of a [kMaxLetters]-long word. */
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
