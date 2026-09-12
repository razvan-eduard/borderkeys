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

    /**
     * Loads the same test pack again under a second tag and activates both.
     *
     * Same content twice rather than a second real pack: the point of the multi-pack tests this
     * enables is exercising the search loop over more than one active slot (visitBudget_ being
     * reset per pack rather than shared across the request, in particular), not testing a second
     * language's own vocabulary.
     */
    bool openSecondPack(const char* secondTag) {
        struct stat info {};
        if (stat(BORDERKEYS_TEST_PACK, &info) != 0) {
            return false;
        }
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        if (fd < 0) {
            return false;
        }
        const int32_t status = engine.loadLanguage(secondTag, fd, 0, info.st_size, 1.0f);
        ::close(fd);
        if (status != kBkdOk) {
            return false;
        }
        const char* tags[2] = {"ro-RO", secondTag};
        const float weights[2] = {1.0f, 1.0f};
        engine.setActiveLanguages(tags, weights, 2);
        return true;
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
        // æ and œ are deliberately NOT folded to a bare 'a'/'o': a French or English word
        // spelled without the ligature is spelled with two letters ("coeur", "aetiology"), not
        // one, and collapsing the ligature to a single vowel merges it with whatever unrelated
        // word already occupies that shorter spelling. Folding "cœur" to 'o' once made it
        // unreachable behind "cour" (a different, more frequent word, "yard") -- an ligature
        // fold needs the two-letter expansion "oe"/"ae" this per-code-point function cannot
        // produce, not a same-length substitute.
        check(foldCodePoint(0xE6) == 0xE6u, "ae-ligature is left alone rather than merged into a");
        check(foldCodePoint(0x153) == 0x153u, "oe-ligature is left alone rather than merged into o");
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

        // Between two corrections -- neither exact -- the closer one wins almost regardless of
        // frequency. "thexx" reaches "thex" at one edit (delete the trailing x) and "the" at
        // two (delete both), and "the" is more than a thousand times more common than "thex" --
        // yet "thex" still has to come first, because two edits is a claim that the user made
        // two mistakes and that should lose to a one-edit reading of the same typing. This is
        // the shape "jicat" reaching "cât" ahead of "jucat" had: not a completion (theme/the
        // above), and not an exact word losing to a correction (also above) -- two different
        // non-exact corrections, ranked by which one is the smaller mistake.
        check(loaded.rankOf("thexx", "thex") == 0,
              "the one-edit correction outranks a much more frequent two-edit one");
        check(loaded.rankOf("thexx", "the") > 0,
              "and the frequent, farther correction is still offered, just not first");

        // Insertion has to be able to reach any character, not just the ones near whatever key
        // comes next -- "kyboard" reaching "keyboard" needs an 'e' inserted before 'y', and 'e'
        // is nowhere near 'y' on this keyboard. Restricting insertion to neighbouring keys meant
        // this was never a candidate at all, the same way "because" was never reachable from
        // "beause" (a 'c' nowhere near the 'a' after it).
        check(loaded.rankOf("kyboard", "keyboard") >= 0,
              "an inserted character reaches a word even when it is not a nearby key");

        // The "acm"/"acum"/"cam" shape: "acum" is one insertion away and by far the most
        // frequent word here, but "cam" is one (cheaper) transposition away and has a small
        // bushy family of its own completions -- camera, campion, camion. At the old, much
        // wider gap between kTransposeCost and kInsertCost, that family alone filled every kept
        // candidate before "acum" was ever considered, the same way "cam" and its own real
        // completions crowded "acum" out of the real Romanian pack entirely. "acum" has to
        // still be found even with a cheaper, bushier alternative sitting right next to it.
        check(loaded.rankOf("acm", "acum") >= 0,
              "a frequent insertion is not crowded out by a bushy, cheaper transposition");

        // "în" and "in" fold to the same key; the dictionary keeps only the more frequent
        // spelling, so "in" reaches "în" at zero cost the same way "masina" reaches "mașina"
        // above. This is what a keyboard-level short-word exemption must not break -- the guard
        // belongs in AutoCorrection.kt, in front of a real suggestion, not in the search itself.
        check(loaded.rankOf("in", "în") == 0,
              "a two-letter word reaches its accented twin like any other folded spelling");

        // One more typo-pattern sweep over an existing word, covering the shapes the cases
        // above don't: a plain deletion, a doubled letter, and a transposition on a word that
        // (unlike "acm"/"cam") has no bushy competing family to get lost behind.
        check(loaded.rankOf("keybord", "keyboard") >= 0,
              "a dropped letter still reaches the word");
        check(loaded.rankOf("keyboarrd", "keyboard") >= 0,
              "a doubled letter still reaches the word");
        check(loaded.rankOf("kyeboard", "keyboard") >= 0,
              "a transposition still reaches the word");

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

    section("more than one active pack");
    {
        // Nothing before this exercised more than one active language pack -- every case above
        // uses exactly one. visitBudget_ (the fuzzy-search node-visit allowance) used to be a
        // single counter shared across every active pack in one request rather than reset per
        // pack, so a pack searched earlier in the loop could exhaust it before a later pack's
        // own fuzzy walk ever ran; that pack's exact matches and frequent-prefix shortlist still
        // worked, so the strip was never empty, just silently missing that pack's corrections.
        // The tiny self-test dictionary is nowhere near large enough to exhaust the budget on
        // its own (that needs a real, much larger pack), so this cannot reproduce the starvation
        // itself -- it instead pins down that activating a second pack changes nothing about
        // what a fuzzy correction the first pack alone already finds, which is what the fix
        // (resetting visitBudget_ inside the per-pack loop rather than once for the request)
        // guarantees regardless of pack size.
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the first pack");
        check(loaded.openSecondPack("en-US"), "and a second pack, same content, different tag");

        check(loaded.rankOf("keyboarf", "keyboard") >= 0,
              "a neighbouring-key slip is still corrected with two packs active");
        check(loaded.rankOf("kyboard", "keyboard") >= 0,
              "an inserted-character correction still reaches its word");
        check(loaded.rankOf("thexx", "thex") == 0,
              "the closer of two corrections still outranks the farther, more frequent one");
        check(loaded.rankOf("theme", "theme") == 0,
              "a correctly spelled word still outranks a frequent correction of it");

        // Duplicated across packs, "keyboard" is now reachable from two active slots with
        // identical text -- offerCandidate's text-based dedup (not (pack,index)) is what this
        // exercises for the first time with a genuine duplicate rather than a same-pack repeat.
        Candidate out[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest("keyboard", 8, nullptr, 0, nullptr, 0, out,
                                                Engine::kMaxCandidates);
        int keyboardCount = 0;
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(out[i], &length);
            if (text != nullptr && length == 8 && std::memcmp(text, "keyboard", 8) == 0) {
                ++keyboardCount;
            }
        }
        check(keyboardCount == 1,
              "the same word reached from two active packs still appears once");

        // Both packs hold the same test vocabulary, so this cannot show two packs disagreeing --
        // what it pins down is that candidateForPack answers for the pack index it was given,
        // not for whichever pack suggest()'s own dominantPack_/strictLanguage_ would have picked,
        // which is the one thing this function exists to do differently from suggest().
        char spelling[64];
        int written = loaded.engine.candidateForPack(0, "keyboarf", 8, spelling, sizeof(spelling));
        check(written == 8 && std::memcmp(spelling, "keyboard", 8) == 0,
              "candidateForPack corrects a typo through pack 0 explicitly");
        written = loaded.engine.candidateForPack(1, "keyboarf", 8, spelling, sizeof(spelling));
        check(written == 8 && std::memcmp(spelling, "keyboard", 8) == 0,
              "and through pack 1 explicitly, independent of which pack suggest() would pick");
        check(loaded.engine.candidateForPack(2, "keyboarf", 8, spelling, sizeof(spelling)) == 0,
              "a pack index with nothing loaded in it answers nothing");
        check(loaded.engine.candidateForPack(0, "keyboarf", 8, spelling, 2) == 0,
              "an output buffer too small to hold the answer is refused rather than truncated");
    }

    section("dominantPack starts undecided");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        check(loaded.engine.dominantPack() == -1,
              "before any word has been observed, no pack is dominant");
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

    section("correction strictness is a bounded multiplier, not an override");
    {
        // kEditPenalty (40) so dominates any realistic frequency gap that even the most lenient
        // end of the range this multiplies (0.5x) still prices a one-edit correction at roughly
        // seventeen log-units -- far past any ratio this test pack, or a real one, can produce.
        // So unlike setLearningSpeed's own test above, this cannot demonstrate a ranking flip:
        // that is by design, the strictness dial is a fine adjustment on top of the calibration,
        // not a way to turn it off. What it can and must verify is what setCorrectionStrictness
        // shares with every other JNI-facing setter here -- that a value crossing from a stored
        // preference cannot be trusted, and an invalid one falls back to sane rather than
        // disabling correction or crashing.
        LoadedEngine lenient;
        lenient.open();
        lenient.engine.setCorrectionStrictness(0.5f);
        check(lenient.rankOf("keyboarf", "keyboard") >= 0,
              "the most lenient setting still corrects a neighbouring-key slip");
        check(lenient.rankOf("theme", "theme") == 0,
              "and still does not displace a correctly spelled word");

        LoadedEngine strict;
        strict.open();
        strict.engine.setCorrectionStrictness(2.0f);
        check(strict.rankOf("keyboarf", "keyboard") >= 0,
              "the strictest setting still corrects the same slip");

        LoadedEngine guardedStrictness;
        guardedStrictness.open();
        guardedStrictness.engine.setCorrectionStrictness(0.0f);
        check(guardedStrictness.rankOf("keyboarf", "keyboard") >= 0,
              "a zero falls back to the default rather than disabling correction");
        guardedStrictness.engine.setCorrectionStrictness(-1.0f);
        check(guardedStrictness.rankOf("keyboarf", "keyboard") >= 0, "and so does a negative one");
        guardedStrictness.engine.setCorrectionStrictness(1000.0f);
        check(guardedStrictness.rankOf("keyboarf", "keyboard") >= 0,
              "and an absurdly large one is clamped rather than pricing every edit at infinity");
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
