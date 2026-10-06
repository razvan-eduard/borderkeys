// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_BKD_FORMAT_HPP
#define BORDERKEYS_BKD_FORMAT_HPP

#include <cstddef>
#include <cstdint>

// The on-disk language pack format, and the checks a file must pass to be read as one. Every
// field is treated as untrusted. A fixed 336-byte header is followed by section payloads at
// header-declared offsets, each checked against the real file size and read in place; the content
// checksum is the one linear pass.

namespace borderkeys {

// 'B' 'K' 'D' '1' read as a little-endian u32; the file is little-endian.
inline constexpr uint32_t kBkdMagic = 0x31444B42u;

// The format version; a pack of any other version is refused.
inline constexpr uint32_t kBkdVersion = 6u;

// Caps, checked before a byte is mapped and before any count is multiplied by a size.
inline constexpr uint64_t kMaxPackBytes = 64ull * 1024ull * 1024ull;
inline constexpr uint32_t kMaxWords = 4000000u;
inline constexpr uint32_t kMaxNodes = 32000000u;
inline constexpr uint32_t kMaxAlphabet = 1024u;
inline constexpr uint32_t kMaxNgramCapacity = 1u << 26;

// The most part-of-speech tags, one byte each.
inline constexpr uint32_t kMaxPosTags = 256u;

// Section table. Order is fixed; a section may be empty (length 0) but may not be missing.
enum BkdSectionIndex : uint32_t {
    kSectionAlphabet = 0,     // uint32_t[alphabetCount], sorted folded code points
    kSectionTrieBase,         // int32_t[nodeCount]
    kSectionTrieCheck,        // int32_t[nodeCount]
    kSectionWordOffsets,      // uint32_t[wordCount + 1], prefix offsets into the text blob
    kSectionWordFreq,         // uint8_t[wordCount], quantised unigram log-probability
    kSectionWordText,         // UTF-8, the display forms, diacritics intact
    kSectionSuccessorOffsets, // uint32_t[wordCount + 2], list starts; list wordCount is the
                              // sentence start, the last entry is successorCount
    kSectionSuccessorWords,   // uint32_t[successorCount], sorted within each list
    kSectionSuccessorValues,  // uint8_t[successorCount], quantised -log P(word | context)
    kSectionTrigramOffsets,   // uint32_t[successorCount + 1], list starts by pair position; the
                              // last entry is trigramCount
    kSectionTrigramValues,    // uint8_t[trigramCount], quantised -log P(word | pair)
    kSectionWordTags,         // uint8_t[wordCount], part-of-speech tag index per word
    kSectionPosTransitions,   // uint8_t[posTagCount * posTagCount], quantised -log P(t|prev)
    kSectionWordFlags,        // uint8_t[wordCount], kWordFlag* bits per word
    kSectionWordRun,          // uint8_t[wordCount], spellings sharing this word's folded key
    kSectionTrigramWords,     // uint32_t[trigramCount], sorted within each list
    kSectionCount
};

// Bits in a kSectionWordFlags byte.
inline constexpr uint8_t kWordFlagProperNoun = 1u << 0;  // always capitalise, regardless of
                                                          // typed case or shift state
inline constexpr uint8_t kWordFlagKnownOnly = 1u << 1;   // a spelling the pack knows and never
                                                          // offers

struct BkdSection {
    uint64_t offset;
    uint64_t length;
};

// Flag bits in BkdHeader::flags.
inline constexpr uint32_t kBkdFlagCaseFolded = 1u << 0;   // trie is indexed on folded forms
inline constexpr uint32_t kBkdFlagContentCrc = 1u << 1;   // contentCrc32 is meaningful

struct BkdHeader {
    uint32_t magic;
    uint32_t formatVersion;
    uint32_t headerBytes;      // must equal sizeof(BkdHeader)
    uint32_t flags;

    uint64_t fileBytes;        // must equal the real size of the mapped window

    uint32_t contentCrc32;     // CRC-32 over [headerBytes, fileBytes)
    uint32_t headerCrc32;      // CRC-32 over the header with this field taken as zero

    char languageTag[16];      // BCP-47, NUL padded, NUL terminated

    uint32_t wordCount;
    uint32_t nodeCount;
    uint32_t alphabetCount;
    uint32_t successorCount;   // pairs in the successor index, or zero
    uint32_t trigramCount;     // triples in the continuation index, or zero
    uint32_t logProbScaleQ;    // fixed point: logProb = -quantised / logProbScaleQ

