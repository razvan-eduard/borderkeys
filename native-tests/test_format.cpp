// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>

#include "bkd_format.hpp"
#include "engine.hpp"
#include "packed_trie.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

/** Opens a pack from bytes on disk, returning the status the engine reports. */
int32_t openFromBytes(const std::string& bytes, LanguagePack* pack) {
    const std::string path = std::string(BORDERKEYS_TEST_PACK) + ".case";
    if (!writeFile(path.c_str(), bytes)) {
        return kBkdErrArgument;
    }
    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        return kBkdErrArgument;
    }
    const int32_t status = pack->open("test", fd, 0, static_cast<int64_t>(bytes.size()));
    ::close(fd);
    ::remove(path.c_str());
    return status;
}

/** Rewrites both checksums so a mutated pack is judged on its structure, not on its CRC. */
void repairChecksums(std::string& bytes) {
    if (bytes.size() < sizeof(BkdHeader)) {
        return;
    }
    BkdHeader header;
    std::memcpy(&header, bytes.data(), sizeof(header));
    header.magic = kBkdMagic;
    header.formatVersion = kBkdVersion;
    header.headerBytes = sizeof(BkdHeader);
    header.fileBytes = bytes.size();
    header.contentCrc32 = crc32(bytes.data() + sizeof(BkdHeader),
                                bytes.size() - sizeof(BkdHeader));
    header.headerCrc32 = 0;
    BkdHeader zeroed = header;
    header.headerCrc32 = crc32(&zeroed, sizeof(zeroed));
    std::memcpy(&bytes[0], &header, sizeof(header));
}

}  // namespace

void runFormatTests() {
    section("pack format");

    const std::string good = readFile(BORDERKEYS_TEST_PACK);
    check(!good.empty(), "the test pack was produced by tools/build_dict.py");
    if (good.empty()) {
        return;
    }
    check(good.size() >= sizeof(BkdHeader), "the pack is at least a header long");

    {
        LanguagePack pack;
        check(openFromBytes(good, &pack) == kBkdOk, "a valid pack opens");
        check(pack.isOpen(), "and reports itself open");
        check(std::strcmp(pack.tag(), "test") == 0, "with the tag it was opened under");
        check(pack.trie().wordCount() > 0, "and a non-empty dictionary");
    }

    section("the header is validated field by field");

    struct Case {
        const char* name;
        size_t offset;
        uint8_t xorValue;
        int32_t expected;
    };
    // Each of these targets one field, so a failure names the check that stopped caring rather
    // than "something was wrong somewhere".
    const Case cases[] = {
        {"a wrong magic is refused", 0, 0xFF, kBkdErrMagic},
        {"a wrong format version is refused", 4, 0x09, kBkdErrVersion},
        {"a wrong header size is refused", 8, 0x10, kBkdErrHeaderSize},
        {"a declared size that is not the real size is refused", 16, 0x7F, kBkdErrFileSize},
    };
    for (const Case& testCase : cases) {
        std::string mutated = good;
        mutated[testCase.offset] = static_cast<char>(mutated[testCase.offset] ^ testCase.xorValue);
        LanguagePack pack;
        const int32_t status = openFromBytes(mutated, &pack);
        char label[160];
        std::snprintf(label, sizeof(label), "%s (status %d)", testCase.name, status);
        check(status == testCase.expected, label);
    }

    {
        // The header checksum has to catch a change the other fields would not.
        std::string mutated = good;
        mutated[48] = static_cast<char>(mutated[48] ^ 0x01);  // wordCount
        LanguagePack pack;
        const int32_t status = openFromBytes(mutated, &pack);
        check(status == kBkdErrHeaderCrc, "a header edited without repairing its CRC is refused");
    }

    {
        std::string mutated = good;
        mutated[mutated.size() / 2] = static_cast<char>(mutated[mutated.size() / 2] ^ 0xAA);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdErrContentCrc,
              "a flipped content byte is caught by the content checksum");
    }

    section("truncation");

    for (size_t keep : {size_t(0), size_t(1), size_t(64), size_t(255), good.size() / 2,
                        good.size() - 1}) {
        LanguagePack pack;
        const int32_t status = openFromBytes(good.substr(0, keep), &pack);
        char label[160];
        std::snprintf(label, sizeof(label), "truncated to %zu bytes is refused (status %d)", keep,
                      status);
        check(status != kBkdOk, label);
    }

    section("section bounds, with the checksums repaired");

    // These are the cases the checksum would otherwise hide. Every one edits a section offset or
    // length in the header, repairs both CRCs, and expects the bounds check -- not the CRC -- to
    // be the thing that refuses it.
    const size_t sectionTableOffset = offsetof(BkdHeader, sections);
    struct BoundsCase {
        const char* name;
        int sectionIndex;
        bool editLength;
        uint64_t value;
    };
    const BoundsCase boundsCases[] = {
        {"a section offset past the end of the file", kSectionTrieBase, false, 0xFFFFFFFFull},
        {"a section length larger than the file", kSectionTrieBase, true, 0xFFFFFFFFull},
        {"an offset that would wrap when added to its length", kSectionTrieCheck, false,
         0xFFFFFFFFFFFFFFF0ull},
        {"a section offset inside the header", kSectionAlphabet, false, 8ull},
        {"a misaligned section offset", kSectionTrieBase, false, 0ull},
    };
    for (const BoundsCase& testCase : boundsCases) {
        std::string mutated = good;
        const size_t fieldOffset = sectionTableOffset +
                                   static_cast<size_t>(testCase.sectionIndex) * sizeof(BkdSection) +
                                   (testCase.editLength ? sizeof(uint64_t) : 0);
        uint64_t value = testCase.value;
        if (!testCase.editLength && testCase.value == 0ull) {
            // "Misaligned" means one byte past a legal offset, which needs the real value first.
            std::memcpy(&value, mutated.data() + fieldOffset, sizeof(value));
            value += 1;
        }
        std::memcpy(&mutated[fieldOffset], &value, sizeof(value));
        repairChecksums(mutated);
        LanguagePack pack;
        const int32_t status = openFromBytes(mutated, &pack);
        char label[200];
        std::snprintf(label, sizeof(label), "%s is refused (status %d)", testCase.name, status);
        check(status != kBkdOk, label);
    }

    section("caps");

    {
        std::string mutated = good;
        const uint32_t absurd = kMaxWords + 1;
        std::memcpy(&mutated[offsetof(BkdHeader, wordCount)], &absurd, sizeof(absurd));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdErrCounts,
              "a word count above the format cap is refused on the field, before any arithmetic");
    }
    {
        std::string mutated = good;
        const uint32_t notPowerOfTwo = 12345;
        std::memcpy(&mutated[offsetof(BkdHeader, bigramCapacity)], &notPowerOfTwo,
                    sizeof(notPowerOfTwo));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdErrCapacity,
              "a hash capacity that is not a power of two is refused");
    }
}
