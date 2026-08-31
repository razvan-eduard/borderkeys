// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "packed_trie.hpp"

#include <cstdint>
#include <cstring>

namespace borderkeys {

bool PackedTrie::bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header) {
    baseArray_ = nullptr;
    checkArray_ = nullptr;
    if (base == nullptr || bkdValidateHeader(header, mappedBytes) != kBkdOk) {
        return false;
    }

    const BkdSection& baseSection = header.sections[kSectionTrieBase];
    const BkdSection& checkSection = header.sections[kSectionTrieCheck];
    const BkdSection& alphabetSection = header.sections[kSectionAlphabet];
    const BkdSection& offsetsSection = header.sections[kSectionWordOffsets];
    const BkdSection& freqSection = header.sections[kSectionWordFreq];
    const BkdSection& textSection = header.sections[kSectionWordText];

    nodeCount_ = header.nodeCount;
    wordCount_ = header.wordCount;
    alphabetCount_ = header.alphabetCount;
    logProbScale_ = static_cast<float>(header.logProbScaleQ);

    baseArray_ = reinterpret_cast<const int32_t*>(base + baseSection.offset);
    checkArray_ = reinterpret_cast<const int32_t*>(base + checkSection.offset);
    alphabet_ = reinterpret_cast<const uint32_t*>(base + alphabetSection.offset);
    wordOffsets_ = (wordCount_ == 0)
                       ? nullptr
                       : reinterpret_cast<const uint32_t*>(base + offsetsSection.offset);
    wordFreq_ =
        (wordCount_ == 0) ? nullptr : reinterpret_cast<const uint8_t*>(base + freqSection.offset);
    wordText_ = reinterpret_cast<const char*>(base + textSection.offset);
    wordTextBytes_ = static_cast<uint32_t>(textSection.length);

    // The root must be a real node with no parent. A file claiming otherwise has either been
    // corrupted or built by something that is not our tool, and either way every traversal
    // below would start from a lie.
    if (checkArray_[0] != -1) {
        baseArray_ = nullptr;
        return false;
    }

    // The alphabet has to be strictly ascending: symbolFor binary-searches it, and an unsorted
    // array would silently fail to find characters that are present.
    for (uint32_t i = 1; i < alphabetCount_; ++i) {
        if (alphabet_[i] <= alphabet_[i - 1]) {
            baseArray_ = nullptr;
            return false;
        }
    }

    // The offset table must be non-decreasing and stay inside the text blob. Checked once here
    // rather than on every wordText() call, because this loop is bounded by wordCount and the
    // accessor is on the suggestion path.
    if (wordCount_ != 0) {
        if (wordOffsets_[0] != 0u) {
            baseArray_ = nullptr;
            return false;
        }
        for (uint32_t i = 1; i <= wordCount_; ++i) {
            if (wordOffsets_[i] < wordOffsets_[i - 1] || wordOffsets_[i] > wordTextBytes_) {
                baseArray_ = nullptr;
                return false;
            }
        }
    }

    std::memset(asciiSymbol_, -1, sizeof(asciiSymbol_));
    for (uint32_t i = 0; i < alphabetCount_; ++i) {
        const uint32_t codePoint = alphabet_[i];
        if (codePoint < 128u) {
            asciiSymbol_[codePoint] = static_cast<int16_t>(i + 1);
        }
    }
    return true;
}

int PackedTrie::symbolFor(uint32_t foldedCodePoint) const {
    if (baseArray_ == nullptr) {
        return -1;
    }
    if (foldedCodePoint < 128u) {
        return asciiSymbol_[foldedCodePoint];
    }
    uint32_t low = 0;
    uint32_t high = alphabetCount_;
    while (low < high) {
        const uint32_t mid = low + (high - low) / 2;
        const uint32_t value = alphabet_[mid];
        if (value == foldedCodePoint) {
            return static_cast<int>(mid + 1);
        }
        if (value < foldedCodePoint) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    return -1;
}

const char* PackedTrie::wordText(uint32_t wordIndex, uint32_t* lengthOut) const {
    if (wordIndex >= wordCount_ || wordOffsets_ == nullptr) {
        return nullptr;
    }
    const uint32_t begin = wordOffsets_[wordIndex];
    const uint32_t end = wordOffsets_[wordIndex + 1];
    // bind() proved the table is monotonic and bounded, so this cannot fail; the test stays
    // because "cannot fail" and "is checked" are different claims about a file we did not write.
    if (end < begin || end > wordTextBytes_) {
        return nullptr;
    }
    *lengthOut = end - begin;
    return wordText_ + begin;
}

int32_t PackedTrie::lookupFolded(const uint32_t* folded, int count) const {
    if (baseArray_ == nullptr || folded == nullptr || count < 0) {
        return -1;
    }
    int32_t node = root();
    for (int i = 0; i < count; ++i) {
        const int symbol = symbolFor(folded[i]);
        if (symbol <= 0) {
            return -1;
        }
        node = walk(node, symbol);
        if (node < 0) {
            return -1;
        }
    }
    return terminalWordIndex(node);
}

}  // namespace borderkeys
