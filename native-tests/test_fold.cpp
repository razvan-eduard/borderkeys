// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "proximity.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

constexpr uint32_t kTableSize = 0x2000;

/**
 * tools/build_dict.py's own fold table, as `--dump-folds --out` wrote it at build time: one
 * `XXXX<TAB>YYYY` line per code point the Python side folds to something other than itself.
 * Everything not listed folds to itself. Returns false when the file is missing or malformed,
 * which is a build problem rather than a fold problem and is reported as its own failure.
 */
bool readPythonFolds(std::vector<uint32_t>& out) {
    const std::string text = readFile(BORDERKEYS_TEST_FOLDS);
    if (text.empty()) {
        return false;
    }
    out.assign(kTableSize, 0u);
    for (uint32_t i = 0; i < kTableSize; ++i) {
        out[i] = i;
    }
    size_t position = 0;
    while (position < text.size()) {
        const size_t lineEnd = text.find('\n', position);
        const std::string line = text.substr(
            position, lineEnd == std::string::npos ? std::string::npos : lineEnd - position);
        position = (lineEnd == std::string::npos) ? text.size() : lineEnd + 1;
        if (line.empty()) {
            continue;
        }
        const size_t tab = line.find('\t');
        if (tab == std::string::npos) {
            return false;
        }
        const unsigned long from = std::strtoul(line.substr(0, tab).c_str(), nullptr, 16);
        const unsigned long to = std::strtoul(line.substr(tab + 1).c_str(), nullptr, 16);
        if (from >= kTableSize) {
            return false;
        }
        out[from] = static_cast<uint32_t>(to);
    }
    return true;
}

/** Whether a Latin Extended-A code point is the capital of its pair -- the fold's own rule,
 *  restated here so the sweep below does not trust the function it is checking. */
bool isLatinExtendedACapital(uint32_t codePoint) {
    if (codePoint == 0x138u || codePoint == 0x149u) {
        return false;  // kra and 'n have no capital
    }
    const bool evenCapital = codePoint <= 0x137u || (codePoint >= 0x14Au && codePoint <= 0x177u);
    const bool oddCapital = (codePoint >= 0x139u && codePoint <= 0x148u) ||
                            (codePoint >= 0x179u && codePoint <= 0x17Eu);
    return (evenCapital && (codePoint & 1u) == 0u) || (oddCapital && (codePoint & 1u) == 1u);
}

}  // namespace

