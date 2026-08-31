// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "ngram_model.hpp"

namespace borderkeys {

bool NgramModel::bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header) {
    bigramKeys_ = nullptr;
    trigramKeys_ = nullptr;
    if (base == nullptr || bkdValidateHeader(header, mappedBytes) != kBkdOk) {
        return false;
    }

    logProbScale_ = static_cast<float>(header.logProbScaleQ);

    bigramCapacity_ = header.bigramCapacity;
    bigramMask_ = (bigramCapacity_ == 0) ? 0u : bigramCapacity_ - 1u;
    if (bigramCapacity_ != 0) {
        bigramKeys_ = reinterpret_cast<const uint64_t*>(
            base + header.sections[kSectionBigramKeys].offset);
        bigramValues_ =
            reinterpret_cast<const uint8_t*>(base + header.sections[kSectionBigramValues].offset);
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

float NgramModel::bigram(uint32_t previous, uint32_t current) const {
    if (bigramCapacity_ == 0) {
        return kNoEntry;
    }
    const uint64_t key =
        (static_cast<uint64_t>(previous + 1u) << 32) | static_cast<uint64_t>(current + 1u);
    uint32_t slot = static_cast<uint32_t>(mix(key)) & bigramMask_;
    // Bounded by the capacity: a table that the builder filled completely, or one whose keys
    // were rewritten to collide on every slot, must still terminate.
    for (uint32_t probe = 0; probe <= bigramMask_; ++probe) {
        const uint64_t stored = bigramKeys_[slot];
        if (stored == 0ull) {
            return kNoEntry;
        }
        if (stored == key) {
            return dequantise(bigramValues_[slot]);
        }
        slot = (slot + 1u) & bigramMask_;
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
