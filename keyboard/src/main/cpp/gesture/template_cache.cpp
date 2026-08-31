// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "template_cache.hpp"

#include <cstdint>

namespace borderkeys {

void TemplateCache::setGeometry(const KeyGeometry* geometry) {
    geometry_ = geometry;
    clear();
}

void TemplateCache::clear() {
    for (Entry& entry : entries_) {
        entry.valid = false;
        entry.key = 0;
    }
    rebuilds_ = 0;
}

const TemplateCache::Entry* TemplateCache::templateFor(uint32_t cacheKey, const uint32_t* letters,
                                                       int letterCount) {
    if (geometry_ == nullptr || letters == nullptr || letterCount <= 0 ||
        letterCount > kMaxLetters) {
        return nullptr;
    }
    Entry& entry = entries_[cacheKey % kCapacity];
    if (entry.valid && entry.key == cacheKey) {
        return &entry;
    }
    if (!build(entry, letters, letterCount)) {
        entry.valid = false;
        return nullptr;
    }
    entry.key = cacheKey;
    entry.valid = true;
    ++rebuilds_;
    return &entry;
}

bool TemplateCache::build(Entry& entry, const uint32_t* letters, int letterCount) const {
    float pointsX[kMaxLetters];
    float pointsY[kMaxLetters];
    int written = 0;

    for (int i = 0; i < letterCount; ++i) {
        const uint32_t* neighbourCodes = nullptr;
        const float* neighbourCosts = nullptr;
        // Slot zero of the neighbour ring is the key itself, which is the cheapest way to ask
        // "is this character on the keyboard, and where".
        if (geometry_->neighbours(letters[i], &neighbourCodes, &neighbourCosts) <= 0) {
            return false;
        }
        float x = 0.f;
        float y = 0.f;
        if (!geometry_->centreOf(letters[i], &x, &y)) {
            return false;
        }
        // Consecutive identical letters land on the same point. Keeping both is correct: the
        // ideal path for "ll" really does pause there, and dropping one would shorten the
        // template relative to a gesture that did pause.
        pointsX[written] = x;
        pointsY[written] = y;
        ++written;
    }

    if (written == 1) {
        // A one-letter word has no path. Fill both channels with the single point so the
        // distance metrics stay defined rather than special-cased at every call site.
        for (int i = 0; i < kResampleCount; ++i) {
            entry.locationX[i] = pointsX[0];
            entry.locationY[i] = pointsY[0];
            entry.shapeX[i] = 0.f;
            entry.shapeY[i] = 0.f;
        }
        entry.length = 0.f;
        return true;
    }

    if (!resamplePath(pointsX, pointsY, written, entry.locationX, entry.locationY,
                      kResampleCount)) {
        return false;
    }
    entry.length = pathLength(pointsX, pointsY, written);
    normaliseShape(entry.locationX, entry.locationY, kResampleCount, entry.shapeX, entry.shapeY);
    return true;
}

}  // namespace borderkeys
