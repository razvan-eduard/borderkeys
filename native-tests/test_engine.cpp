// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstddef>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <initializer_list>
#include <limits>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include "engine.hpp"
#include "reading.hpp"
#include "proximity.hpp"
#include "test_support.hpp"
#include "topk.hpp"
#include "touch_model.hpp"

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

    /** Loads the same test pack again under a second tag and activates both. */
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

    /** Loads the test pack once more under `tag`, returning the engine's own status. */
    int32_t loadUnder(const char* tag) {
        struct stat info {};
        if (stat(BORDERKEYS_TEST_PACK, &info) != 0) {
            return kBkdErrArgument;
        }
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        if (fd < 0) {
            return kBkdErrArgument;
        }
        const int32_t status = engine.loadLanguage(tag, fd, 0, info.st_size, 1.0f);
        ::close(fd);
        return status;
    }

    /**
     * The score `expected` carries among the suggestions for `composing`, or 0 if absent; each
     * letter of the ASCII `composing` tapped at `xs` and `ys` when they are given.
     */
    float scoreOf(const char* composing, const char* expected, const float* xs = nullptr,
                  const float* ys = nullptr) {
        Candidate out[Engine::kMaxCandidates];
        const size_t length = std::strlen(composing);
        const int found = engine.suggest(composing, length, nullptr, 0, nullptr, 0, xs, ys,
                                         xs != nullptr ? static_cast<int>(length) : 0, out,
                                         Engine::kMaxCandidates);
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(out[i], &length);
            if (text != nullptr && length == std::strlen(expected) &&
                std::memcmp(text, expected, length) == 0) {
                return out[i].score;
            }
        }
        return 0.0f;
    }

    /**
     * The suggestions for the ASCII word `composing`, each letter tapped at `xs` and `ys`, or
     * untapped when they are null: best first, each `word:score` to three places, then a bar and
     * autocorrect's word.
     */
    std::string answer(const char* composing, const float* xs, const float* ys) {
        Candidate out[Engine::kMaxCandidates];
        const size_t length = std::strlen(composing);
        const int found = engine.suggest(composing, length, nullptr, 0, nullptr, 0, xs, ys,
                                         xs != nullptr ? static_cast<int>(length) : 0, out,
                                         Engine::kMaxCandidates);
        std::string text;
        for (int i = 0; i < found; ++i) {
            uint32_t wordLength = 0;
            const char* const word = engine.candidateText(out[i], &wordLength);
            char score[24];
            std::snprintf(score, sizeof(score), ":%.3f ", out[i].score);
            text.append(word, wordLength).append(score);
        }
        text.append("| ");
        const Candidate* const best = engine.bestCorrection();
        uint32_t bestLength = 0;
        const char* const bestWord =
            best != nullptr ? engine.candidateText(*best, &bestLength) : nullptr;
        if (bestWord != nullptr) {
            text.append(bestWord, bestLength);
        }
        return text;
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
        // æ and œ are not folded to a single vowel.
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

    section("the touch model");
    {
        TestLayout layout;
        KeyGeometry geometry;
        geometry.set(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                     layout.keyHeight);
        float gx = 0.f;
        float gy = 0.f;
        float hx = 0.f;
        float hy = 0.f;
        layout.centreOf('g', &gx, &gy);
        layout.centreOf('h', &hx, &hy);
        const float geometryCost = geometry.substitutionCost('g', 'h');
        const float towardsH = gx + 0.3f * layout.keyWidth;

        TouchModel defaults;
        check(defaults.substitutionCost(geometry, 'g', 'h', gx, gy, geometryCost) == geometryCost,
              "a tap on the typed key's centre costs the geometry's centre distance");
        checkNear(defaults.substitutionCost(geometry, 'g', 'h', (gx + hx) / 2.f, gy, geometryCost),
                  KeyGeometry::kMinSubstitutionCost, 0.001f,
                  "a tap halfway between two keys costs the least a substitution may");
        const float defaultCost =
            defaults.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost);
        check(defaultCost < defaults.substitutionCost(geometry, 'g', 'h',
                                                      gx - 0.3f * layout.keyWidth, gy,
                                                      geometryCost),
              "a tap towards the intended key costs less than one away from it");
        const float none = std::numeric_limits<float>::quiet_NaN();
        check(defaults.substitutionCost(geometry, 'g', 'h', none, none, geometryCost) ==
                  geometryCost,
              "a tap with no point is priced by the geometry");

        // One learned pattern with fifty taps: h's taps land 0.2 key widths left of its centre,
        // towards g.
        const int32_t hCode = 'h';
        const float fifty = 50.f;
        const float leftOfCentre = -0.2f;
        const float zero = 0.f;
        const float variance = TouchModel::kReferenceSpread * TouchModel::kReferenceSpread;
        TouchModel leaning;
        leaning.set(&hCode, &fifty, &leftOfCentre, &zero, &variance, &variance, &zero, 1);
        leaning.configure(true, 1.f, 30);
        const float leaningCost =
            leaning.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost);
        check(leaningCost < defaultCost,
              "a key whose taps lean towards the typed one is cheaper to reach from that side");
        check(leaningCost >= KeyGeometry::kMinSubstitutionCost,
              "and never cheaper than the least a substitution may cost");

        TouchModel half;
        half.set(&hCode, &fifty, &leftOfCentre, &zero, &variance, &variance, &zero, 1);
        half.configure(true, 0.5f, 30);
        checkNear(half.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost),
                  (defaultCost + leaningCost) / 2.f, 0.001f,
                  "at weight one half a learned pattern moves the cost halfway");

        TouchModel ignored;
        ignored.set(&hCode, &fifty, &leftOfCentre, &zero, &variance, &variance, &zero, 1);
        ignored.configure(false, 1.f, 30);
        check(ignored.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost) ==
                  defaultCost,
              "while learned patterns do not count, the default patterns price every tap");

        TouchModel sparse;
        sparse.set(&hCode, &fifty, &leftOfCentre, &zero, &variance, &variance, &zero, 1);
        sparse.configure(true, 1.f, 100);
        check(sparse.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost) ==
                  defaultCost,
              "a key with fewer taps than the minimum is priced by its default pattern");

        TouchModel unweighted;
        unweighted.set(&hCode, &fifty, &leftOfCentre, &zero, &variance, &variance, &zero, 1);
        unweighted.configure(true, 0.f, 30);
        check(unweighted.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost) ==
                  defaultCost,
              "at weight zero the learned patterns change nothing");

        // g's own taps land 0.3 key widths right of its centre, where this tap is.
        const int32_t gCode = 'g';
        const float rightOfCentre = 0.3f;
        TouchModel typedLeaning;
        typedLeaning.set(&gCode, &fifty, &rightOfCentre, &zero, &variance, &variance, &zero, 1);
        typedLeaning.configure(true, 1.f, 30);
        check(typedLeaning.substitutionCost(geometry, 'g', 'h', towardsH, gy, geometryCost) >
                  defaultCost,
              "a typed key whose taps usually land there is dearer to read as its neighbour");
    }

    section("folding keeps each code point's source");
    {
        // Shin, a qamats that folds to nothing, final mem.
        const char text[] = "\xD7\xA9\xD6\xB8\xD7\x9D";
        uint32_t folded[8];
        int source[8];
        const int length = foldUtf8(text, sizeof(text) - 1, folded, 8, source);
        check(length == 2 && source[0] == 0 && source[1] == 2,
              "a mark that folds to nothing leaves no entry, and the next keeps its own source");
    }

    section("taps at the key centres answer as no taps do");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        const char word[] = "keyboarf";
        const int length = static_cast<int>(sizeof(word) - 1);
        float xs[8];
        float ys[8];
        for (int i = 0; i < length; ++i) {
            loaded.layout.centreOf(word[i], &xs[i], &ys[i]);
        }
        Candidate plain[16];
        Candidate tapped[16];
        const int plainCount = loaded.engine.suggest(word, length, "", 0, "", 0, plain, 16);
        const int tappedCount =
            loaded.engine.suggest(word, length, "", 0, "", 0, xs, ys, length, tapped, 16);
        bool same = plainCount == tappedCount && plainCount > 0;
        for (int i = 0; same && i < plainCount; ++i) {
            same = plain[i].packIndex == tapped[i].packIndex &&
                   plain[i].wordIndex == tapped[i].wordIndex && plain[i].score == tapped[i].score;
        }
        check(same, "the same words at the same scores");
    }

    section("a tapped substitution is priced by where the tap landed");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        // The d of "thede" is beside the s of "these" and diagonally below the r of "there".
        const char word[] = "thede";
        float xs[5];
        float ys[5];
        for (int i = 0; i < 5; ++i) {
            loaded.layout.centreOf(word[i], &xs[i], &ys[i]);
        }
        const std::string untapped = loaded.answer(word, nullptr, nullptr);
        check(untapped.rfind("these:", 0) == 0 && untapped.find("| these") != std::string::npos,
              "untapped, these ranks first and autocorrect takes it");
        const float none = std::numeric_limits<float>::quiet_NaN();
        const float nowhere[5] = {none, none, none, none, none};
        check(loaded.answer(word, nowhere, nowhere) == untapped,
              "taps with no point answer as no taps do");

        // The d tapped at its upper right, towards r.
        const float upX = 0.3f;
        const float upY = -0.45f;
        xs[3] += upX * loaded.layout.keyWidth;
        ys[3] += upY * loaded.layout.keyHeight;
        const std::string towardsR = loaded.answer(word, xs, ys);
        check(towardsR.rfind("there:", 0) == 0 && towardsR.find("| there") != std::string::npos,
              "a d tapped towards r ranks there first, and autocorrect takes it");

        // d's learned taps usually land at that upper right.
        const int32_t dCode = 'd';
        const float fifty = 50.f;
        const float zero = 0.f;
        const float variance = TouchModel::kReferenceSpread * TouchModel::kReferenceSpread;
        loaded.engine.setTouchPatterns(&dCode, &fifty, &upX, &upY, &variance, &variance, &zero,
                                       1);
        const float unlearned = loaded.scoreOf(word, "there", xs, ys);
        loaded.engine.setTouchModel(true, 1.f, 30);
        check(loaded.scoreOf(word, "there", xs, ys) < unlearned,
              "a d whose taps usually land there makes the same tap dearer to read as r");
        loaded.engine.setTouchModel(true, 1.f, 100);
        check(loaded.scoreOf(word, "there", xs, ys) == unlearned,
              "with fewer taps than the minimum, the default patterns price it");
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
        // the correction is.
        check(loaded.rankOf("theme", "theme") == 0,
              "a correctly spelled rare word outranks a frequent correction of it");
        // Reaching it discards two typed characters, which costs 2 x kDeleteCost -- past
        // maxEditCostFor at this length, so it is not reached at all.
        check(loaded.rankOf("theme", "the") < 0,
              "and a correction two deletions away is beyond the ceiling");
        check(loaded.rankOf("timer", "timer") == 0,
              "which holds when the ratio is sixty to one");
        check(loaded.rankOf("masiv", "masiv") == 0,
              "and when the correction would also add a diacritic");

        // Between two corrections, neither exact, the closer one wins almost regardless of
        // frequency: "thexx" reaches "thex" at one edit and "the" at two.
        check(loaded.rankOf("thexx", "thex") == 0,
              "the one-edit correction outranks a much more frequent two-edit one");
        check(loaded.rankOf("thexy", "the") < 0,
              "and a reading two deletions away is beyond the ceiling too");
        // A repeated letter is deleted at kRepeatDeleteCost.
        check(loaded.rankOf("timmer", "timer") == 0,
              "a letter typed twice reads as the word with it once");

        // Insertion reaches any character, not only keys near the next one: "kyboard" needs an
        // 'e' before the 'y'.
        check(loaded.rankOf("kyboard", "keyboard") >= 0,
              "an inserted character reaches a word even when it is not a nearby key");

        // "acum", one insertion away, is found although "cam", one cheaper transposition away,
        // has a family of completions of its own.
        check(loaded.rankOf("acm", "acum") >= 0,
              "a frequent insertion is not crowded out by a bushy, cheaper transposition");

        // "in" reaches "în", which folds to the same key, at zero cost.
        check(loaded.rankOf("in", "în") == 0,
              "a two-letter word reaches its accented twin like any other folded spelling");

        // A plain deletion, a doubled letter, and a transposition on a word with no competing
        // family.
        check(loaded.rankOf("keybord", "keyboard") >= 0,
              "a dropped letter still reaches the word");
        check(loaded.rankOf("keyboarrd", "keyboard") >= 0,
              "a doubled letter still reaches the word");
        check(loaded.rankOf("kyeboard", "keyboard") >= 0,
              "a transposition still reaches the word");

        // The surcharge is charged for correcting, not for completing: a completion competes on
        // frequency alone.
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

    section("a pack no longer named gives its slot back");
    {
        // A pack no longer named in setActiveLanguages is closed and its slot returned.
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the first pack");
        check(loaded.openSecondPack("en-US"), "and a second one under another tag");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0, "both are consulted");

        const char* only[1] = {"ro-RO"};
        const float weight[1] = {1.0f};
        loaded.engine.setActiveLanguages(only, weight, 1);
        check(loaded.rankOf("keyboarf", "keyboard") >= 0, "the one still named keeps answering");

        // Three more distinct tags fit (four slots, one in use); a fifth does not, and says so
        // rather than evicting anything.
        check(loaded.loadUnder("de-DE") == kBkdOk, "the freed slot takes a new tag");
        check(loaded.loadUnder("fr-FR") == kBkdOk, "and a third");
        check(loaded.loadUnder("it-IT") == kBkdOk, "and a fourth");
        check(loaded.loadUnder("es-ES") == kBkdErrNoSlot, "a fifth tag is refused with kBkdErrNoSlot");

        loaded.engine.setActiveLanguages(nullptr, nullptr, 0);
        check(loaded.rankOf("keyboarf", "keyboard") < 0, "an empty set closes every pack");
        check(loaded.loadUnder("es-ES") == kBkdOk, "and every slot is free again");
    }

    section("more than one active pack");
    {
        // A second active pack changes nothing about a fuzzy correction the first finds alone;
        // visitBudget_ is reset per pack.
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

        // "keyboard" is reachable from two active slots with identical text; offerCandidate
        // dedups by text.
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

        // candidateForPack answers for the pack index it was given, not for the pack suggest()
        // would pick.
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

    section("a preferred language decides where an undecided search starts");
    {
        // Both slots hold the same vocabulary; Candidate::packIndex shows which slot the search
        // was restricted to.
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        check(loaded.openSecondPack("en-US"), "a second pack loads under another tag");

        // Every pack a request reached, as a bitmask over slots.
        const auto packsReached = [&loaded](const char* composing) {
            Candidate out[Engine::kMaxCandidates];
            const int found = loaded.engine.suggest(composing, std::strlen(composing), nullptr, 0,
                                                    nullptr, 0, out, Engine::kMaxCandidates);
            int mask = 0;
            for (int i = 0; i < found; ++i) {
                if (out[i].packIndex >= 0) {
                    mask |= 1 << out[i].packIndex;
                }
            }
            return mask;
        };

        check(loaded.engine.dominantPack() == -1, "nothing has been recognised yet");

        // The unrestricted baseline, measured; the assertions below compare against it.
        const int unrestricted = packsReached("keyboar");
        check(unrestricted != 0, "with no preference the search answers from somewhere");

        loaded.engine.setPreferredLanguage("en-US");
        check(packsReached("keyboar") == 0b10,
              "with a preference set and nothing recognised, only that pack answers");

        loaded.engine.setPreferredLanguage("ro-RO");
        check(packsReached("keyboar") == 0b01, "and naming the other one moves the search to it");

        // Turning the setting off restores the unrestricted search.
        loaded.engine.setPreferredLanguage("");
        check(packsReached("keyboar") == unrestricted,
              "cleared, the search is exactly what it was before a preference was ever set");

        loaded.engine.setPreferredLanguage(nullptr);
        check(packsReached("keyboar") == unrestricted,
              "and null clears it the same way empty does");

        // A tag for a language that is not loaded reads as no preference, not as "restrict to
        // nothing".
        loaded.engine.setPreferredLanguage("de-DE");
        check(packsReached("keyboar") == unrestricted,
              "an unknown tag behaves as no preference, not as no dictionary at all");

        // The preference is a tag, so it survives the slots moving.
        loaded.engine.setPreferredLanguage("en-US");
        const char* tags[2] = {"en-US", "ro-RO"};
        const float weights[2] = {1.0f, 1.0f};
        loaded.engine.setActiveLanguages(tags, weights, 2);
        check(packsReached("keyboar") == 0b10,
              "and it still names the same language after the active set is re-sent reordered");

        // Switching the preferred language off entirely leaves a tag naming a pack that is no
        // longer active; that is the same case as an unknown tag and must not empty the strip.
        const char* onlyRo[1] = {"ro-RO"};
        const float oneWeight[1] = {1.0f};
        loaded.engine.setActiveLanguages(onlyRo, oneWeight, 1);
        check(packsReached("keyboar") != 0,
              "a preference naming a pack that has been switched off still suggests something");
    }

    section("a mark the user typed is not discarded to reach a word");
    {
        // A pack word with a mark appended must not produce that word back (kMarkDeleteCost).
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");

        const auto committed = [&loaded](const char* composing) {
            Candidate out[Engine::kMaxCandidates];
            loaded.engine.suggest(composing, std::strlen(composing), nullptr, 0, nullptr, 0, out,
                                  Engine::kMaxCandidates);
            const Candidate* const best = loaded.engine.bestCorrection();
            if (best == nullptr) {
                return std::string();
            }
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(*best, &length);
            return text == nullptr ? std::string() : std::string(text, length);
        };

        check(committed("keyboard'") != "keyboard",
              "a trailing apostrophe is not thrown away to reach the word without it");
        check(committed("keyboard-") != "keyboard",
              "and neither is a trailing hyphen -- the rule is about the class, not the "
              "character that happened to be reported");
        check(committed("'keyboard") != "keyboard",
              "nor a leading one, which is what let the rest of the word be rewritten too");

        // A mark left out is cheap to insert.
        check(loaded.rankOf("keyboar", "keyboard") >= 0,
              "a word one letter short of finished is still reached");
    }

    section("forgetting the language verdict");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        loaded.engine.resetLanguageEvidence();
        check(loaded.engine.dominantPack() == -1, "after a reset no pack is dominant");
        check(loaded.rankOf("keyboar", "keyboard") >= 0,
              "and the engine still answers, so the reset clears evidence rather than state");
    }

    section("sentence openers");
    {
        LoadedEngine loaded;
        loaded.open();
        const char* words[2] = {"hello", "borderkeys"};
        const size_t lengths[2] = {5, 10};
        const int32_t counts[2] = {6, 40};
        loaded.engine.loadUserWords(words, lengths, counts, 2);
        // The pair the keyboard records for a word with nothing before it: the sentence start
        // itself as the context.
        const char* previous[1] = {"\x02start"};
        const size_t previousLengths[1] = {6};
        const char* next[1] = {"hello"};
        const size_t nextLengths[1] = {5};
        const int32_t pairCounts[1] = {5};
        loaded.engine.loadUserBigrams(previous, previousLengths, next, nextLengths, pairCounts, 1);
        auto slotsOf = [&](int* helloAt, int* markerAt) {
            Candidate out[Engine::kMaxCandidates];
            const int found = loaded.engine.suggest("", 0, nullptr, 0, nullptr, 0, out,
                                                    Engine::kMaxCandidates);
            *helloAt = -1;
            *markerAt = -1;
            for (int i = 0; i < found; ++i) {
                uint32_t length = 0;
                const char* const text = loaded.engine.candidateText(out[i], &length);
                if (text == nullptr || length == 0) {
                    continue;
                }
                if (length == 5 && std::memcmp(text, "hello", 5) == 0) {
                    *helloAt = i;
                }
                if (text[0] == '\x02') {
                    *markerAt = i;
                }
            }
        };
        int helloAt = -1;
        int markerAt = -1;
        slotsOf(&helloAt, &markerAt);
        check(helloAt >= 0, "a word this person opens sentences with is offered at a sentence start");
        check(markerAt < 0, "the sentence start itself is never offered as a word");
        loaded.engine.setPersonalModelEnabled(false);
        slotsOf(&helloAt, &markerAt);
        check(helloAt < 0, "and not while the personal model is switched off");
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

        // A pack word the personal dictionary also holds, capitalised: one suggestion, not two
        // that the strip would then show as the same word twice.
        const char* capitalised[1] = {"Keyboard"};
        const size_t capitalisedLength[1] = {8};
        const int32_t capitalisedCount[1] = {40};
        loaded.engine.loadUserWords(capitalised, capitalisedLength, capitalisedCount, 1);
        Candidate offered[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest("keyboa", 6, nullptr, 0, nullptr, 0, offered,
                                                Engine::kMaxCandidates);
        int copies = 0;
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(offered[i], &length);
            if (text != nullptr && sameSpellingIgnoringCase(text, length, "keyboard", 8)) {
                ++copies;
            }
        }
        check(copies == 1, "a word held by a pack and, capitalised, by the personal dictionary is offered once");

        UserModel model;
        model.learn("borders", 7);
        model.learn("borders", 7);
        check(model.countFor("borders", 7) == 2, "learning increments a count");
        check(model.countFor("Borders", 7) == 2, "and the lookup is case folded");
        check(model.countFor("absent", 6) == 0, "an unlearned word has no count");
    }

    section("a name missing its apostrophe");
    {
        // The three conditions, checked as properties rather than against particular words: the
        // fixture pack is small and its name rows are whatever build_dict's selftest produced.
        LoadedEngine loaded;
        loaded.open();
        char out[64];

        check(loaded.engine.possessiveFor("car", 3, out, sizeof(out)) == 0,
              "a word not ending in s is never a possessive");
        check(loaded.engine.possessiveFor("as", 2, out, sizeof(out)) == 0,
              "and neither is something too short to have a stem");

        // The middle condition, and the one that carries the safety. Any word the dictionaries
        // hold means itself: "times" is not "time's".
        Candidate probe[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest("", 0, nullptr, 0, nullptr, 0, probe,
                                                Engine::kMaxCandidates);
        for (int i = 0; i < found && i < 4; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(probe[i], &length);
            if (text == nullptr || length == 0 || text[length - 1] != 's') {
                continue;
            }
            check(loaded.engine.possessiveFor(text, length, out, sizeof(out)) == 0,
                  "a word the dictionaries hold is left alone however it ends");
        }
    }

    section("each reading is what its cost and depth say");
    {
        // readingOf is pure, so this needs no pack: the classification is the hierarchy, and
        // these are the five cases the routing distinguishes.
        check(readingOf(0.0f, 0, "car", 3) == Reading::Exact, "no edit, no depth, no mark");
        check(readingOf(0.0f, 0, "can\u0103", 5) == Reading::Respelling, "a mark the fold drops");
        check(readingOf(0.0f, 1, "cars", 4) == Reading::ShortCompletion, "one character past");
        check(readingOf(0.0f, 2, "carts", 5) == Reading::LongCompletion, "two characters past");
        check(readingOf(0.8f, 0, "the", 3) == Reading::Correction, "reached by an edit");

        // Depth outranks the mark: a word carrying a diacritic is still a completion when it
        // runs past the letters typed.
        check(readingOf(0.0f, 1, "can\u0103", 5) == Reading::ShortCompletion,
              "a mark does not make a completion a respelling");
        // Cost outranks both.
        check(readingOf(0.8f, 1, "can\u0103", 5) == Reading::Correction,
              "an edit outranks depth and marks alike");

        check(reachesCorrectionHeap(Reading::Correction), "corrections may be committed");
        check(reachesCorrectionHeap(Reading::ShortCompletion), "and so may a short completion");
        check(!reachesCorrectionHeap(Reading::LongCompletion), "a long completion may not");
        check(!reachesCorrectionHeap(Reading::Exact), "nor the word already typed");
        check(takesRespellingTier(Reading::Respelling), "a respelling takes its own tier");
        check(!takesRespellingTier(Reading::Exact), "and nothing else does");
    }

    section("autocorrect asks its own question");
    {
        // Whatever bestCorrection returns carries the typed letters on by at most
        // kMaxCorrectionCompletion characters.
        LoadedEngine loaded;
        loaded.open();

        Candidate out[Engine::kMaxCandidates];
        loaded.engine.suggest("car", 3, nullptr, 0, nullptr, 0, out, Engine::kMaxCandidates);
        const Candidate* const best = loaded.engine.bestCorrection();
        if (best != nullptr) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(*best, &length);
            check(text != nullptr && length > 0, "a correction resolves to text");
            const bool continues = length > 3 + kMaxCorrectionCompletion &&
                                   std::memcmp(text, "car", 3) == 0;
            check(!continues, "and it carries the typed letters on no further than the cap");
        }

        // A word the dictionaries do not know at all has nothing to correct towards, and the
        // heap must come back empty rather than reaching for whatever it can find.
        loaded.engine.suggest("zzqx", 4, nullptr, 0, nullptr, 0, out, Engine::kMaxCandidates);
        check(loaded.engine.bestCorrection() == nullptr ||
                  loaded.engine.candidateText(*loaded.engine.bestCorrection(), nullptr) != nullptr,
              "an unrecognisable word yields nothing, or something that resolves");

        // Cleared per request, never carried from one word into the next.
        loaded.engine.suggest("", 0, nullptr, 0, nullptr, 0, out, Engine::kMaxCandidates);
        check(loaded.engine.bestCorrection() == nullptr,
              "nothing typed means nothing to correct");
    }

    section("a word written once or twice is kept but not offered");
    {
        // A personal word is anchored at a fixed value that beats any rarer dictionary word,
        // so it reaches the strip only once established: three effective uses, or one choice.
        LoadedEngine loaded;
        loaded.open();
        const char* words[3] = {"borderkeysonce", "borderkeystwice", "borderkeysthrice"};
        const size_t lengths[3] = {14, 15, 16};
        const int32_t counts[3] = {1, 2, 3};
        loaded.engine.loadUserWords(words, lengths, counts, 3);

        check(loaded.rankOf("borderkeyso", "borderkeysonce") < 0,
              "a single sighting is not evidence enough to suggest");
        check(loaded.rankOf("borderkeystw", "borderkeystwice") < 0, "nor is a second");
        check(loaded.rankOf("borderkeysth", "borderkeysthrice") == 0,
              "a third use confirms it, and it is offered");

        // The gate reads the effective count, so the setting decides rather than this constant.
        loaded.engine.setLearningSpeed(3.0f);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "and \"the first time counts\" means exactly that");

        loaded.engine.setLearningSpeed(0.35f);
        check(loaded.rankOf("borderkeysth", "borderkeysthrice") < 0,
              "while the cautious setting wants more repetitions than three");
        loaded.engine.setLearningSpeed(1.0f);

        // Chosen on purpose once -- tapped on the strip, or put back after a correction -- and
        // the count no longer matters, at any setting.
        const int32_t asserted[3] = {1, 0, 0};
        loaded.engine.loadUserWords(words, lengths, counts, 3, nullptr, asserted);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "a word chosen once is offered from then on");
        loaded.engine.setLearningSpeed(0.35f);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "however cautious the setting");
        loaded.engine.setLearningSpeed(1.0f);
    }

    section("a word typed past does not vouch for itself");
    {
        // knownSpelling answers from the personal dictionary only for an established word.
        LoadedEngine loaded;
        loaded.open();
        char out[64];
        loaded.engine.learn("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        loaded.engine.learn("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        check(loaded.engine.knownSpelling("borderkeystypo", 14, out, sizeof(out)) == 0,
              "written twice, it is not a word the dictionaries know");
        loaded.engine.learn("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        check(loaded.engine.knownSpelling("borderkeystypo", 14, out, sizeof(out)) == 14,
              "written a third time, it is");

        loaded.engine.learn("borderkeysmine", 14, nullptr, 0, nullptr, 0, false, true);
        check(loaded.engine.knownSpelling("borderkeysmine", 14, out, sizeof(out)) == 14,
              "chosen once, it is at once");

        // A successor answers to the same rule.
        loaded.engine.learn("keyboard", 8, nullptr, 0, nullptr, 0);
        loaded.engine.learn("borderkeysnext", 14, "keyboard", 8, nullptr, 0);
        check(loaded.rankOf("", "borderkeysnext", "keyboard") < 0,
              "a word written once after another is not predicted after it");
        loaded.engine.learn("borderkeysnext", 14, "keyboard", 8, nullptr, 0, false, true);
        check(loaded.rankOf("", "borderkeysnext", "keyboard") >= 0, "until it is chosen");
    }

    section("a private field does not consult the personal dictionary");
    {
        // Nothing learned is offered into a private field; the model stays loaded.
        LoadedEngine loaded;
        loaded.open();
        const char* words[1] = {"borderkeysword"};
        const size_t lengths[1] = {14};
        const int32_t counts[1] = {40};
        loaded.engine.loadUserWords(words, lengths, counts, 1);
        check(loaded.rankOf("borderkeysw", "borderkeysword") == 0, "a personal word is offered");

        loaded.engine.setPersonalModelEnabled(false);
        check(loaded.rankOf("borderkeysw", "borderkeysword") < 0,
              "and not at all while the field is private");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0, "the dictionaries still answer");

        loaded.engine.setPersonalModelEnabled(true);
        check(loaded.rankOf("borderkeysw", "borderkeysword") == 0,
              "and it is back the moment an ordinary field switches it on again");
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

        // With nothing typed, the word that follows in the phrase is offered.
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

        // Two phrases starting from the same word, one written three times and one once: the
        // repeated one leads. Both words of each phrase are learned, as the service does.
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
        // The learning speed moves a personal word along the boost curve faster. It cannot make
        // that word beat a correctly typed one: kMaxUserBoost is 3.0 against an anchor of -8.0,
        // and "test" in the fixture scores -4.80.
        const auto heldAfterThreePicks = [](float speed) {
            LoadedEngine loaded;
            loaded.open();
            loaded.engine.setLearningSpeed(speed);
            for (int i = 0; i < 3; ++i) {
                loaded.engine.learn("testing", 7, nullptr, 0, nullptr, 0, false, true);
            }
            return loaded.scoreOf("test", "testing");
        };

        check(heldAfterThreePicks(3.0f) > heldAfterThreePicks(1.0f),
              "the impatient setting believes three picks sooner");
        check(heldAfterThreePicks(1.0f) > heldAfterThreePicks(0.35f),
              "and the cautious one later");

        // And the ranking that should hold at every setting: the word actually typed leads, and
        // the personal word is offered right behind it rather than instead of it.
        LoadedEngine loaded;
        loaded.open();
        loaded.engine.setLearningSpeed(3.0f);
        for (int i = 0; i < 3; ++i) {
            loaded.engine.learn("testing", 7, nullptr, 0, nullptr, 0, false, true);
        }
        check(loaded.rankOf("test", "test") == 0, "the word typed exactly is first");
        check(loaded.rankOf("test", "testing") == 1, "the learned word follows it, not replaces it");
    }

    section("a personal-dictionary word can still be a proper noun");
    {
        // "border" is flagged a proper noun in the test pack (build_dict.py's --selftest
        // fixture). Learned under a different case, the personal candidate keeps the flag
        // (Engine::candidateIsProperNoun).
        LoadedEngine loaded;
        loaded.open();
        // Three times, so the word is established -- see "a word written once or twice is kept
        // but not offered". This section is about the flag the candidate carries.
        loaded.engine.learn("Border", 6, nullptr, 0, nullptr, 0);
        loaded.engine.learn("Border", 6, nullptr, 0, nullptr, 0);
        loaded.engine.learn("Border", 6, nullptr, 0, nullptr, 0);

        Candidate out[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest("bord", 4, nullptr, 0, nullptr, 0, out,
                                                 Engine::kMaxCandidates);
        // The pack's copy and the personal one are one word, so it is offered once -- from
        // whichever source scored it higher -- and it carries the flag either way, since a
        // personal candidate looks its flag up across the packs.
        int slot = -1;
        int copies = 0;
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(out[i], &length);
            if (text != nullptr && sameSpellingIgnoringCase(text, length, "border", 6)) {
                slot = i;
                ++copies;
            }
        }
        check(copies == 1, "the learned word is offered once, not once per source");
        check(slot >= 0 && loaded.engine.candidateIsProperNoun(out[slot]),
              "and is still recognised as the proper noun the pack itself flags it as");
    }

    section("a personal word only becomes a proper noun once deliberately capitalised");
    {
        // "emanuel" is absent from the test pack, so this checks UserModel::deliberateCapitals
        // alone.
        LoadedEngine loaded;
        loaded.open();
        // Three times, so the word is established; the subject here is the capitalisation flag.
        loaded.engine.learn("emanuel", 7, nullptr, 0, nullptr, 0, false);
        loaded.engine.learn("emanuel", 7, nullptr, 0, nullptr, 0, false);
        loaded.engine.learn("emanuel", 7, nullptr, 0, nullptr, 0, false);

        Candidate out[Engine::kMaxCandidates];
        int found = loaded.engine.suggest("eman", 4, nullptr, 0, nullptr, 0, out,
                                           Engine::kMaxCandidates);
        int userPackSlot = -1;
        for (int i = 0; i < found; ++i) {
            if (out[i].packIndex == Candidate::kUserPack) {
                userPackSlot = i;
                break;
            }
        }
        check(userPackSlot >= 0, "the learned word is offered from the user model");
        check(userPackSlot >= 0 && !loaded.engine.candidateIsProperNoun(out[userPackSlot]),
              "and is not a proper noun yet -- it has never been capitalised on purpose");

        // Learned a second time, this time with a deliberate capital -- the way it would arrive
        // if the user had typed "Emanuel" with shift physically held for the "E".
        loaded.engine.learn("emanuel", 7, nullptr, 0, nullptr, 0, true);
        found = loaded.engine.suggest("eman", 4, nullptr, 0, nullptr, 0, out,
                                       Engine::kMaxCandidates);
        userPackSlot = -1;
        for (int i = 0; i < found; ++i) {
            if (out[i].packIndex == Candidate::kUserPack) {
                userPackSlot = i;
                break;
            }
        }
        check(userPackSlot >= 0 && loaded.engine.candidateIsProperNoun(out[userPackSlot]),
              "one deliberate capital is enough to mark it a name from then on");
    }

    section("correction strictness is a bounded multiplier, not an override");
    {
        // An invalid correction strictness falls back to a sane value.
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
        // With no bigram, the most frequent word is not offered as following itself.
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

        // Off by default.
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

    section("a blocked word is no longer a word");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");

        const auto block = [&loaded](std::initializer_list<const char*> words) {
            const char* texts[8] = {};
            size_t lengths[8] = {};
            int count = 0;
            for (const char* word : words) {
                texts[count] = word;
                lengths[count] = std::strlen(word);
                ++count;
            }
            loaded.engine.setBlockedWords(texts, lengths, count);
        };
        const auto correction = [&loaded](const char* composing) {
            Candidate out[Engine::kMaxCandidates];
            loaded.engine.suggest(composing, std::strlen(composing), nullptr, 0, nullptr, 0, out,
                                  Engine::kMaxCandidates);
            const Candidate* const best = loaded.engine.bestCorrection();
            uint32_t length = 0;
            const char* const text =
                best != nullptr ? loaded.engine.candidateText(*best, &length) : nullptr;
            return text != nullptr ? std::string(text, length) : std::string();
        };
        const auto known = [&loaded](const char* word) {
            char spelling[64];
            const int length =
                loaded.engine.knownSpelling(word, std::strlen(word), spelling, sizeof(spelling));
            return std::string(spelling, length > 0 ? static_cast<size_t>(length) : 0u);
        };
        const auto possessive = [&loaded](const char* word) {
            char spelling[64];
            const int length =
                loaded.engine.possessiveFor(word, std::strlen(word), spelling, sizeof(spelling));
            return std::string(spelling, length > 0 ? static_cast<size_t>(length) : 0u);
        };

        check(loaded.rankOf("masa", "masă") == 0 && loaded.rankOf("masa", "masa") > 0,
              "both spellings of one folded key are offered");
        check(known("masa") == "masa" && correction("masa") == "masa",
              "the one typed is known, and is what space commits");

        block({"MASA"});
        check(loaded.rankOf("masa", "masa") < 0, "a blocked spelling is not offered, case aside");
        check(loaded.rankOf("masa", "masă") == 0, "the other spelling of its key still is");
        check(known("masa") == "masă", "the typed word is not known; its key's other spelling is");
        check(correction("masa") == "masă", "and space commits that spelling");

        block({"\xC8\x98I"});
        check(loaded.rankOf("si", "\xC8\x99i") < 0, "Ș blocks ș");

        block({"în"});
        check(loaded.rankOf("in", "în") < 0 && known("in").empty(),
              "a key whose only spelling is blocked is not a word at all");
        check(correction("in") != "în", "and is never a correction");

        block({});
        Candidate out[Engine::kMaxCandidates];
        const int before =
            loaded.engine.suggest("", 0, "the", 3, nullptr, 0, out, Engine::kMaxCandidates);
        check(loaded.rankOf("", "time", "the") >= 0, "\"time\" is predicted after \"the\"");
        block({"time"});
        const int after =
            loaded.engine.suggest("", 0, "the", 3, nullptr, 0, out, Engine::kMaxCandidates);
        check(loaded.rankOf("", "time", "the") < 0, "blocked, it is not");
        check(after == before, "and the next word takes its place");

        const char* personal[1] = {"borderkeysword"};
        const size_t personalLengths[1] = {14};
        const int32_t personalCounts[1] = {40};
        loaded.engine.loadUserWords(personal, personalLengths, personalCounts, 1);
        block({});
        check(loaded.rankOf("borderkeysw", "borderkeysword") == 0 &&
                  known("borderkeysword") == "borderkeysword",
              "a personal word is offered and known");
        block({"borderkeysword"});
        check(loaded.rankOf("borderkeysw", "borderkeysword") < 0 &&
                  known("borderkeysword").empty(),
              "blocked, it is neither");

        block({});
        check(possessive("borders").empty(), "a word the pack holds takes no apostrophe");
        block({"borders"});
        check(possessive("borders") == "border's",
              "blocked, it is absent, and the name it extends takes the apostrophe");
        block({"borders", "border's"});
        check(possessive("borders").empty(), "unless the possessive itself is blocked");
        block({"borders", "border"});
        check(possessive("borders").empty(), "and a blocked name is no stem");

        block({});
        check(loaded.engine.vouchesForStem("keyboard", 8),
              "a pack word vouches for its inflections");
        block({"keyboard"});
        check(!loaded.engine.vouchesForStem("keyboard", 8), "a blocked one does not");

        LoadedEngine phrases;
        phrases.open();
        phrases.engine.setPhraseSuggestions(true);
        for (int i = 0; i < 6; ++i) {
            phrases.engine.learn("the", 3, nullptr, 0, nullptr, 0);
            phrases.engine.learn("test", 4, "the", 3, nullptr, 0);
            phrases.engine.learn("keys", 4, "test", 4, "the", 3);
        }
        check(phrases.rankOf("", "test keys", "the") > 0, "a phrase is offered");
        const char* blockedWord[1] = {"keys"};
        const size_t blockedLength[1] = {4};
        phrases.engine.setBlockedWords(blockedWord, blockedLength, 1);
        check(phrases.rankOf("", "test keys", "the") < 0, "not once one of its words is blocked");
    }

    section("the engine survives being used after release");
    {
        Engine engine;
        engine.create();
        engine.destroy();
        Candidate out[4];
        // The service can be destroyed while a request is already posted.
        check(engine.suggest("the", 3, nullptr, 0, nullptr, 0, out, 4) == 0,
              "suggesting after destroy returns nothing rather than touching freed memory");
    }
}
