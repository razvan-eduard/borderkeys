// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include "engine.hpp"
#include "proximity.hpp"
#include "test_support.hpp"
#include "topk.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

struct LoadedEngine {
    Engine engine;
    TestLayout layout;

    bool open() {
        if (!engine.create()) {
            return false;
        }
        struct stat info {};
        if (stat(BORDERKEYS_TEST_PACK, &info) != 0) {
            return false;
        }
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        if (fd < 0) {
            return false;
        }
        const int32_t status = engine.loadLanguage("ro-RO", fd, 0, info.st_size, 1.0f);
        ::close(fd);
        if (status != kBkdOk) {
            return false;
        }
        const char* tags[1] = {"ro-RO"};
        const float weights[1] = {1.0f};
        engine.setActiveLanguages(tags, weights, 1);
        return engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count,
                                     layout.keyWidth, layout.keyHeight);
    }

    /** The rank of `expected` among the suggestions for `composing`, or -1. */
    int rankOf(const char* composing, const char* expected, const char* previous = nullptr) {
        Candidate out[Engine::kMaxCandidates];
        const int found = engine.suggest(composing, std::strlen(composing), previous,
                                         previous != nullptr ? std::strlen(previous) : 0, nullptr,
                                         0, out, Engine::kMaxCandidates);
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(out[i], &length);
            if (text != nullptr && length == std::strlen(expected) &&
                std::memcmp(text, expected, length) == 0) {
                return i;
            }
        }
        return -1;
    }
};

}  // namespace

