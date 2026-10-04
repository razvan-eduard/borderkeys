// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "touch_model.hpp"

#include <algorithm>
#include <cmath>

namespace borderkeys {

const TouchModel::Pattern TouchModel::kDefault = {
    0u, 0.0f, 0.0f, 0.0f,
    TouchModel::kReferenceSpread * TouchModel::kReferenceSpread,
    TouchModel::kReferenceSpread * TouchModel::kReferenceSpread,
    0.0f,
};

void TouchModel::set(const int32_t* codes, const float* taps, const float* meanX,
                     const float* meanY, const float* varianceX, const float* varianceY,
                     const float* covariance, int count) {
    clear();
    if (codes == nullptr || taps == nullptr || meanX == nullptr || meanY == nullptr ||
        varianceX == nullptr || varianceY == nullptr || covariance == nullptr) {
        return;
    }
    for (int i = 0; i < count && count_ < kMaxKeys; ++i) {
        if (codes[i] <= 0 || !(taps[i] > 0.0f)) {
            continue;
        }
        const uint32_t folded = foldCodePoint(static_cast<uint32_t>(codes[i]));
        if (find(folded) != nullptr) {
            continue;
        }
        Pattern& pattern = patterns_[count_++];
        pattern.code = folded;
        pattern.taps = taps[i];
        pattern.meanX = meanX[i];
        pattern.meanY = meanY[i];
        pattern.varianceX = std::max(varianceX[i], kMinVariance);
        pattern.varianceY = std::max(varianceY[i], kMinVariance);
        // Within 0.95 of its bound, the covariance keeps the matrix positive definite.
        const float limit = 0.95f * std::sqrt(pattern.varianceX * pattern.varianceY);
        pattern.covariance = std::clamp(covariance[i], -limit, limit);
    }
}

void TouchModel::configure(bool learned, float weight, int minTaps) {
    learned_ = learned;
    weight_ = weight;
    minTaps_ = static_cast<float>(std::max(minTaps, 1));
}

const TouchModel::Pattern* TouchModel::find(uint32_t folded) const {
    for (int i = 0; i < count_; ++i) {
        if (patterns_[i].code == folded) {
            return &patterns_[i];
        }
    }
    return nullptr;
}

const TouchModel::Pattern* TouchModel::learned(uint32_t folded) const {
    if (!learned_) {
        return nullptr;
    }
    const Pattern* const pattern = find(folded);
    return (pattern != nullptr && pattern->taps >= minTaps_) ? pattern : nullptr;
}

float TouchModel::logDensity(const Pattern& pattern, float offsetX, float offsetY) {
    const float dx = offsetX - pattern.meanX;
    const float dy = offsetY - pattern.meanY;
    const float determinant =
        pattern.varianceX * pattern.varianceY - pattern.covariance * pattern.covariance;
    // The Mahalanobis distance through the inverse of the 2x2 covariance.
    const float mahalanobis = (pattern.varianceY * dx * dx - 2.0f * pattern.covariance * dx * dy +
                               pattern.varianceX * dy * dy) /
                              determinant;
    return -0.5f * mahalanobis - 0.5f * std::log(determinant);
}

float TouchModel::substitutionCost(const KeyGeometry& geometry, uint32_t typed, uint32_t intended,
                                   float x, float y, float geometryCost) const {
    if (std::isnan(x) || std::isnan(y)) {
        return geometryCost;
    }
    float typedX = 0.0f;
    float typedY = 0.0f;
    float intendedX = 0.0f;
    float intendedY = 0.0f;
    if (!geometry.centreOf(typed, &typedX, &typedY) ||
        !geometry.centreOf(intended, &intendedX, &intendedY)) {
        return geometryCost;
    }
    const float width = geometry.keyWidth();
    const float height = geometry.keyHeight();
    const float typedDx = (x - typedX) / width;
    const float typedDy = (y - typedY) / height;
    const float intendedDx = (x - intendedX) / width;
    const float intendedDy = (y - intendedY) / height;
    const float typedSquared = typedDx * typedDx + typedDy * typedDy;
    const float intendedSquared = intendedDx * intendedDx + intendedDy * intendedDy;
    float cost = std::sqrt(std::max(intendedSquared - typedSquared, 0.0f));

    const Pattern* const typedPattern = learned(typed);
    const Pattern* const intendedPattern = learned(intended);
    if (typedPattern != nullptr || intendedPattern != nullptr) {
        const float ratio =
            logDensity(typedPattern != nullptr ? *typedPattern : kDefault, typedDx, typedDy) -
            logDensity(intendedPattern != nullptr ? *intendedPattern : kDefault, intendedDx,
                       intendedDy);
        const float learnedCost = kReferenceSpread * std::sqrt(2.0f * std::max(ratio, 0.0f));
        cost += weight_ * (learnedCost - cost);
    }
    return std::max(cost, KeyGeometry::kMinSubstitutionCost);
}

float TouchModel::tapLogLikelihood(const KeyGeometry& geometry, uint32_t key, float x,
                                   float y) const {
    float centreX = 0.0f;
    float centreY = 0.0f;
    if (std::isnan(x) || std::isnan(y) || !geometry.centreOfLetter(key, &centreX, &centreY)) {
        return kOffKeyLogLikelihood;
    }
    const float offsetX = (x - centreX) / geometry.keyWidth();
    const float offsetY = (y - centreY) / geometry.keyHeight();
    const float base = logDensity(kDefault, offsetX, offsetY);
    const Pattern* const pattern = learned(key);
    if (pattern == nullptr) {
        return base;
    }
    return base + weight_ * (logDensity(*pattern, offsetX, offsetY) - base);
}

}  // namespace borderkeys
