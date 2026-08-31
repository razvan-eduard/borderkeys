// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PACKED_TRIE_HPP
#define BORDERKEYS_PACKED_TRIE_HPP

#include <cstdint>

#include "bkd_format.hpp"

namespace borderkeys {

// A read-only double-array trie, reinterpreted in place over a memory mapping.
//
// Double array rather than LOUDS, deliberately. LOUDS stores the same trie in roughly a third
// of the space, but every child lookup becomes a rank/select over a bit vector, which is
// several dependent memory accesses plus an auxiliary index that has to be built or stored.
// The double array resolves a transition in one addition, one bounds test and one comparison:
//
//     next = base[node] + symbol;  if (check[next] == node) -> next
//
// The choice follows from what this trie is actually asked to do. Correcting a typo means
// walking the neighbourhood of what was typed, so a single keystroke visits thousands of nodes,
// not tens. Per-node latency is the budget; total bytes are not, because a language pack is a
// few megabytes of page cache shared with the APK it was mapped from. If a pack ever has to be
// carried on a device where those megabytes matter, LOUDS is the thing to reconsider, and the
// only code that would change is behind this interface.
//
// Every accessor bounds-checks. The arrays came from a file that may have been handed to us by
// anyone, so a node index is untrusted data even after the header validated, and a traversal
// that walked off the end would do so inside the process that sees every keystroke.
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
    // marker. Returns -1 when the character does not occur in this language at all, which is
    // the cheap test that lets a whole pack be skipped for a word it could not possibly hold.
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
        // 64-bit arithmetic on purpose: base[node] is a signed value out of the file, and
        // base[node] + symbol overflows int32 for a hostile base. The overflowed sum would be a
        // small in-range index that passes the check below by accident.
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
        // A terminal node has no children, so its base slot is free to carry the word index,
        // stored negated and offset by one so that word 0 is distinguishable from an unset 0.
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
    const char* wordText_ = nullptr;
    uint32_t wordTextBytes_ = 0;
    uint32_t wordCount_ = 0;

    float logProbScale_ = 1.0f;

    // Direct map for the ASCII range, which is the whole alphabet of both shipped layouts.
    // Without it every character of every candidate costs a binary search.
    int16_t asciiSymbol_[128] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PACKED_TRIE_HPP