    // Rows and columns of the transition matrix, and the exclusive bound on a word's tag index;
    // zero for a pack without grammar, whose two grammar sections are then empty.
    uint32_t posTagCount;

    uint32_t reserved[1];

    BkdSection sections[kSectionCount];
};

static_assert(sizeof(BkdHeader) == 336, "the .bkd header is a fixed 336 bytes");
static_assert(sizeof(BkdSection) == 16, "section descriptors are two 64-bit fields");
static_assert(alignof(BkdHeader) == 8, "header alignment is part of the layout");

// Reasons a pack was refused, returned across JNI as a plain int.
enum BkdStatus : int32_t {
    kBkdOk = 0,
    kBkdErrTooLarge = -1,
    kBkdErrTooSmall = -2,
    kBkdErrMagic = -3,
    kBkdErrVersion = -4,
    kBkdErrHeaderSize = -5,
    kBkdErrFileSize = -6,
    kBkdErrHeaderCrc = -7,
    kBkdErrContentCrc = -8,
    kBkdErrSectionBounds = -9,
    kBkdErrSectionAlign = -10,
    kBkdErrSectionSize = -11,
    kBkdErrCounts = -12,
    kBkdErrCapacity = -13,
    kBkdErrMmap = -14,
    kBkdErrNoSlot = -15,
    kBkdErrArgument = -16,
};

// CRC-32 (IEEE 802.3, reflected, polynomial 0xEDB88320).
inline uint32_t crc32Update(uint32_t crc, const void* data, size_t length) {
    static const uint32_t* const table = [] {
        static uint32_t generated[256];
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t value = i;
            for (int bit = 0; bit < 8; ++bit) {
                value = (value & 1u) ? (0xEDB88320u ^ (value >> 1)) : (value >> 1);
            }
            generated[i] = value;
        }
        return generated;
    }();

    const uint8_t* p = static_cast<const uint8_t*>(data);
    crc = ~crc;
    for (size_t i = 0; i < length; ++i) {
        crc = table[(crc ^ p[i]) & 0xFFu] ^ (crc >> 8);
    }
    return ~crc;
}

inline uint32_t crc32(const void* data, size_t length) {
    return crc32Update(0u, data, length);
}

// True when `expectedCount` elements of `elementSize` bytes fit at `section.offset` inside a
// mapping of `fileBytes`, at the type's alignment. Checked by subtraction, without overflow.
inline bool bkdSectionFits(const BkdSection& section,
                           uint64_t fileBytes,
                           uint64_t headerBytes,
                           size_t elementSize,
                           size_t alignment,
                           uint64_t expectedCount) {
    if (expectedCount == 0) {
        return section.length == 0;
    }
    if (section.offset < headerBytes || section.offset > fileBytes) {
        return false;
    }
    if (section.offset % alignment != 0) {
        return false;
    }
    if (elementSize != 0 && expectedCount > (UINT64_MAX / elementSize)) {
        return false;
    }
    const uint64_t needed = expectedCount * elementSize;
    if (section.length != needed) {
        return false;
    }
    return section.length <= fileBytes - section.offset;
}

