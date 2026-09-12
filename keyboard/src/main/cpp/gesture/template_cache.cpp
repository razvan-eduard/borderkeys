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
    bool hasDoubledLetter = false;

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
        // Consecutive identical letters land on the same point, and arc-length resampling
        // spends no length on a zero-distance step -- so this plain path reads as the same
        // shape as the word with the double collapsed to one letter, which is exactly right for
        // a gesture that just passes over the key once. `buildLoopVariant` below is the other
        // half: the same letters, for a gesture that pauses there on purpose.
        if (written > 0 && pointsX[written - 1] == x && pointsY[written - 1] == y) {
            hasDoubledLetter = true;
        }
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
        entry.hasLoop = false;
        return true;
    }

    if (!resamplePath(pointsX, pointsY, written, entry.locationX, entry.locationY,
                      kResampleCount)) {
        return false;
    }
    entry.length = pathLength(pointsX, pointsY, written);
    normaliseShape(entry.locationX, entry.locationY, kResampleCount, entry.shapeX, entry.shapeY);

    entry.hasLoop = hasDoubledLetter && buildLoopVariant(entry, pointsX, pointsY, written);
    return true;
}

bool TemplateCache::buildLoopVariant(Entry& entry, const float* pointsX, const float* pointsY,
                                     int written) const {
    // In key widths: big enough to be a real detour the shape channel can see, small enough that
    // it stays "at" the letter rather than wandering toward its neighbours.
    constexpr float kLoopRadiusFactor = 0.28f;
    const float keyWidth = geometry_->keyWidth();
    if (!(keyWidth > 0.f)) {
        return false;
    }
    const float radius = kLoopRadiusFactor * keyWidth;

    float loopX[kMaxLoopPoints];
    float loopY[kMaxLoopPoints];
    int loopWritten = 0;

    for (int i = 0; i < written; ++i) {
        const bool isDoubled =
            i > 0 && pointsX[i] == pointsX[i - 1] && pointsY[i] == pointsY[i - 1];
        if (isDoubled && loopWritten + 5 <= kMaxLoopPoints) {
            // A small diamond around the key centre, traced before landing back on it -- a
            // detour with real length, so arc-length resampling actually spends points on it
            // instead of skipping straight through like it does for the coincident point alone.
            const float cx = pointsX[i];
            const float cy = pointsY[i];
            loopX[loopWritten] = cx - radius;
            loopY[loopWritten] = cy;
            ++loopWritten;
            loopX[loopWritten] = cx;
            loopY[loopWritten] = cy - radius;
            ++loopWritten;
            loopX[loopWritten] = cx + radius;
            loopY[loopWritten] = cy;
            ++loopWritten;
            loopX[loopWritten] = cx;
            loopY[loopWritten] = cy + radius;
            ++loopWritten;
        }
        if (loopWritten >= kMaxLoopPoints) {
            break;
        }
        loopX[loopWritten] = pointsX[i];
        loopY[loopWritten] = pointsY[i];
        ++loopWritten;
    }

    if (loopWritten < 2) {
        return false;
    }
    if (!resamplePath(loopX, loopY, loopWritten, entry.loopLocationX, entry.loopLocationY,
                      kResampleCount)) {
        return false;
    }
    entry.loopLength = pathLength(loopX, loopY, loopWritten);
    normaliseShape(entry.loopLocationX, entry.loopLocationY, kResampleCount, entry.loopShapeX,
                   entry.loopShapeY);
    return true;
}

}  // namespace borderkeys
