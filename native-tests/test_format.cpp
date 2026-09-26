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

/** The index of an ASCII word in the pack, or -1. */
int32_t lookupAscii(const LanguagePack& pack, const char* word) {
    uint32_t folded[32];
    size_t length = 0;
    for (; word[length] != '\0' && length < 32; ++length) {
        folded[length] = static_cast<uint32_t>(word[length]);
    }
    return pack.trie().lookupFolded(folded, static_cast<int>(length));
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

    section("proper noun flag");

    {
        // The sample word list build_dict.py's own --selftest builds the test pack from flags
        // exactly one word, "border" (see build_dict.py's sample_proper_nouns) -- so this is a
        // real round-trip through the actual compiler, not a hand-built fixture.
        LanguagePack pack;
        check(openFromBytes(good, &pack) == kBkdOk, "the test pack opens for the proper-noun check");

        const int32_t properIndex = lookupAscii(pack, "border");
        check(properIndex >= 0, "\"border\", the sample pack's flagged proper noun, is found");
        check(properIndex >= 0 && pack.trie().isProperNoun(static_cast<uint32_t>(properIndex)),
              "and its proper-noun bit is set");

        const int32_t plainIndex = lookupAscii(pack, "mare");
        check(plainIndex >= 0, "an ordinary sample word is found");
        check(plainIndex >= 0 && !pack.trie().isProperNoun(static_cast<uint32_t>(plainIndex)),
              "and its proper-noun bit is not set");
    }

    section("the successor index");

    {
        // The sample pairs build_dict.py's --selftest writes: "the" is followed by "time" and
        // "keyboard", and sentences open with "the".
        LanguagePack pack;
        check(openFromBytes(good, &pack) == kBkdOk, "the test pack opens for the successor check");
        check(pack.ngrams().hasBigrams(), "and carries pairs");
        const int32_t the = lookupAscii(pack, "the");
        const int32_t time = lookupAscii(pack, "time");
        const int32_t keyboard = lookupAscii(pack, "keyboard");
        const int32_t mare = lookupAscii(pack, "mare");
        check(the >= 0 && time >= 0 && keyboard >= 0 && mare >= 0, "the sample words are found");
        const NgramModel& ngrams = pack.ngrams();
        check(ngrams.bigram(static_cast<uint32_t>(the), static_cast<uint32_t>(time)) <= 0.0f,
              "a written pair is found");
        check(ngrams.bigram(static_cast<uint32_t>(the), static_cast<uint32_t>(mare)) ==
                  NgramModel::kNoEntry,
              "a pair never written is not");
        check(ngrams.bigram(NgramModel::kSentenceStartContext, static_cast<uint32_t>(the)) <= 0.0f,
              "the sentence start has its own list");
        uint32_t first = 0;
        const uint32_t count = ngrams.successors(static_cast<uint32_t>(the), &first);
        check(count == 2, "\"the\" lists both of its successors");
        bool sorted = true;
        for (uint32_t i = 1; i < count; ++i) {
            sorted = sorted && ngrams.successorWord(first + i - 1) < ngrams.successorWord(first + i);
        }
        check(sorted, "in index order");
        check(ngrams.successors(static_cast<uint32_t>(mare), &first) == 0,
              "a word nothing follows lists nothing");
        check(ngrams.successors(0xFFFFFFF0u, &first) == 0,
              "and a context outside the pack lists nothing");
    }

    {
        // List bounds are file content and are read as claims: an offset past the pairs is
        // clamped, never followed.
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        const uint32_t huge = 0xFFFFFFFFu;
        const size_t at = static_cast<size_t>(header.sections[kSectionSuccessorOffsets].offset);
        std::memcpy(&mutated[at + sizeof(uint32_t)], &huge, sizeof(huge));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdOk,
              "an offset the header cannot see still opens");
        uint32_t first = 0;
        bool bounded = true;
        for (uint32_t list = 0; list <= header.wordCount; ++list) {
            const uint32_t count = pack.ngrams().successors(list, &first);
            bounded = bounded && first <= header.successorCount &&
                      count <= header.successorCount - first;
        }
        check(bounded, "and every list stays inside the pairs the pack holds");
        check(pack.ngrams().bigram(0u, 1u) == NgramModel::kNoEntry ||
                  pack.ngrams().bigram(0u, 1u) <= 0.0f,
              "a lookup through it answers rather than reading past the end");
    }

    {
        // The sample triples build_dict.py's --selftest writes: "the keyboard keys" hangs off
        // the ("the", "keyboard") pair; "key keys test" names a pair the pack never wrote and
        // was dropped at compile time.
        LanguagePack pack;
        check(openFromBytes(good, &pack) == kBkdOk, "the test pack opens for the triple check");
        check(pack.ngrams().hasTrigrams(), "and carries triples");
        const uint32_t the = static_cast<uint32_t>(lookupAscii(pack, "the"));
        const uint32_t keyboard = static_cast<uint32_t>(lookupAscii(pack, "keyboard"));
        const uint32_t keys = static_cast<uint32_t>(lookupAscii(pack, "keys"));
        const uint32_t key = static_cast<uint32_t>(lookupAscii(pack, "key"));
        const uint32_t test = static_cast<uint32_t>(lookupAscii(pack, "test"));
        const uint32_t time = static_cast<uint32_t>(lookupAscii(pack, "time"));
        const NgramModel& ngrams = pack.ngrams();
        check(ngrams.trigram(the, keyboard, keys) <= 0.0f, "a written triple is found");
        check(ngrams.trigram(the, keyboard, time) == NgramModel::kNoEntry,
              "a triple never written is not, though its pair is");
        check(ngrams.trigram(key, keys, test) == NgramModel::kNoEntry,
              "nor is a triple whose pair the pack does not hold");
        check(ngrams.trigram(0xFFFFFFF0u, keyboard, keys) == NgramModel::kNoEntry,
              "and a context outside the pack has no triples");
    }

    {
        // The continuation lists are bounded the same way the successor lists are: an offset
        // past the triples is clamped, never followed.
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        const uint32_t huge = 0xFFFFFFFFu;
        const size_t at = static_cast<size_t>(header.sections[kSectionTrigramOffsets].offset);
        std::memcpy(&mutated[at + sizeof(uint32_t)], &huge, sizeof(huge));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdOk,
              "a continuation offset the header cannot see still opens");
        uint32_t first = 0;
        bool bounded = true;
        for (uint32_t pair = 0; pair < header.successorCount; ++pair) {
            const uint32_t count = pack.ngrams().continuations(pair, &first);
            bounded = bounded && first <= header.trigramCount &&
                      count <= header.trigramCount - first;
        }
        check(bounded, "and every list stays inside the triples the pack holds");
        check(pack.ngrams().continuations(header.successorCount, &first) == 0,
              "a pair position outside the index lists nothing");
    }

    {
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        header.successorCount += 1u;
        std::memcpy(mutated.data(), &header, sizeof(header));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdErrSectionBounds,
              "a pair count larger than its sections is refused");
    }

    section("grammar sections");

    {
        // The test pack is built without a treebank, which is the case every language starts
        // in and the one that must keep working: no tags, no matrix, and a score with no
        // grammar term rather than a refusal.
        LanguagePack pack;
        check(openFromBytes(good, &pack) == kBkdOk, "a pack with no grammar opens");
        check(!pack.hasGrammar(), "and reports that it carries none");
        check(pack.posTag(0) == LanguagePack::kNoPosTag, "every word is untagged");
        check(pack.posTransition(0, 0) == 0, "and the matrix reads as free everywhere");
    }

    {
        // posTagCount is the field every grammar read is bounded by, so a value a byte could
        // not index has to be refused on the field rather than trusted into an array index.
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        header.posTagCount = kMaxPosTags + 1u;
        std::memcpy(mutated.data(), &header, sizeof(header));
        repairChecksums(mutated);
        LanguagePack pack;
        const int32_t status = openFromBytes(mutated, &pack);
        check(status == kBkdErrCounts, "a tag count wider than a byte is refused");
    }

    {
        // A pack claiming tags without a tag section is claiming wordCount bytes that are not
        // there, which the section bounds check is what catches.
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        header.posTagCount = 8u;
        std::memcpy(mutated.data(), &header, sizeof(header));
        repairChecksums(mutated);
        LanguagePack pack;
        const int32_t status = openFromBytes(mutated, &pack);
        check(status == kBkdErrSectionBounds,
              "declaring tags without the sections to hold them is refused");
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
        const uint32_t claimed = 12345;
        std::memcpy(&mutated[offsetof(BkdHeader, trigramCount)], &claimed, sizeof(claimed));
        repairChecksums(mutated);
        LanguagePack pack;
        check(openFromBytes(mutated, &pack) == kBkdErrSectionBounds,
              "a triple count the sections do not hold is refused");
    }
    {
        std::string mutated = good;
        BkdHeader header;
        std::memcpy(&header, mutated.data(), sizeof(header));
        const uint32_t none = 0;
        std::memcpy(&mutated[offsetof(BkdHeader, successorCount)], &none, sizeof(none));
        repairChecksums(mutated);
        LanguagePack pack;
        check(header.trigramCount != 0 && openFromBytes(mutated, &pack) != kBkdOk,
              "triples without pairs are refused");
    }
}
