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

        // A correction must not displace a word that needed none, however much more frequent
        // the correction is. "the" is a hundred times more frequent than "theme" in the test
        // pack, and deleting two characters to reach it used to cost less than that ratio was
        // worth -- so someone who typed "theme" correctly read "the" at the head of the strip.
        // The same shape in Romanian is "si" beating a correctly typed "stiu".
        check(loaded.rankOf("theme", "theme") == 0,
              "a correctly spelled rare word outranks a frequent correction of it");
        check(loaded.rankOf("theme", "the") > 0,
              "and the frequent correction is still offered, just not first");
        check(loaded.rankOf("timer", "timer") == 0,
              "which holds when the ratio is sixty to one");
        check(loaded.rankOf("masiv", "masiv") == 0,
              "and when the correction would also add a diacritic");

        // The surcharge is charged for correcting, not for completing. A completion costs no
        // edits, so it still competes on frequency alone: this is what a suggestion strip is
        // for, and a rule that put "car" ahead of everything starting with it would break it.
        check(loaded.rankOf("them", "theme") > 0,
              "a completion is still offered above nothing");
        check(loaded.rankOf("mas", "mașina") >= 0,
              "and a prefix still reaches the frequent completion");
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

    section("phrases the user repeats");
    {
        LoadedEngine loaded;
        loaded.open();

        // Writing "vreau sa ma duc la" a few times, the way the service does it: each word is
        // learned with the one before it as context.
        const char* phrase[] = {"vreau", "sa", "ma", "duc", "la"};
        for (int round = 0; round < 4; ++round) {
            const char* previous = nullptr;
            for (const char* word : phrase) {
                loaded.engine.learn(word, std::strlen(word), previous,
                                    previous != nullptr ? std::strlen(previous) : 0, nullptr, 0);
                previous = word;
            }
        }

        // With nothing typed, the word that follows in the phrase is offered. This is the whole
        // point: the sequence comes back without being typed out again.
        check(loaded.rankOf("", "sa", "vreau") == 0, "after \"vreau\" the next word is \"sa\"");
        check(loaded.rankOf("", "ma", "sa") == 0, "after \"sa\" it is \"ma\"");
        check(loaded.rankOf("", "duc", "ma") == 0, "and after \"ma\" it is \"duc\"");

        // And the pair helps a word that is being typed, without being needed for it.
        check(loaded.rankOf("s", "sa", "vreau") >= 0,
              "a started word is still reached with the context");
        check(loaded.rankOf("s", "sa") >= 0, "and without it");

        // A word never written after this one is not invented as a successor.
        check(loaded.rankOf("", "keyboard", "vreau") != 0,
              "an unrelated word does not become a prediction");
    }

    section("a learned chain leads the strip");
    {
        LoadedEngine loaded;
        loaded.open();

        // Two phrases starting from the same word, one written three times and one written
        // once. The repeated one leads: the preference for a personal chain grows with how
        // often it has been written, so a habit outranks an accident.
        //
        // Both words of each phrase are learned, because that is what the service does -- a
        // pair names two words and the model resolves those names against words it holds. A
        // test that learned only the second word would record no pair at all and would then
        // pass or fail for reasons that have nothing to do with what it claims to check.
        const auto write = [&loaded](const char* first, const char* second) {
            loaded.engine.learn(first, std::strlen(first), nullptr, 0, nullptr, 0);
            loaded.engine.learn(second, std::strlen(second), first, std::strlen(first),
                                nullptr, 0);
        };
        for (int round = 0; round < 3; ++round) {
            write("the", "them");
        }
        write("the", "test");

        check(loaded.rankOf("", "them", "the") == 0,
              "the phrase written three times leads");
        check(loaded.rankOf("", "test", "the") > 0,
              "and the one written once is still offered, behind it");

        // The dictionary's own candidates are not thrown away; they sit behind the personal
        // ones rather than being replaced by them.
        check(loaded.rankOf("", "time", "the") > 0,
              "a word the pack predicts is still in the list");
    }

    section("how quickly it learns is a setting");
    {
        // "testing" is in the pack and much rarer than "test", so with the prefix "test" the
        // dictionary leads. Choosing "testing" three times is enough to take the lead only at
        // the impatient setting: the same evidence, believed sooner.
        const auto leaderAfterThreePicks = [](float speed) {
            LoadedEngine loaded;
            loaded.open();
            loaded.engine.setLearningSpeed(speed);
            for (int i = 0; i < 3; ++i) {
                loaded.engine.learn("testing", 7, nullptr, 0, nullptr, 0);
            }
            return loaded.rankOf("test", "testing");
        };

        check(leaderAfterThreePicks(3.0f) == 0,
              "at the immediate setting three picks put the personal word first");
        check(leaderAfterThreePicks(1.0f) > 0,
              "at the default they do not, and the dictionary still leads");
        check(leaderAfterThreePicks(0.35f) > 0, "nor at the cautious one");

        // The setting is a multiplier crossing JNI from a stored preference. A zero would turn
        // personalisation off silently and a negative would invert it, so both become the
        // default rather than being trusted.
        LoadedEngine guarded;
        guarded.open();
        guarded.engine.setLearningSpeed(0.0f);
        guarded.engine.learn("testing", 7, nullptr, 0, nullptr, 0);
        check(guarded.rankOf("test", "testing") >= 0,
              "a zero speed falls back to the default rather than disabling learning");
        guarded.engine.setLearningSpeed(-5.0f);
        check(guarded.rankOf("test", "testing") >= 0, "and so does a negative one");
    }

    section("a word is not its own successor");
    {
        LoadedEngine loaded;
        loaded.open();
        // With no bigram to go on, the next-word list is ordered by raw frequency, so the most
        // frequent word in the pack would otherwise be offered as following itself.
        check(loaded.rankOf("", "the", "the") != 0,
              "\"the\" is not the top prediction after \"the\"");
        check(loaded.rankOf("", "\u0219i", "\u0219i") != 0,
              "nor is the most frequent Romanian word after itself");
    }

    section("two words offered as one suggestion");
    {
        const auto write = [](LoadedEngine& loaded, const char* a, const char* b,
                              const char* c) {
            loaded.engine.learn(a, std::strlen(a), nullptr, 0, nullptr, 0);
            loaded.engine.learn(b, std::strlen(b), a, std::strlen(a), nullptr, 0);
            loaded.engine.learn(c, std::strlen(c), b, std::strlen(b), a, std::strlen(a));
        };

        // Off by default: a keyboard that guesses two words at a time without being asked is
        // the behaviour this project refuses everywhere else.
        LoadedEngine off;
        off.open();
        for (int i = 0; i < 6; ++i) {
            write(off, "the", "test", "keys");
        }
        check(off.rankOf("", "test keys", "the") < 0,
              "no two-word suggestion unless it is switched on");

        LoadedEngine on;
        on.open();
        on.engine.setPhraseSuggestions(true);
        for (int i = 0; i < 6; ++i) {
            write(on, "the", "test", "keys");
        }
        check(on.rankOf("", "test keys", "the") > 0,
              "a phrase written six times is offered");
        check(on.rankOf("", "test", "the") == 0,
              "and never ahead of its own first word, which is still there alone");

        // The second link is held to twice the evidence, so one repetition is not a phrase.
        LoadedEngine once;
        once.open();
        once.engine.setPhraseSuggestions(true);
        write(once, "the", "test", "keys");
        check(once.rankOf("", "test keys", "the") < 0,
              "a phrase written once is not offered");
    }

    section("a phrase is not learned from a single word");
    {
        LoadedEngine loaded;
        loaded.open();
        loaded.engine.learn("vreau", 5, nullptr, 0, nullptr, 0);
        loaded.engine.learn("sa", 2, nullptr, 0, nullptr, 0);
        // Both words are known, but never one after the other.
        check(loaded.rankOf("", "sa", "vreau") != 0,
              "two words learned apart do not make a pair");
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
