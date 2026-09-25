// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "ngram_model.hpp"

#include <cstddef>
#include <cstdint>

namespace borderkeys {

bool NgramModel::bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header) {
    successorOffsets_ = nullptr;
    successorWords_ = nullptr;
    successorValues_ = nullptr;
    successorCount_ = 0;
    wordCount_ = 0;
    trigramKeys_ = nullptr;
    if (base == nullptr || bkdValidateHeader(header, mappedBytes) != kBkdOk) {
        return false;
    }

    logProbScale_ = static_cast<float>(header.logProbScaleQ);
    wordCount_ = header.wordCount;

    successorCount_ = header.successorCount;
    if (successorCount_ != 0) {
        successorOffsets_ = reinterpret_cast<const uint32_t*>(
            base + header.sections[kSectionSuccessorOffsets].offset);
        successorWords_ = reinterpret_cast<const uint32_t*>(
            base + header.sections[kSectionSuccessorWords].offset);
        successorValues_ = reinterpret_cast<const uint8_t*>(
            base + header.sections[kSectionSuccessorValues].offset);
    }

    trigramCapacity_ = header.trigramCapacity;
    trigramMask_ = (trigramCapacity_ == 0) ? 0u : trigramCapacity_ - 1u;
    if (trigramCapacity_ != 0) {
        trigramKeys_ = reinterpret_cast<const uint32_t*>(
            base + header.sections[kSectionTrigramKeys].offset);
        trigramValues_ = reinterpret_cast<const uint8_t*>(
            base + header.sections[kSectionTrigramValues].offset);
    }
    return true;
}

uint32_t NgramModel::successors(uint32_t previous, uint32_t* firstOut) const {
    *firstOut = 0;
    if (successorCount_ == 0) {
        return 0;
    }
    const uint32_t list = (previous == kSentenceStartContext) ? wordCount_ : previous;
    if (list > wordCount_) {
        return 0;
    }
    // Clamped to the pack's own count: the offsets are file content and are read as claims.
    uint32_t begin = successorOffsets_[list];
    uint32_t end = successorOffsets_[list + 1];
    if (begin > successorCount_) {
        begin = successorCount_;
    }
    if (end > successorCount_) {
        end = successorCount_;
    }
    if (end < begin) {
        end = begin;
    }
    *firstOut = begin;
    return end - begin;
}

float NgramModel::bigram(uint32_t previous, uint32_t current) const {
    uint32_t first = 0;
    const uint32_t count = successors(previous, &first);
    uint32_t low = first;
    uint32_t high = first + count;
    while (low < high) {
        const uint32_t middle = low + (high - low) / 2u;
        const uint32_t word = successorWords_[middle];
        if (word == current) {
            return dequantise(successorValues_[middle]);
        }
        if (word < current) {
            low = middle + 1u;
        } else {
            high = middle;
        }
    }
    return kNoEntry;
}

float NgramModel::trigram(uint32_t twoBack, uint32_t oneBack, uint32_t current) const {
    if (trigramCapacity_ == 0) {
        return kNoEntry;
    }
    const uint32_t a = twoBack + 1u;
    const uint32_t b = oneBack + 1u;
    const uint32_t c = current + 1u;
    const uint64_t hash = mix((static_cast<uint64_t>(a) << 40) ^
                              (static_cast<uint64_t>(b) << 20) ^ static_cast<uint64_t>(c));
    uint32_t slot = static_cast<uint32_t>(hash) & trigramMask_;
    for (uint32_t probe = 0; probe <= trigramMask_; ++probe) {
        const uint32_t* const entry = trigramKeys_ + static_cast<size_t>(slot) * 3u;
        if (entry[0] == 0u) {
            return kNoEntry;
        }
        // The full triple is compared, not a hash of it. A hash-only table would answer a
        // colliding query with another trigram's probability, which is a wrong suggestion that
        // no test would ever reproduce.
        if (entry[0] == a && entry[1] == b && entry[2] == c) {
            return dequantise(trigramValues_[slot]);
        }
        slot = (slot + 1u) & trigramMask_;
    }
    return kNoEntry;
}

}  // namespace borderkeys
