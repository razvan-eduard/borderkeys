// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_BKD_FORMAT_HPP
#define BORDERKEYS_BKD_FORMAT_HPP

#include <cstddef>
#include <cstdint>

// The on-disk language pack format, and the rules for deciding whether a given pile of bytes
// is one.
//
// This header is the security boundary of the whole application. A .bkd file may have been
// handed to us by the user from anywhere, and the code that reads it does pointer arithmetic
// over a memory mapping inside the process that sees every character typed on this device. A
// malformed pack is not "bad suggestions", it is memory corruption in the worst possible
// process. Everything here is therefore written as if the producer were hostile, including the
// packs we generate ourselves -- because at load time we cannot tell the difference.
//
// Layout: a fixed 256-byte header, then section payloads at header-declared offsets. Nothing
// is parsed: the loader validates the header, checks every section against the real file size,
// and reinterprets pointers into the mapping. Opening a pack is O(1) in the dictionary size.
// The one linear pass is the content checksum, which is deliberate and is paid once, off the
// UI thread, at load; see kBkdContentChecked.

namespace borderkeys {

// 'B' 'K' 'D' '1' read as a little-endian u32. Byte order is part of the format: the file is
// little-endian, every Android ABI we build for is little-endian, and a big-endian reader would
// fail the magic rather than silently misread every offset in the header.
inline constexpr uint32_t kBkdMagic = 0x31444B42u;

// Bumped whenever the meaning of any field changes. A pack whose version is not exactly this
// is refused; there is no best-effort interpretation of an unknown layout.
inline constexpr uint32_t kBkdVersion = 1u;

// Caps, checked before a single byte is mapped.
//
// 64 MB is far above any real dictionary (a large one is a few MB) and far below a size that
// could exhaust address space or make the checksum pass noticeable. The word and node caps
// exist so that a header claiming four billion nodes is rejected on the field itself, before
// its value is ever multiplied by a size to compute a section length.
inline constexpr uint64_t kMaxPackBytes = 64ull * 1024ull * 1024ull;
inline constexpr uint32_t kMaxWords = 4000000u;
inline constexpr uint32_t kMaxNodes = 32000000u;
inline constexpr uint32_t kMaxAlphabet = 1024u;
inline constexpr uint32_t kMaxNgramCapacity = 1u << 26;

// Section table. Order is fixed; a section may be empty (length 0) but may not be missing.
enum BkdSectionIndex : uint32_t {
    kSectionAlphabet = 0,     // uint32_t[alphabetCount], sorted folded code points
    kSectionTrieBase,         // int32_t[nodeCount]
    kSectionTrieCheck,        // int32_t[nodeCount]
    kSectionWordOffsets,      // uint32_t[wordCount + 1], prefix offsets into the text blob
    kSectionWordFreq,         // uint8_t[wordCount], quantised unigram log-probability
    kSectionWordText,         // UTF-8, the display forms, diacritics intact
    kSectionBigramKeys,       // uint64_t[bigramCapacity]
    kSectionBigramValues,     // uint8_t[bigramCapacity]
    kSectionTrigramKeys,      // uint32_t[3 * trigramCapacity]
    kSectionTrigramValues,    // uint8_t[trigramCapacity]
    kSectionCount
};

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
    uint32_t bigramCapacity;   // power of two, or zero
    uint32_t trigramCapacity;  // power of two, or zero
    uint32_t logProbScaleQ;    // fixed point: logProb = -quantised / logProbScaleQ

    uint32_t reserved[6];

    BkdSection sections[kSectionCount];
};

static_assert(sizeof(BkdHeader) == 256, "the .bkd header is a fixed 256 bytes");
static_assert(sizeof(BkdSection) == 16, "section descriptors are two 64-bit fields");
static_assert(alignof(BkdHeader) == 8, "header alignment is part of the layout");

// Reasons a pack was refused. Returned across JNI as a plain int; exceptions are disabled and
// error paths never allocate.
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

// CRC-32 (IEEE 802.3, reflected, polynomial 0xEDB88320). Not a cryptographic check and not
// pretending to be one: it catches truncation and corruption. Tampering is what the SHA-256
// recorded at import time in the database is for.
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

// True when `count` elements of `elementSize` bytes fit at `section.offset` inside a mapping of
// `fileBytes`, at the alignment the type needs.
//
// Written with subtraction rather than addition throughout. `offset + length <= fileBytes` is
// the obvious form and it is wrong: both are attacker-controlled 64-bit values and the sum
// wraps, which is precisely how a section that claims to end before the file ends up pointing
// past it.
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

// Validates everything that can be checked from the header alone, plus the section table
// against the real size of the mapped window. Kept inline and self-contained so that the host
// fuzzing target links against exactly the code the device runs.
//
// Order matters. Cheap and absolute checks first (size caps, magic, version), then the header
// checksum, then the section table -- so that a fuzzed input is rejected on the earliest field
// that is wrong rather than after arithmetic has been done on the later ones.
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
    // The declared size must be the size we actually have. Without this every later bound is
    // checked against a number the file chose for itself.
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
    // A trie with no nodes cannot even hold a root, and the walk code indexes node 0 directly.
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

    // Hash capacities are masked with capacity-1, which is only a valid modulo for powers of
    // two. A non-power-of-two here would turn every probe into an out-of-range index.
    const uint32_t bigramCap = header.bigramCapacity;
    const uint32_t trigramCap = header.trigramCapacity;
    if (bigramCap > kMaxNgramCapacity || trigramCap > kMaxNgramCapacity) {
        return kBkdErrCapacity;
    }
    if (bigramCap != 0 && (bigramCap & (bigramCap - 1)) != 0) {
        return kBkdErrCapacity;
    }
    if (trigramCap != 0 && (trigramCap & (trigramCap - 1)) != 0) {
        return kBkdErrCapacity;
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
        {kSectionBigramKeys, sizeof(uint64_t), alignof(uint64_t), bigramCap},
        {kSectionBigramValues, sizeof(uint8_t), alignof(uint8_t), bigramCap},
        {kSectionTrigramKeys, sizeof(uint32_t), alignof(uint32_t),
         static_cast<uint64_t>(trigramCap) * 3u},
        {kSectionTrigramValues, sizeof(uint8_t), alignof(uint8_t), trigramCap},
    };
    for (const Expectation& e : expectations) {
        if (!bkdSectionFits(header.sections[e.index], fileBytes, headerBytes, e.elementSize,
                            e.alignment, e.count)) {
            return kBkdErrSectionBounds;
        }
    }

    // The text blob is the one section whose length is not implied by a count, so it is bounded
    // directly instead.
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