void runFoldTests() {
    section("character folding");
    {
        // The pairs the single-parity fold got wrong: 0x139..0x148 and 0x179..0x17E put the
        // capital on the odd code point, so "ł" used to fold to "Ń" and "Ź" to itself.
        check(foldCodePoint(0x141u) == 'l' && foldCodePoint(0x142u) == 'l', "Ł and ł fold to l");
        check(foldCodePoint(0x2019u) == '\'' && foldCodePoint(0x2018u) == '\'' && foldCodePoint(0x2BCu) == '\'',
              "the typographic apostrophes fold onto the plain one");
        check(sameSpellingIgnoringCase("don\xE2\x80\x99t", 7, "don't", 5), "and the two spellings of don't are one word");
        // Hebrew: the vowel points drop out, the maqaf is the hyphen, the geresh the apostrophe.
        check(foldCodePoint(0x5B8u) == kDroppedCodePoint && foldCodePoint(0x5C1u) == kDroppedCodePoint,
              "a Hebrew vowel point and the shin dot fold to the dropped mark");
        check(foldCodePoint(0x5BEu) == '-' && foldCodePoint(0x5F3u) == '\'',
              "the maqaf is the hyphen and the geresh the apostrophe");
        check(foldCodePoint(0x5E9u) == 0x5E9u && foldCodePoint(0x5DDu) == 0x5DDu,
              "a Hebrew letter and a final letter stay themselves");
        {
            // "שָׁלוֹם" with its points folds to the four letters of "שלום".
            const char* const pointed = "\xD7\xA9\xD6\xB8\xD7\x81\xD7\x9C\xD7\x95\xD6\xB9\xD7\x9D";
            uint32_t folded[16];
            const int count = foldUtf8(pointed, std::strlen(pointed), folded, 16);
            check(count == 4 && folded[0] == 0x5E9u && folded[1] == 0x5DCu && folded[2] == 0x5D5u &&
                      folded[3] == 0x5DDu,
                  "a pointed Hebrew word folds to its letters alone");
        }
        // Arabic: the harakat and the tatweel drop out, the hamza and madda forms fold onto the
        // bare letter, and the Persian letters onto the Arabic ones.
        check(foldCodePoint(0x64Eu) == kDroppedCodePoint && foldCodePoint(0x651u) == kDroppedCodePoint &&
                  foldCodePoint(0x640u) == kDroppedCodePoint,
              "the fatha, the shadda and the tatweel fold to the dropped mark");
        check(foldCodePoint(0x623u) == 0x627u && foldCodePoint(0x625u) == 0x627u &&
                  foldCodePoint(0x622u) == 0x627u && foldCodePoint(0x671u) == 0x627u,
              "every alef with a mark folds onto the bare alef");
        check(foldCodePoint(0x624u) == 0x648u && foldCodePoint(0x626u) == 0x64Au &&
                  foldCodePoint(0x649u) == 0x64Au && foldCodePoint(0x629u) == 0x647u,
              "the hamza forms, the alef maksura and the teh marbuta fold onto their base letters");
        check(foldCodePoint(0x6CCu) == 0x64Au && foldCodePoint(0x6A9u) == 0x643u,
              "the Persian yeh and kaf fold onto the Arabic ones");
        {
            // "مَدْرَسَة" with its harakat folds to the five letters of "مدرسه".
            const char* const vowelled =
                "\xD9\x85\xD9\x8E\xD8\xAF\xD9\x92\xD8\xB1\xD9\x8E\xD8\xB3\xD9\x8E\xD8\xA9";
            uint32_t folded[16];
            const int count = foldUtf8(vowelled, std::strlen(vowelled), folded, 16);
            check(count == 5 && folded[0] == 0x645u && folded[1] == 0x62Fu && folded[2] == 0x631u &&
                      folded[3] == 0x633u && folded[4] == 0x647u,
                  "a vowelled Arabic word folds to its letters alone, the teh marbuta as heh");
        }
        // Greek: case, tonos, dialytika and the final sigma.
        check(foldCodePoint(0x391u) == 0x3B1u && foldCodePoint(0x386u) == 0x3B1u && foldCodePoint(0x3ACu) == 0x3B1u,
              "Α, Ά and ά fold to α");
        check(foldCodePoint(0x3C2u) == 0x3C3u && foldCodePoint(0x3A3u) == 0x3C3u, "ς and Σ fold to σ");
        check(foldCodePoint(0x390u) == 0x3B9u && foldCodePoint(0x3ABu) == 0x3C5u, "ΐ folds to ι and Ϋ to υ");
        check(lowerCodePoint(0x386u) == 0x3ACu, "Ά lowers to ά and keeps its tonos");
        // Cyrillic: the three case rules, the marked е and и, and the short и that stays.
        check(foldCodePoint(0x410u) == 0x430u && foldCodePoint(0x401u) == 0x435u && foldCodePoint(0x451u) == 0x435u,
              "А folds to а, Ё and ё to е");
        check(foldCodePoint(0x419u) == 0x439u && foldCodePoint(0x439u) == 0x439u, "Й and й stay й");
        check(foldCodePoint(0x4C0u) == 0x4CFu && foldCodePoint(0x4C1u) == 0x4C2u && foldCodePoint(0x4D0u) == 0x4D1u,
              "the odd and even capital pairs past 0x460 fold to their lowercase");
        check(foldCodePoint(0x407u) == 0x457u && foldCodePoint(0x490u) == 0x491u, "Ї and Ґ fold to ї and ґ");
        // Armenian and Georgian: case alone.
        check(foldCodePoint(0x531u) == 0x561u && foldCodePoint(0x556u) == 0x586u, "Ա and Ֆ fold to their lowercase");
        check(foldCodePoint(0x1C90u) == 0x10D0u && foldCodePoint(0x10D0u) == 0x10D0u, "Ა folds to ა, which stays");
        check(foldCodePoint(0x139u) == 'l' && foldCodePoint(0x13Au) == 'l', "Ĺ and ĺ fold to l");
        check(foldCodePoint(0x143u) == 'n' && foldCodePoint(0x144u) == 'n', "Ń and ń fold to n");
        check(foldCodePoint(0x147u) == 'n' && foldCodePoint(0x148u) == 'n', "Ň and ň fold to n");
        check(foldCodePoint(0x179u) == 'z' && foldCodePoint(0x17Au) == 'z', "Ź and ź fold to z");
        check(foldCodePoint(0x17Bu) == 'z' && foldCodePoint(0x17Cu) == 'z', "Ż and ż fold to z");
        check(foldCodePoint(0x17Du) == 'z' && foldCodePoint(0x17Eu) == 'z', "Ž and ž fold to z");
        // The pairs the old test happened to get right stay right.
        check(foldCodePoint(0x102u) == 'a' && foldCodePoint(0x103u) == 'a', "Ă and ă fold to a");
        check(foldCodePoint(0x15Eu) == 's' && foldCodePoint(0x15Fu) == 's', "Ş and ş fold to s");
        check(foldCodePoint(0x162u) == 't' && foldCodePoint(0x163u) == 't', "Ţ and ţ fold to t");
        check(foldCodePoint(0x218u) == 's' && foldCodePoint(0x219u) == 's', "Ș and ș fold to s");
        check(foldCodePoint(0x21Au) == 't' && foldCodePoint(0x21Bu) == 't', "Ț and ț fold to t");
        check(foldCodePoint(0xCEu) == 'i' && foldCodePoint(0xEEu) == 'i', "Î and î fold to i");
        check(foldCodePoint(0x178u) == 'y' && foldCodePoint(0xFFu) == 'y', "Ÿ and ÿ fold to y");
        check(foldCodePoint(0x138u) == 'k', "kra, which has no capital, folds to k");
        check(foldCodePoint(0x149u) == 'n', "'n, which has no capital, folds to n");
        check(foldCodePoint(0x14Au) == 'n' && foldCodePoint(0x14Bu) == 'n', "Ŋ and ŋ fold to n");

        // Every capital in the block folds to what its lowercase folds to: a capital that
        // reached the table unfolded was the whole bug, and this catches any pair the checks
        // above did not name.
        bool everyPairAgrees = true;
        for (uint32_t upper = 0x100u; upper <= 0x17Eu; ++upper) {
            if (!isLatinExtendedACapital(upper)) {
                continue;
            }
            if (foldCodePoint(upper) != foldCodePoint(upper + 1u)) {
                std::printf("       U+%04X folds to U+%04X, its lowercase U+%04X to U+%04X\n",
                            upper, foldCodePoint(upper), upper + 1u, foldCodePoint(upper + 1u));
                everyPairAgrees = false;
            }
        }
        check(everyPairAgrees, "every capital in Latin Extended-A folds like its lowercase");
    }

    section("character folding agrees with tools/build_dict.py");
    {
        // The trie is indexed on folded forms, and the pack compiler folds in Python while the
        // engine folds in C++. A disagreement between the two is not a warning, it is a word the
        // engine can never reach -- so the two tables are diffed here, code point by code point,
        // against the dump the Python side wrote when this test binary was built.
        std::vector<uint32_t> python;
        const bool readable = readPythonFolds(python);
        check(readable, "the Python fold table was dumped at build time");
        if (readable) {
            int disagreements = 0;
            for (uint32_t codePoint = 0; codePoint < kTableSize; ++codePoint) {
                const uint32_t native = foldCodePoint(codePoint);
                if (native != python[codePoint]) {
                    if (disagreements < 8) {
                        std::printf("       U+%04X: C++ folds to U+%04X, Python to U+%04X\n",
                                    codePoint, native, python[codePoint]);
                    }
                    ++disagreements;
                }
            }
            check(disagreements == 0, "C++ and Python fold every code point below U+2000 alike");
        }
    }
}
