// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "ngram_model.hpp"

#include <cstddef>
#include <cstdint>

namespace borderkeys {

namespace {

/** The position of `word` in the sorted run [first, first + count) of `words`, or false. */
bool findSorted(const uint32_t* words, uint32_t first, uint32_t count, uint32_t word,
                uint32_t* positionOut) {
    uint32_t low = first;
    uint32_t high = first + count;
    while (low < high) {
        const uint32_t middle = low + (high - low) / 2u;
        const uint32_t candidate = words[middle];
        if (candidate == word) {
            *positionOut = middle;
            return true;
        }
        if (candidate < word) {
            low = middle + 1u;
        } else {
            high = middle;
        }
    }
    return false;
}

/** [begin, end) as the file claims it, clamped to the `count` entries the pack holds. */
uint32_t clampedRun(uint32_t begin, uint32_t end, uint32_t count, uint32_t* firstOut) {
    if (begin > count) {
        begin = count;
    }
    if (end > count) {
        end = count;
    }
    if (end < begin) {
        end = begin;
    }
    *firstOut = begin;
    return end - begin;
}

}  // namespace

bool NgramModel::bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header) {
    successorOffsets_ = nullptr;
    successorWords_ = nullptr;
    successorValues_ = nullptr;
    successorCount_ = 0;
    wordCount_ = 0;
    trigramOffsets_ = nullptr;
    trigramWords_ = nullptr;
    trigramValues_ = nullptr;
    trigramCount_ = 0;

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

    trigramCount_ = header.trigramCount;
    if (trigramCount_ != 0) {
        trigramOffsets_ = reinterpret_cast<const uint32_t*>(
            base + header.sections[kSectionTrigramOffsets].offset);
        trigramWords_ = reinterpret_cast<const uint32_t*>(
            base + header.sections[kSectionTrigramWords].offset);
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
    return clampedRun(successorOffsets_[list], successorOffsets_[list + 1], successorCount_,
                      firstOut);
}

bool NgramModel::pairPosition(uint32_t previous, uint32_t current, uint32_t* positionOut) const {
    uint32_t first = 0;
    const uint32_t count = successors(previous, &first);
    return count != 0 && findSorted(successorWords_, first, count, current, positionOut);
}

float NgramModel::bigram(uint32_t previous, uint32_t current) const {
    uint32_t position = 0;
    if (!pairPosition(previous, current, &position)) {
        return kNoEntry;
    }
    return dequantise(successorValues_[position]);
}

uint32_t NgramModel::continuations(uint32_t pair, uint32_t* firstOut) const {
    *firstOut = 0;
    if (trigramCount_ == 0 || pair >= successorCount_) {
        return 0;
    }
    return clampedRun(trigramOffsets_[pair], trigramOffsets_[pair + 1], trigramCount_, firstOut);
}

float NgramModel::trigram(uint32_t twoBack, uint32_t oneBack, uint32_t current) const {
    if (trigramCount_ == 0) {
        return kNoEntry;
    }
    uint32_t pair = 0;
    if (!pairPosition(twoBack, oneBack, &pair)) {
        return kNoEntry;
    }
    uint32_t first = 0;
    const uint32_t count = continuations(pair, &first);
    uint32_t position = 0;
    if (count == 0 || !findSorted(trigramWords_, first, count, current, &position)) {
        return kNoEntry;
    }
    return dequantise(trigramValues_[position]);
}

}  // namespace borderkeys
