// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TOUCH_MODEL_HPP
#define BORDERKEYS_TOUCH_MODEL_HPP

#include <cstdint>

#include "proximity.hpp"

namespace borderkeys {

// Prices a substitution from where the tap landed. Every key has a default pattern: taps centred
// on it at the reference spread, the same for everyone. Learned patterns, per letter key of the
// current bucket (how many taps, their mean offset from the key's centre and their covariance,
// in key units: x in key widths, y in key heights), take a key's place while they count and the
// key has enough taps.
class TouchModel {
public:
    static constexpr int kMaxKeys = KeyGeometry::kMaxKeys;

    // The default pattern's spread, in key units, and the scale a log-likelihood ratio is priced
    // on: a tap on the typed key's centre costs KeyGeometry's centre distance.
    static constexpr float kReferenceSpread = 0.3f;

    // The least variance a learned pattern is given, in key units squared.
    static constexpr float kMinVariance = 0.01f;

    void clear() { count_ = 0; }

    // Replaces the learned patterns: `count` keys, each a folded code, its tap count, the mean
    // offset and the covariance. Keys past kMaxKeys, repeated codes and non-positive tap counts
    // are dropped.
    void set(const int32_t* codes, const float* taps, const float* meanX, const float* meanY,
             const float* varianceX, const float* varianceY, const float* covariance, int count);

    // Whether the learned patterns count, how far they move a substitution's cost from the
    // default patterns' cost, and how many taps a key needs before its own pattern counts.
    void configure(bool learned, float weight, int minTaps);

    int keyCount() const { return count_; }

    // The cost of reading a tap at (`x`, `y`), in the keyboard view's pixels, on `typed` as
    // `intended`: from the default patterns, √(intended² − typed²) of the tap's distances from
    // the two centres in key units; with a learned pattern on either key, moved by the weight
    // towards the two patterns' log-likelihood ratio priced on the reference spread. Never below
    // KeyGeometry::kMinSubstitutionCost; `geometryCost` when the tap has no point or either key
    // is not on the geometry.
    float substitutionCost(const KeyGeometry& geometry, uint32_t typed, uint32_t intended,
                           float x, float y, float geometryCost) const;

    // The log-likelihood of a tap at (`x`, `y`), in the keyboard view's pixels, being meant for
    // `key`, a folded letter: the default pattern's density at the tap's offset from the key's
    // centre in key units, moved by the weight towards the learned pattern's when that counts;
    // without the 2π term, so comparable across keys. A long-press letter is read at the key that
    // holds it. kOffKeyLogLikelihood when the tap has no point or the letter is not on the
    // geometry.
    float tapLogLikelihood(const KeyGeometry& geometry, uint32_t key, float x, float y) const;

    // What tapLogLikelihood gives a letter the geometry does not hold.
    static constexpr float kOffKeyLogLikelihood = -30.0f;

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

    static const Pattern kDefault;

    const Pattern* find(uint32_t folded) const;

    // `folded`'s learned pattern while learned patterns count and it has enough taps, or null.
    const Pattern* learned(uint32_t folded) const;

    // The log of the pattern's density at an offset from its key's centre, without the 2π term,
    // which cancels in a ratio.
    static float logDensity(const Pattern& pattern, float offsetX, float offsetY);

    Pattern patterns_[kMaxKeys] = {};
    int count_ = 0;
    bool learned_ = false;
    float weight_ = 1.0f;
    float minTaps_ = 1.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TOUCH_MODEL_HPP
