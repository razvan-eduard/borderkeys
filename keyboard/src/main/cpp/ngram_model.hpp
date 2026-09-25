// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_NGRAM_MODEL_HPP
#define BORDERKEYS_NGRAM_MODEL_HPP

#include <cstdint>

#include "bkd_format.hpp"

namespace borderkeys {

// The pair and triple tables, read in place from the mapping.
//
// Pairs are a successor index: for every word, and for the sentence start, the words the corpus
// wrote after it, sorted by index, each with its quantised conditional log-probability. A pair
// lookup is a binary search within one list, and the list itself can be walked, which is what
// the next-word search does when nothing has been typed.
//
// Triples are open addressing with linear probing, capacity a power of two, load factor kept
// below 0.7 by the builder. Word ids are stored offset by one, so that a zero key means "empty
// slot" without needing a separate occupancy bitmap.
//
// Values are quantised natural log-probabilities in one byte: q = round(-logProb * scale),
// saturating at 255.
class NgramModel {
public:
    // Positive: log-probabilities are always <= 0, so any positive value is unambiguously
    // "no entry" without a second return channel.
    static constexpr float kNoEntry = 1.0f;

    // The context "a sentence began here". Outside any word index; the index keeps its list
    // after the last word's, at position wordCount. Mirrors tools/build_dict.py.
    static constexpr uint32_t kSentenceStartContext = 0xFFFFFFFEu;

    bool bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header);

    bool hasBigrams() const { return successorCount_ != 0; }
    bool hasTrigrams() const { return trigramCapacity_ != 0; }

    // `previous`, `current` and the trigram's three words are word indices as returned by
    // PackedTrie, or kSentenceStartContext. Returns kNoEntry when the n-gram was not seen.
    float bigram(uint32_t previous, uint32_t current) const;
    float trigram(uint32_t twoBack, uint32_t oneBack, uint32_t current) const;

    /**
     * The successors of `previous`: how many there are, with `firstOut` set to the position
     * of the first. Positions index [successorWord] and [successorLogProb]. Zero for a context
     * the index does not hold, and bounded by the pack's own count whatever its offsets claim.
     */
    uint32_t successors(uint32_t previous, uint32_t* firstOut) const;
    uint32_t successorWord(uint32_t position) const {
        return (position < successorCount_) ? successorWords_[position] : 0u;
    }
    float successorLogProb(uint32_t position) const {
        return (position < successorCount_) ? dequantise(successorValues_[position]) : kNoEntry;
    }

private:
    // splitmix64's finaliser. Word indices are dense small integers, so the low bits of any
    // cheap combination of them are heavily correlated; masking those directly would cluster
    // every probe sequence into the same few slots.
    static uint64_t mix(uint64_t value) {
        value += 0x9E3779B97F4A7C15ull;
        value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9ull;
        value = (value ^ (value >> 27)) * 0x94D049BB133111EBull;
        return value ^ (value >> 31);
    }

    float dequantise(uint8_t quantised) const {
        return -static_cast<float>(quantised) / logProbScale_;
    }

    const uint32_t* successorOffsets_ = nullptr;  // wordCount + 2 entries
    const uint32_t* successorWords_ = nullptr;
    const uint8_t* successorValues_ = nullptr;
    uint32_t successorCount_ = 0;
    uint32_t wordCount_ = 0;

    const uint32_t* trigramKeys_ = nullptr;  // three consecutive u32 per slot
    const uint8_t* trigramValues_ = nullptr;
    uint32_t trigramCapacity_ = 0;
    uint32_t trigramMask_ = 0;

    float logProbScale_ = 1.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_NGRAM_MODEL_HPP
