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