void runEngineTests() {
    section("top-K heap");
    {
        struct Item {
            float score;
        };
        Item storage[4];
        TopK<Item> heap;
        heap.reset(storage, 4);
        const float scores[] = {3.f, 1.f, 4.f, 1.f, 5.f, 9.f, 2.f, 6.f};
        for (float score : scores) {
            heap.offer(Item{score});
        }
        Item drained[4];
        const int count = heap.drainSorted(drained, 4);
        check(count == 4, "a heap of capacity four keeps four items out of eight");
        check(drained[0].score == 9.f && drained[1].score == 6.f && drained[2].score == 5.f &&
                  drained[3].score == 4.f,
              "and drains them in descending order");

        heap.reset(storage, 4);
        check(heap.worstScore() < -1e30f, "an empty heap accepts anything");
        heap.offer(Item{1.f});
        heap.offer(Item{2.f});
        heap.offer(Item{3.f});
        heap.offer(Item{4.f});
        checkNear(heap.worstScore(), 1.f, 0.001f, "a full heap reports its floor");
    }

    section("character folding");
    {
        check(foldCodePoint('A') == 'a', "uppercase ASCII folds to lowercase");
        check(foldCodePoint(0x219) == 's', "s-comma folds to s");
        check(foldCodePoint(0x15F) == 's', "s-cedilla folds to s as well");
        check(foldCodePoint(0x21B) == 't', "t-comma folds to t");
        check(foldCodePoint(0x163) == 't', "t-cedilla folds to t");
        check(foldCodePoint(0x103) == 'a', "a-breve folds to a");
        check(foldCodePoint(0xE2) == 'a', "a-circumflex folds to a");
        check(foldCodePoint(0xEE) == 'i', "i-circumflex folds to i");
        check(foldCodePoint(0x4E2D) == 0x4E2Du, "a script we do not understand is left alone");
    }

    section("UTF-8 decoding refuses what it should");
    {
        uint32_t codePoint = 0;
        const char overlong[] = {static_cast<char>(0xC0), static_cast<char>(0x80)};
        check(utf8Decode(overlong, overlong + 2, &codePoint) == nullptr,
              "an overlong encoding is rejected, not normalised");
        const char surrogate[] = {static_cast<char>(0xED), static_cast<char>(0xA0),
                                  static_cast<char>(0x80)};
        check(utf8Decode(surrogate, surrogate + 3, &codePoint) == nullptr,
              "a surrogate is rejected");
        const char truncated[] = {static_cast<char>(0xE2), static_cast<char>(0x82)};
        check(utf8Decode(truncated, truncated + 2, &codePoint) == nullptr,
              "a truncated sequence is rejected");
        const char valid[] = {static_cast<char>(0xC8), static_cast<char>(0x99)};
        check(utf8Decode(valid, valid + 2, &codePoint) != nullptr && codePoint == 0x219,
              "a valid two-byte sequence decodes");
    }

    section("key geometry");
    {
        TestLayout layout;
        KeyGeometry geometry;
        check(geometry.set(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                           layout.keyHeight),
              "geometry is accepted");
        checkNear(geometry.substitutionCost('a', 'a'), 0.f, 0.001f, "a key costs nothing itself");
        const float neighbour = geometry.substitutionCost('a', 's');
        const float distant = geometry.substitutionCost('a', 'p');
        check(neighbour < distant, "a neighbouring key costs less than a distant one");
        checkNear(neighbour, 1.f, 0.05f, "and adjacent keys are about one key width apart");

        const uint32_t* codes = nullptr;
        const float* costs = nullptr;
        const int count = geometry.neighbours('g', &codes, &costs);
        check(count > 1 && codes[0] == 'g' && costs[0] == 0.f,
              "the neighbour ring starts with the key itself at zero cost");
        bool sorted = true;
        for (int i = 2; i < count; ++i) {
            sorted = sorted && costs[i] >= costs[i - 1];
        }
        check(sorted, "and is ordered by cost");

        check(!geometry.set(layout.codes, layout.xs, layout.ys, layout.count, 0.f, 160.f),
              "a zero key width is refused rather than producing infinite distances");
    }

    section("suggestions");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");

        check(loaded.rankOf("the", "the") == 0, "an exact word is the top suggestion");
        check(loaded.rankOf("mas", "mașina") >= 0, "a prefix reaches a word with diacritics");
        check(loaded.rankOf("masina", "mașina") == 0,
              "typing without diacritics finds the accented spelling");
        check(loaded.rankOf("tara", "țară") >= 0, "and does so for t-comma as well");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0,
              "a neighbouring-key slip is corrected using the pushed-down geometry");
        check(loaded.rankOf("zzzqqq", "the") < 0, "nonsense does not produce a top word");

        Candidate out[Engine::kMaxCandidates];
        const int empty = loaded.engine.suggest("", 0, "the", 3, nullptr, 0, out,
                                                Engine::kMaxCandidates);
        check(empty > 0, "an empty prefix still predicts a next word from the context");

        // Duplicates are what a user sees first when the search reaches one word by two paths.
        const int found = loaded.engine.suggest("the", 3, nullptr, 0, nullptr, 0, out,
                                                Engine::kMaxCandidates);
        bool duplicate = false;
        for (int i = 0; i < found; ++i) {
            uint32_t lengthI = 0;
            const char* textI = loaded.engine.candidateText(out[i], &lengthI);
            for (int j = i + 1; j < found; ++j) {
                uint32_t lengthJ = 0;
                const char* textJ = loaded.engine.candidateText(out[j], &lengthJ);
                if (textI != nullptr && textJ != nullptr && lengthI == lengthJ &&
                    std::memcmp(textI, textJ, lengthI) == 0) {
                    duplicate = true;
                }
            }
        }
        check(!duplicate, "no word appears twice in one set of suggestions");
    }

    section("personal dictionary");
    {
        LoadedEngine loaded;
        loaded.open();
        const char* words[2] = {"Razvan", "borderkeys"};
        const size_t lengths[2] = {6, 10};
        const int32_t counts[2] = {12, 40};
        loaded.engine.loadUserWords(words, lengths, counts, 2);
        check(loaded.rankOf("raz", "Razvan") == 0, "a personal word is suggested");
        check(loaded.rankOf("border", "borderkeys") == 0,
              "a word confirmed forty times outranks a rare dictionary word");

        UserModel model;
        model.learn("borders", 7);
        model.learn("borders", 7);
        check(model.countFor("borders", 7) == 2, "learning increments a count");
        check(model.countFor("Borders", 7) == 2, "and the lookup is case folded");
        check(model.countFor("absent", 6) == 0, "an unlearned word has no count");

        const std::string path = std::string(BORDERKEYS_TEST_PACK) + ".user";
        check(model.snapshot(path.c_str()), "the model snapshots");
        UserModel restored;
        check(restored.restore(path.c_str()), "and restores");
        check(restored.countFor("borders", 7) == 2, "with its counts intact");
        ::remove(path.c_str());

        UserModel refused;
        check(!refused.restore("/nonexistent/path/user.bku"), "a missing snapshot is refused");
    }

    section("the engine survives being used after release");
    {
        Engine engine;
        engine.create();
        engine.destroy();
        Candidate out[4];
        // Not a hypothetical: the service is destroyed while a request may already be posted.
        check(engine.suggest("the", 3, nullptr, 0, nullptr, 0, out, 4) == 0,
              "suggesting after destroy returns nothing rather than touching freed memory");
    }
}