// Validates the header, and its section table against the real size of the mapped window: size
// caps, magic and version first, then the header checksum, the counts and the sections.
inline int32_t bkdValidateHeader(const BkdHeader& header, uint64_t mappedBytes) {
    if (mappedBytes < sizeof(BkdHeader)) {
        return kBkdErrTooSmall;
    }
    if (mappedBytes > kMaxPackBytes) {
        return kBkdErrTooLarge;
    }
    if (header.magic != kBkdMagic) {
        return kBkdErrMagic;
    }
    if (header.formatVersion != kBkdVersion) {
        return kBkdErrVersion;
    }
    if (header.headerBytes != sizeof(BkdHeader)) {
        return kBkdErrHeaderSize;
    }
    // The declared size must be the mapped size.
    if (header.fileBytes != mappedBytes) {
        return kBkdErrFileSize;
    }

    {
        BkdHeader copy = header;
        copy.headerCrc32 = 0u;
        if (crc32(&copy, sizeof(copy)) != header.headerCrc32) {
            return kBkdErrHeaderCrc;
        }
    }

    if (header.wordCount > kMaxWords || header.nodeCount > kMaxNodes ||
        header.alphabetCount == 0 || header.alphabetCount > kMaxAlphabet) {
        return kBkdErrCounts;
    }
    // The trie needs at least its root.
    if (header.nodeCount < 1) {
        return kBkdErrCounts;
    }
    if (header.logProbScaleQ == 0u) {
        return kBkdErrCounts;
    }
    // The language tag is read as a C string by everything downstream.
    if (header.languageTag[sizeof(header.languageTag) - 1] != '\0') {
        return kBkdErrCounts;
    }

    // Both counts are bounded, and triples need pairs.
    const uint32_t successorCount = header.successorCount;
    const uint32_t trigramCount = header.trigramCount;
    if (successorCount > kMaxNgramCapacity || trigramCount > kMaxNgramCapacity) {
        return kBkdErrCapacity;
    }
    if (trigramCount != 0 && successorCount == 0) {
        return kBkdErrCounts;
    }
    // At most kMaxPosTags tags; zero for a pack without grammar.
    if (header.posTagCount > kMaxPosTags) {
        return kBkdErrCounts;
    }

    const uint64_t headerBytes = header.headerBytes;
    const uint64_t fileBytes = header.fileBytes;

    struct Expectation {
        BkdSectionIndex index;
        size_t elementSize;
        size_t alignment;
        uint64_t count;
    };
    const Expectation expectations[] = {
        {kSectionAlphabet, sizeof(uint32_t), alignof(uint32_t), header.alphabetCount},
        {kSectionTrieBase, sizeof(int32_t), alignof(int32_t), header.nodeCount},
        {kSectionTrieCheck, sizeof(int32_t), alignof(int32_t), header.nodeCount},
        {kSectionWordOffsets, sizeof(uint32_t), alignof(uint32_t),
         header.wordCount == 0 ? 0u : static_cast<uint64_t>(header.wordCount) + 1u},
        {kSectionWordFreq, sizeof(uint8_t), alignof(uint8_t), header.wordCount},
        {kSectionWordFlags, sizeof(uint8_t), alignof(uint8_t), header.wordCount},
        {kSectionWordRun, sizeof(uint8_t), alignof(uint8_t), header.wordCount},
        {kSectionSuccessorOffsets, sizeof(uint32_t), alignof(uint32_t),
         successorCount == 0 ? 0u : static_cast<uint64_t>(header.wordCount) + 2u},
        {kSectionSuccessorWords, sizeof(uint32_t), alignof(uint32_t), successorCount},
        {kSectionSuccessorValues, sizeof(uint8_t), alignof(uint8_t), successorCount},
        {kSectionTrigramOffsets, sizeof(uint32_t), alignof(uint32_t),
         trigramCount == 0 ? 0u : static_cast<uint64_t>(successorCount) + 1u},
        {kSectionTrigramWords, sizeof(uint32_t), alignof(uint32_t), trigramCount},
        {kSectionTrigramValues, sizeof(uint8_t), alignof(uint8_t), trigramCount},
        {kSectionWordTags, sizeof(uint8_t), alignof(uint8_t),
         header.posTagCount == 0 ? 0u : header.wordCount},
        {kSectionPosTransitions, sizeof(uint8_t), alignof(uint8_t),
         static_cast<uint64_t>(header.posTagCount) * header.posTagCount},
    };
    for (const Expectation& e : expectations) {
        if (!bkdSectionFits(header.sections[e.index], fileBytes, headerBytes, e.elementSize,
                            e.alignment, e.count)) {
            return kBkdErrSectionBounds;
        }
    }

    // The text blob's length is bounded directly.
    const BkdSection& text = header.sections[kSectionWordText];
    if (header.wordCount == 0) {
        if (text.length != 0) {
            return kBkdErrSectionBounds;
        }
    } else {
        if (text.offset < headerBytes || text.offset > fileBytes) {
            return kBkdErrSectionBounds;
        }
        if (text.length > fileBytes - text.offset) {
            return kBkdErrSectionBounds;
        }
        if (text.length > kMaxPackBytes) {
            return kBkdErrSectionSize;
        }
    }

    return kBkdOk;
}

}  // namespace borderkeys

#endif  // BORDERKEYS_BKD_FORMAT_HPP
