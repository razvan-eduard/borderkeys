// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TOUCH_MODEL_HPP
#define BORDERKEYS_TOUCH_MODEL_HPP

#include <cstdint>

#include "proximity.hpp"

namespace borderkeys {

// Where one person's taps land on each letter key of the current bucket: per key, how many taps,
// and their mean offset from the key's centre and its covariance, in key units (x in key widths,
// y in key heights). Prices a substitution from a tap once both keys have enough taps.
class TouchModel {
public:
    static constexpr int kMaxKeys = KeyGeometry::kMaxKeys;

    // The spread, in key units, at which a key's pattern centred on the key prices a tap at its
    // own centre exactly as KeyGeometry's centre distance does.
    static constexpr float kReferenceSpread = 0.3f;

    // The least variance a pattern is given, in key units squared.
    static constexpr float kMinVariance = 0.01f;

    void clear() { count_ = 0; }

    // Replaces the patterns: `count` keys, each a folded code, its tap count, the mean offset and
    // the covariance. Keys past kMaxKeys, repeated codes and non-positive tap counts are dropped.
    void set(const int32_t* codes, const float* taps, const float* meanX, const float* meanY,
             const float* varianceX, const float* varianceY, const float* covariance, int count);

    // Whether the model prices anything: switched on and not empty. [weight] scales how far the
    // model moves a substitution's cost from the geometry's; [minTaps] is how many taps a key
    // needs before its pattern counts.
    void configure(bool enabled, float weight, int minTaps);
    bool active() const { return enabled_ && count_ > 0; }

    int keyCount() const { return count_; }

    // The cost of reading a tap at (`x`, `y`), in the keyboard view's pixels, on `typed` as
    // `intended`: the log-likelihood ratio of the two keys' patterns for the tap, as the centre
    // distance an equally telling tap would be under the reference spread, moved from
    // `geometryCost` by the weight, and never below KeyGeometry::kMinSubstitutionCost.
    // `geometryCost` itself when the model is inactive, the tap has no point, or either key has
    // fewer taps than the minimum.
    float substitutionCost(const KeyGeometry& geometry, uint32_t typed, uint32_t intended,
                           float x, float y, float geometryCost) const;

private:
    struct Pattern {
        uint32_t code;
        float taps;
        float meanX;
        float meanY;
        float varianceX;
        float varianceY;
        float covariance;
    };

    const Pattern* find(uint32_t folded) const;

    // The log of the pattern's density at an offset from its key's centre, without the 2π term,
    // which cancels in a ratio.
    static float logDensity(const Pattern& pattern, float offsetX, float offsetY);

    Pattern patterns_[kMaxKeys] = {};
    int count_ = 0;
    bool enabled_ = false;
    float weight_ = 1.0f;
    float minTaps_ = 0.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TOUCH_MODEL_HPP
