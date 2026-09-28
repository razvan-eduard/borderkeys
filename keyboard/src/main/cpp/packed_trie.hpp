// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PACKED_TRIE_HPP
#define BORDERKEYS_PACKED_TRIE_HPP

#include <cstdint>

#include "bkd_format.hpp"

namespace borderkeys {

// A read-only double-array trie, reinterpreted in place over a memory mapping:
//
//     next = base[node] + symbol;  if (check[next] == node) -> next
//
// Every accessor bounds-checks; node indices are untrusted.
class PackedTrie {
public:
    // Symbol 0 is the end-of-word marker; alphabet symbols are 1..alphabetCount.
    static constexpr int kTerminalSymbol = 0;

    // Binds to an already validated mapping. `base` points at the start of the pack, and the
    // header must have passed bkdValidateHeader against `mappedBytes` before this is called.
    bool bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header);

    bool isBound() const { return baseArray_ != nullptr; }

    int32_t root() const { return 0; }

    // The alphabet index of a folded code point, offset by one so that 0 stays the terminal
    // marker; -1 when the character does not occur in this language.
    int symbolFor(uint32_t foldedCodePoint) const;

    bool alphabetContains(uint32_t foldedCodePoint) const {
        return symbolFor(foldedCodePoint) > 0;
    }

    int alphabetSize() const { return static_cast<int>(alphabetCount_); }
    uint32_t alphabetCodePointAt(int index) const {
        return (index >= 0 && index < static_cast<int>(alphabetCount_)) ? alphabet_[index] : 0u;
    }

    // Follows one transition. Returns -1 when there is no such child.
    int32_t walk(int32_t node, int symbol) const {
        if (node < 0 || static_cast<uint32_t>(node) >= nodeCount_) {
            return -1;
        }
        if (symbol < 0 || symbol > static_cast<int>(alphabetCount_)) {
            return -1;
        }
        // In 64 bits, so an untrusted base cannot overflow.
        const int64_t next = static_cast<int64_t>(baseArray_[node]) + symbol;
        if (next < 0 || next >= static_cast<int64_t>(nodeCount_)) {
            return -1;
        }
        if (checkArray_[next] != node) {
            return -1;
        }
        return static_cast<int32_t>(next);
    }

    // The word index if `node` ends a word, otherwise -1.
    int32_t terminalWordIndex(int32_t node) const {
        const int32_t terminal = walk(node, kTerminalSymbol);
        if (terminal < 0) {
            return -1;
        }
        // A terminal's base slot holds the word index, negated and offset by one.
        const int32_t encoded = baseArray_[terminal];
        if (encoded >= 0) {
            return -1;
        }
        const int64_t index = -static_cast<int64_t>(encoded) - 1;
        if (index < 0 || index >= static_cast<int64_t>(wordCount_)) {
            return -1;
        }
        return static_cast<int32_t>(index);
    }

    uint32_t wordCount() const { return wordCount_; }

    // The display form: diacritics intact, as the suggestion strip must show it. Returns null
    // and leaves `lengthOut` untouched if the index or the stored range is out of bounds.
    const char* wordText(uint32_t wordIndex, uint32_t* lengthOut) const;

    // Quantised unigram log-probability. Dequantise with logProbScale().
    uint8_t wordFreqQuantised(uint32_t wordIndex) const {
        return (wordIndex < wordCount_) ? wordFreq_[wordIndex] : 0xFFu;
    }

    float logProbScale() const { return logProbScale_; }

    float unigramLogProb(uint32_t wordIndex) const {
        return -static_cast<float>(wordFreqQuantised(wordIndex)) / logProbScale_;
    }

    /** How many spellings share this word's folded key, counting from this one onwards. */
    uint32_t spellingsFrom(uint32_t wordIndex) const {
        if (wordIndex >= wordCount_ || wordRun_ == nullptr || wordRun_[wordIndex] == 0u) {
            return 1;
        }
        const uint32_t remaining = wordCount_ - wordIndex;
        const uint32_t run = wordRun_[wordIndex];
        return run < remaining ? run : remaining;
    }

    // Whether this word is a name, always capitalised; false for an index out of bounds.
    bool isProperNoun(uint32_t wordIndex) const {
        return wordIndex < wordCount_ && wordFlags_ != nullptr &&
               (wordFlags_[wordIndex] & kWordFlagProperNoun) != 0u;
    }

    // Exact lookup of an already folded word. Returns the word index or -1.
    int32_t lookupFolded(const uint32_t* folded, int count) const;

private:
    const int32_t* baseArray_ = nullptr;
    const int32_t* checkArray_ = nullptr;
    uint32_t nodeCount_ = 0;

    const uint32_t* alphabet_ = nullptr;
    uint32_t alphabetCount_ = 0;

    const uint32_t* wordOffsets_ = nullptr;
    const uint8_t* wordFreq_ = nullptr;
    const uint8_t* wordFlags_ = nullptr;
    const uint8_t* wordRun_ = nullptr;
    const char* wordText_ = nullptr;
    uint32_t wordTextBytes_ = 0;
    uint32_t wordCount_ = 0;

    float logProbScale_ = 1.0f;

    // Direct map for the ASCII range.
    int16_t asciiSymbol_[128] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PACKED_TRIE_HPP
