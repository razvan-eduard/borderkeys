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
// Triples hang off the pairs: for every pair, by its position in the successor index, the words
// the corpus wrote after that pair, sorted by index, each with its own quantised conditional
// log-probability. A triple lookup is the pair's binary search and then one more inside the
// pair's own list. A triple whose pair the pack does not hold has nowhere to live, which is
// also the one triple the engine never asks about.
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
    bool hasTrigrams() const { return trigramCount_ != 0; }

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

    /**
     * The continuations of the pair at successor position `pair`: how many there are, with
     * `firstOut` set to the position of the first. Positions index [continuationWord] and
     * [continuationLogProb]. Zero for a position outside the pairs, and bounded by the pack's
     * own count whatever its offsets claim.
     */
    uint32_t continuations(uint32_t pair, uint32_t* firstOut) const;

    uint32_t continuationWord(uint32_t position) const {
        return (position < trigramCount_) ? trigramWords_[position] : 0u;
    }

    float continuationLogProb(uint32_t position) const {
        return (position < trigramCount_) ? dequantise(trigramValues_[position]) : kNoEntry;
    }

private:
    /** Whether the pair `previous` -> `current` is in the index, and where. */
    bool pairPosition(uint32_t previous, uint32_t current, uint32_t* positionOut) const;

    float dequantise(uint8_t quantised) const {
        return -static_cast<float>(quantised) / logProbScale_;
    }

    const uint32_t* successorOffsets_ = nullptr;  // wordCount + 2 entries
    const uint32_t* successorWords_ = nullptr;
    const uint8_t* successorValues_ = nullptr;
    uint32_t successorCount_ = 0;
    uint32_t wordCount_ = 0;

    const uint32_t* trigramOffsets_ = nullptr;  // successorCount + 1 entries
    const uint32_t* trigramWords_ = nullptr;
    const uint8_t* trigramValues_ = nullptr;
    uint32_t trigramCount_ = 0;

    float logProbScale_ = 1.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_NGRAM_MODEL_HPP
