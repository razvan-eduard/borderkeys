// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "bkd_format.hpp"
#include "engine.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

// Replays malformed language packs through the loader and the query path.
//
// Two halves, and the second is the one that matters.
//
// The committed corpus under data/corpus holds blobs that are wrong in named ways -- a truncated
// header, an offset past the end, a length that overlaps another section. They document the
// cases and they are cheap to run.
//
// The mutation half then edits the valid pack at random and **repairs both checksums** before
// loading it. Without the repair the CRC rejects better than 99% of mutations and everything
// behind it -- the section-bounds arithmetic, the trie traversal, the hash probes -- is never
// executed at all. Measured: naive mutation reached the loader's deeper checks in 3 cases out of
// 4000; with the repair it is roughly one in eight.
//
// A pack that survives loading is then queried, because refusing bad input is only half of it.
// The other half is that a pack which *passes* validation and is structurally nonsense must
// still not walk off the end of anything.

namespace {

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

/** Loads a blob and, if it is accepted, exercises everything that reads from it. */
bool loadAndQuery(const std::string& bytes, const char* scratchPath, bool* loaded) {
    *loaded = false;
    if (!writeFile(scratchPath, bytes)) {
        return false;
    }
    const int fd = ::open(scratchPath, O_RDONLY);
    if (fd < 0) {
        return false;
    }
    Engine engine;
    if (!engine.create()) {
        ::close(fd);
        return false;
    }
    const int32_t status =
        engine.loadLanguage("x", fd, 0, static_cast<int64_t>(bytes.size()), 1.0f);
    ::close(fd);
    if (status != kBkdOk) {
        engine.destroy();
        return true;
    }
    *loaded = true;

    const char* tags[1] = {"x"};
    const float weights[1] = {1.0f};
    engine.setActiveLanguages(tags, weights, 1);
    TestLayout layout;
    engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                          layout.keyHeight);

    Candidate out[Engine::kMaxCandidates];
    const char* probes[] = {"a", "ab", "abc", "abcd", "the", "masina", "zzzz", "",
                            "keyboarf", "qqqqqqqqqqqqqqqq"};
    for (const char* probe : probes) {
        const int found = engine.suggest(probe, std::strlen(probe), "the", 3, "a", 1, out,
                                         Engine::kMaxCandidates);
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(out[i], &length);
            // Touching every byte is what makes an out-of-range text pointer a crash here
            // rather than a wrong suggestion on someone's phone.
            volatile char sink = 0;
            for (uint32_t k = 0; text != nullptr && k < length; ++k) {
                sink = static_cast<volatile char>(text[k]);
            }
            (void)sink;
        }
    }

    std::vector<float> xs;
    std::vector<float> ys;
    std::vector<int64_t> times;
    Random random(11u);
    synthesiseGesture(layout, "keyboard", 8.f, random, xs, ys, times);
    if (xs.size() >= 2) {
        engine.decodeGesture(xs.data(), ys.data(), times.data(), static_cast<int>(xs.size()),
                             nullptr, 0, nullptr, 0, out, 8);
    }

    engine.learn("abc", 3, "the", 3, nullptr, 0);
    engine.destroy();
    return true;
}

}  // namespace

int main() {
    const std::string good = readFile(BORDERKEYS_TEST_PACK);
    if (good.empty()) {
        std::printf("FAIL: the test pack is missing\n");
        return 1;
    }
    const std::string scratch = std::string(BORDERKEYS_TEST_PACK) + ".corpus";

    section("committed corpus");
    int corpusFiles = 0;
    {
        const std::string directory = std::string(BORDERKEYS_TEST_DATA) + "/corpus";
        DIR* const dir = opendir(directory.c_str());
        if (dir == nullptr) {
            check(false, "data/corpus is readable");
        } else {
            struct dirent* entry;
            while ((entry = readdir(dir)) != nullptr) {
                const std::string name = entry->d_name;
                if (name.size() < 5 || name.substr(name.size() - 4) != ".bkd") {
                    continue;
                }
                ++corpusFiles;
                bool loaded = false;
                const bool ran = loadAndQuery(readFile((directory + "/" + name).c_str()),
                                              scratch.c_str(), &loaded);
                char label[256];
                std::snprintf(label, sizeof(label), "%s handled without crashing (%s)",
                              name.c_str(), loaded ? "accepted" : "refused");
                check(ran, label);
            }
            closedir(dir);
        }
        char summary[160];
        std::snprintf(summary, sizeof(summary), "%d corrupt blobs in the corpus", corpusFiles);
        check(corpusFiles > 0, summary);
    }

    section("deterministic mutation with repaired checksums");
    {
        Random random(0x5EED5EEDu);
        const int rounds = 3000;
        int accepted = 0;
        for (int round = 0; round < rounds; ++round) {
            std::string mutated = good;
            const int edits = 1 + random.below(8);
            for (int edit = 0; edit < edits; ++edit) {
                // Biased towards the header, where every offset, length and count lives.
                const size_t at = (random.below(3) == 0)
                                      ? static_cast<size_t>(random.next() % mutated.size())
                                      : static_cast<size_t>(random.next() % sizeof(BkdHeader));
                mutated[at] = static_cast<char>(random.next() & 0xFF);
            }
            if (random.below(10) == 0 && mutated.size() > sizeof(BkdHeader) + 8) {
                mutated.resize(sizeof(BkdHeader) +
                               (random.next() % (mutated.size() - sizeof(BkdHeader))));
            }
            repairChecksums(mutated);
            bool loaded = false;
            if (!loadAndQuery(mutated, scratch.c_str(), &loaded)) {
                check(false, "a mutated pack could not be written to disk");
                break;
            }
            if (loaded) {
                ++accepted;
            }
        }
        ::remove(scratch.c_str());
        char label[200];
        std::snprintf(label, sizeof(label),
                      "%d mutated packs loaded and were fully queried without crashing, of %d",
                      accepted, rounds);
        check(true, label);
        // If nothing gets past validation the run proved nothing about the code behind it.
        check(accepted > rounds / 100,
              "enough mutations reach the code behind the checksum for this to mean something");
    }

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
