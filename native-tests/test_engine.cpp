// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <initializer_list>
#include <limits>
#include <set>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include "candidate_sets.hpp"
#include "engine.hpp"
#include "jni_slots.hpp"
#include "reading.hpp"
#include "proximity.hpp"
#include "test_support.hpp"
#include "topk.hpp"
#include "tap_decode.hpp"
#include "touch_model.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

/** The test pack's language: the store the tests' requests read and their learning writes. */
constexpr const char* kPackLanguage = "ro";
constexpr size_t kPackLanguageLength = 2;

struct LoadedEngine {
    Engine engine;
    TestLayout layout;
    /** The keyboard's lookup for this engine. */
    WorkingCopyFixture copy{engine};

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
        const int found = copy.suggest(composing, length, nullptr, 0, nullptr, 0, xs, ys,
                                         xs != nullptr ? static_cast<int>(length) : 0, out,
                                         Engine::kMaxCandidates, kPackLanguage,
                                         kPackLanguageLength);
        return scoreIn(out, found, expected);
    }

    /**
     * The suggestions for the ASCII word `composing`, each letter tapped at `xs` and `ys`, or
     * untapped when they are null, after `previous1` and `previous2`, null for none: best first,
     * each `word:score` to three places, then a bar and autocorrect's word.
     */
    std::string answer(const char* composing, const float* xs, const float* ys,
                       const char* previous1 = nullptr, const char* previous2 = nullptr) {
        Candidate out[Engine::kMaxCandidates];
        const size_t length = std::strlen(composing);
        const int found = copy.suggest(composing, length, previous1, lengthOf(previous1),
                                         previous2, lengthOf(previous2), xs, ys,
                                         xs != nullptr ? static_cast<int>(length) : 0, out,
                                         Engine::kMaxCandidates, kPackLanguage,
                                         kPackLanguageLength);
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

    /**
     * Autocorrect's candidates for `composing`, best first, each `word` followed by
     * `/edits/pastTyped`, after the request has been made.
     */
    std::string corrections(const char* composing) {
        Candidate out[Engine::kMaxCandidates];
        copy.suggest(composing, std::strlen(composing), nullptr, 0, nullptr, 0, out,
                       Engine::kMaxCandidates, kPackLanguage, kPackLanguageLength);
        const Candidate* list = nullptr;
        const int count = engine.corrections(&list);
        std::string text;
        for (int i = 0; i < count; ++i) {
            uint32_t wordLength = 0;
            const char* const word = engine.candidateText(list[i], &wordLength);
            char parts[24];
            std::snprintf(parts, sizeof(parts), "/%d/%d ", list[i].edits, list[i].pastTyped);
            text.append(word != nullptr ? word : "?", word != nullptr ? wordLength : 1)
                .append(parts);
        }
        return text;
    }

    /** Whether autocorrect's list for `composing` holds `word` reached by slips alone. */
    bool slipsOnly(const char* composing, const char* word) {
        Candidate out[Engine::kMaxCandidates];
        copy.suggest(composing, std::strlen(composing), nullptr, 0, nullptr, 0, out,
                       Engine::kMaxCandidates, kPackLanguage, kPackLanguageLength);
        const Candidate* list = nullptr;
        const int count = engine.corrections(&list);
        for (int i = 0; i < count; ++i) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(list[i], &length);
            if (text != nullptr && length == std::strlen(word) &&
                std::memcmp(text, word, length) == 0) {
                return list[i].slipsOnly();
            }
        }
        return false;
    }

    /**
     * The tap decoder's word for the ASCII `composing`, letter i tapped at `xs[i]`, `ys[i]`, or
     * untapped when they are null, after `previous1`, null for none; empty when it found none.
     * `accepted` receives whether the engine accepted it, `margin` its margin over the typed
     * letters.
     */
    std::string decoded(const char* composing, const float* xs, const float* ys,
                        bool* accepted = nullptr, float* margin = nullptr,
                        const char* previous1 = nullptr) {
        Candidate out[Engine::kMaxCandidates];
        const size_t length = std::strlen(composing);
        copy.suggest(composing, length, previous1, lengthOf(previous1), nullptr, 0, xs, ys,
                       xs != nullptr ? static_cast<int>(length) : 0, out, Engine::kMaxCandidates,
                       kPackLanguage, kPackLanguageLength);
        if (accepted != nullptr) {
            *accepted = engine.decodedCorrection() != nullptr;
        }
        Candidate best{};
        if (!engine.decoderBest(&best, margin)) {
            return std::string();
        }
        uint32_t wordLength = 0;
        const char* const word = engine.candidateText(best, &wordLength);
        return word != nullptr ? std::string(word, wordLength) : std::string();
    }

    /**
     * Taps for the ASCII `typed` into `xs` and `ys`: each at its key's centre, moved `share` of
     * the way towards the key of the same letter of `meant`.
     */
    void leanTaps(const char* typed, const char* meant, float share, float* xs, float* ys) const {
        for (size_t i = 0; typed[i] != '\0'; ++i) {
            float meantX = 0.f;
            float meantY = 0.f;
            layout.centreOf(typed[i], &xs[i], &ys[i]);
            layout.centreOf(meant[i], &meantX, &meantY);
            xs[i] += share * (meantX - xs[i]);
            ys[i] += share * (meantY - ys[i]);
        }
    }

    /**
     * The rank of `expected` among the suggestions for `composing`, or -1; the personal pairs and
     * triples read in the store of `language`.
     */
    int rankOf(const char* composing, const char* expected, const char* previous = nullptr,
               const char* previous2 = nullptr, const char* language = kPackLanguage) {
        Candidate out[Engine::kMaxCandidates];
        const int found = copy.suggest(composing, std::strlen(composing), previous,
                                       previous != nullptr ? std::strlen(previous) : 0, previous2,
                                       previous2 != nullptr ? std::strlen(previous2) : 0, out,
                                       Engine::kMaxCandidates, language, std::strlen(language));
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

    /** Writes `previous` and then `word` after it, `times` times, as the keyboard learns them. */
    void learnAfter(const char* previous, const char* word, uint32_t times) {
        for (uint32_t i = 0; i < times; ++i) {
            copy.commit(previous, std::strlen(previous), nullptr, 0, nullptr, 0);
            copy.commit(word, std::strlen(word), previous, std::strlen(previous), nullptr, 0,
                         false, false, kPackLanguage, kPackLanguageLength);
        }
    }

    /**
     * The score `expected` carries among the suggestions for `composing` after `previous1`, and
     * `previous2` before it, either null for none; 0 if absent.
     */
    float scoreAfter(const char* composing, const char* expected, const char* previous1,
                     const char* previous2 = nullptr) {
        Candidate out[Engine::kMaxCandidates];
        const int found = copy.suggest(composing, std::strlen(composing), previous1,
                                         lengthOf(previous1), previous2, lengthOf(previous2), out,
                                         Engine::kMaxCandidates, kPackLanguage,
                                         kPackLanguageLength);
        return scoreIn(out, found, expected);
    }

    /** The score `expected` carries in autocorrect's list for `composing` after `previous1`. */
    float correctionScoreAfter(const char* composing, const char* expected,
                               const char* previous1) {
        Candidate out[Engine::kMaxCandidates];
        copy.suggest(composing, std::strlen(composing), previous1, lengthOf(previous1), nullptr,
                       0, out, Engine::kMaxCandidates, kPackLanguage, kPackLanguageLength);
        const Candidate* list = nullptr;
        const int count = engine.corrections(&list);
        return scoreIn(list, count, expected);
    }

    /** Autocorrect's word for `composing` after `previous1`, its score in `score`; empty for none. */
    std::string bestAfter(const char* composing, const char* previous1, float* score) {
        Candidate out[Engine::kMaxCandidates];
        copy.suggest(composing, std::strlen(composing), previous1, lengthOf(previous1), nullptr,
                       0, out, Engine::kMaxCandidates, kPackLanguage, kPackLanguageLength);
        const Candidate* const best = engine.bestCorrection();
        if (best == nullptr) {
            return std::string();
        }
        *score = best->score;
        uint32_t length = 0;
        const char* const text = engine.candidateText(*best, &length);
        return text != nullptr ? std::string(text, length) : std::string();
    }

    /** Whether `word` stands in autocorrect's list from the last request; false when absent. */
    bool stands(const char* word) const {
        const Candidate* list = nullptr;
        const int count = engine.corrections(&list);
        for (int i = 0; i < count; ++i) {
            if (spells(list[i], word)) {
                return engine.correctionConfident(i);
            }
        }
        return false;
    }

    /** The score of `expected` among the `count` candidates at `list`; 0 if absent. */
    float scoreIn(const Candidate* list, int count, const char* expected) const {
        for (int i = 0; i < count; ++i) {
            if (spells(list[i], expected)) {
                return list[i].score;
            }
        }
        return 0.0f;
    }

    /** Whether `candidate` is spelled `word`. */
    bool spells(const Candidate& candidate, const char* word) const {
        uint32_t length = 0;
        const char* const text = engine.candidateText(candidate, &length);
        return text != nullptr && length == std::strlen(word) && std::memcmp(text, word, length) == 0;
    }

    /** The length of `text`, 0 for null. */
    static size_t lengthOf(const char* text) { return text != nullptr ? std::strlen(text) : 0; }
};

/**
 * kCount learned words that each continue `stem` by one letter, from `firstEnding` on, used
 * kFewestUses to kFewestUses + kCount - 1 times, the counts scattered through them; after
 * `leading`, when one is given, used `leadingUses` times.
 */
struct LearnedContinuations {
    static constexpr int kCount = 20;
    static constexpr int32_t kFewestUses = 5;
    // The step the counts take through the words, prime to kCount so each count comes once.
    static constexpr int kScatterStep = 7;
    // The longest stem, and room for its ending and the terminator.
    static constexpr size_t kMaxStem = 8;
    static constexpr size_t kSpellingBytes = kMaxStem + 2;

    char spellings[kCount][kSpellingBytes] = {};
    const char* words[kCount + 1] = {};
    size_t lengths[kCount + 1] = {};
    int32_t counts[kCount + 1] = {};
    int size = 0;

    LearnedContinuations(const char* stem, char firstEnding, const char* leading = nullptr,
                         int32_t leadingUses = 0) {
        if (leading != nullptr) {
            add(leading, leadingUses);
        }
        for (int i = 0; i < kCount; ++i) {
            std::snprintf(spellings[i], sizeof(spellings[i]), "%s%c", stem, firstEnding + i);
            add(spellings[i], kFewestUses + (i * kScatterStep) % kCount);
        }
    }

    void loadInto(WorkingCopyFixture& copy) const { copy.loadWords(words, lengths, counts, size); }

    /** The uses of the continuation spelled `text`, or -1 when it is none of them. */
    int32_t usesOf(const char* text, uint32_t length) const {
        for (int i = 0; i < kCount; ++i) {
            if (length == std::strlen(spellings[i]) && std::memcmp(text, spellings[i], length) == 0) {
                return kFewestUses + (i * kScatterStep) % kCount;
            }
        }
        return -1;
    }

private:
    void add(const char* word, int32_t uses) {
        words[size] = word;
        lengths[size] = std::strlen(word);
        counts[size] = uses;
        ++size;
    }
};

/** Loads `words` into `table`, each with `uses`, and returns their entries in order. */
std::vector<int32_t> holdWords(WordTable& table, const std::vector<const char*>& words,
                               int32_t uses = 1) {
    const std::vector<const char*>& spellings = words;
    std::vector<size_t> lengths;
    std::vector<int32_t> fields;
    for (const char* word : words) {
        lengths.push_back(std::strlen(word));
        fields.insert(fields.end(), {uses, 0, 0, 0});
    }
    std::vector<int32_t> ids(spellings.size(), WordTable::kNewWord);
    table.load(spellings.data(), lengths.data(), fields.data(), static_cast<int>(spellings.size()),
               ids.data());
    return ids;
}

/** A link a test hands in a slice: its last word's id, its count and its choices. */
struct HandedLink {
    int32_t word;
    int32_t count;
    int32_t asserted;
};

/** A slice as a test hands it: the arrays ContextSlice::replace and Engine::pushSlice take. */
struct HandedSlice {
    std::vector<int32_t> header = std::vector<int32_t>(ContextSlice::kHeaderFields, 0);
    std::vector<int32_t> links;
    std::vector<int32_t> hops;
    std::vector<int64_t> seen = std::vector<int64_t>(ContextSlice::kContextSeenFields, 0);

    /** `key`'s context: `pairs` after the word before and `triples` after the two, with their totals. */
    HandedSlice(const ContextSlice::Key& key, int32_t pairTotal, const std::vector<HandedLink>& pairs,
                int32_t tripleTotal = 0, const std::vector<HandedLink>& triples = {}) {
        header[ContextSlice::kLanguageField] = key.language;
        header[ContextSlice::kContext1Field] = key.context1;
        header[ContextSlice::kContext2Field] = key.context2;
        header[ContextSlice::kPairTotalField] = pairTotal;
        header[ContextSlice::kTripleTotalField] = tripleTotal;
        header[ContextSlice::kPairsField] = static_cast<int32_t>(pairs.size());
        header[ContextSlice::kTriplesField] = static_cast<int32_t>(triples.size());
        for (const std::vector<HandedLink>* kind : {&pairs, &triples}) {
            for (const HandedLink& link : *kind) {
                links.insert(links.end(), {link.word, link.count, link.asserted});
                seen.push_back(0);
            }
        }
    }

    /** Adds a hop for the next pair: the link after its word, and that context's total. */
    HandedSlice& hop(int32_t word, int32_t count, int32_t asserted, int32_t middleTotal) {
        hops.insert(hops.end(), {word, count, asserted, middleTotal});
        header[ContextSlice::kHopsField] += 1;
        return *this;
    }

    bool into(ContextSlice& slice) const {
        return slice.replace(header.data(), header.size(), links.data(), links.size(), hops.data(),
                             hops.size(), seen.data(), seen.size());
    }

    bool into(Engine& engine) const {
        return engine.pushSlice(header.data(), header.size(), links.data(), links.size(),
                                hops.data(), hops.size(), seen.data(), seen.size());
    }
};

}  // namespace

void runEngineTests() {
    section("top-K heap");
    {
        struct Item {
            float score;
            bool ranksBelow(const Item& other) const { return score < other.score; }
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

    section("the candidate sets keep each kind apart and drain best first");
    {
        constexpr int kContinuationRoom = 2;
        constexpr int kOtherRoom = 3;
        Candidate continuationStorage[kContinuationRoom];
        Candidate otherStorage[kOtherRoom];
        Candidate continuationScratch[kContinuationRoom];
        Candidate otherScratch[kOtherRoom];
        CandidateSets sets;
        sets.reset(continuationStorage, kContinuationRoom, otherStorage, kOtherRoom);
        for (const float score : {5.f, 1.f, 3.f}) {
            sets.heapFor(true).offer(Candidate{0, 0, score});
        }
        for (const float score : {-1.f, 4.f, 0.f, 2.f}) {
            sets.heapFor(false).offer(Candidate{0, 0, score});
        }
        check(sets.size() == kContinuationRoom + kOtherRoom, "each set keeps only its own best");
        checkNear(sets.lowestFloor(), 0.f, 0.001f, "the lower floor is the others' 0, below 3");
        constexpr int kDrained = 4;
        Candidate out[kDrained];
        check(sets.drain(out, kDrained, continuationScratch, otherScratch) == kDrained &&
                  out[0].score == 5.f && out[1].score == 4.f && out[2].score == 3.f &&
                  out[3].score == 2.f,
              "the drain merges both best first, up to the room given");
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

        const float onG = defaults.tapLogLikelihood(geometry, 'g', gx, gy);
        const float hFromG = defaults.tapLogLikelihood(geometry, 'h', gx, gy);
        checkNear(onG - hFromG,
                  0.5f / (TouchModel::kReferenceSpread * TouchModel::kReferenceSpread), 0.01f,
                  "a tap on a key's centre is one key width, squared over the spread, less "
                  "likely for its neighbour");
        checkNear(defaults.tapLogLikelihood(geometry, 'g', (gx + hx) / 2.f, gy),
                  defaults.tapLogLikelihood(geometry, 'h', (gx + hx) / 2.f, gy), 0.001f,
                  "a tap halfway between two keys is as likely for either");
        check(defaults.tapLogLikelihood(geometry, 'p', gx, gy) < hFromG,
              "a far key is less likely than a neighbour");
        check(defaults.tapLogLikelihood(geometry, 'g', none, none) ==
                  TouchModel::kOffKeyLogLikelihood,
              "a tap with no point gives the off-key likelihood");

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
        check(leaning.tapLogLikelihood(geometry, 'h', towardsH, gy) >
                  defaults.tapLogLikelihood(geometry, 'h', towardsH, gy),
              "a tap where a key's taps lean is more likely for that key once learned");

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

    section("a word the pack only knows is never offered, and counts as spelled within reach");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        char out[64];
        check(loaded.engine.knownSpelling("timepiece", 9, out, sizeof(out)) == 0,
              "by default it is not a word the dictionaries spell");
        loaded.engine.setKnownWordReach(-std::numeric_limits<float>::infinity());
        check(loaded.engine.knownSpelling("timepiece", 9, out, sizeof(out)) == 9,
              "within reach it is");
        loaded.engine.setKnownWordReach(std::log(3e-6f) + 0.5f);
        check(loaded.engine.knownSpelling("timepiece", 9, out, sizeof(out)) == 0,
              "and out of reach once the reach asks for commoner words");
        loaded.engine.setKnownWordReach(-std::numeric_limits<float>::infinity());
        check(loaded.rankOf("keyboardi", "keyboardist") < 0 && loaded.rankOf("timepie", "timepiece") < 0,
              "it is never completed");
        check(loaded.rankOf("keyboardjst", "keyboardist") < 0, "nor corrected to");
        check(loaded.decoded("tumepiecr", nullptr, nullptr) != "timepiece", "nor decoded to");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0, "and the words the pack offers are as before");
    }

    section("continuations past the ones the strip shows take no other candidate's place");
    {
        // LearnedContinuations::kCount learned words continue "zq"; "zq" itself is used as often
        // as learning asks and scores below every one of them.
        constexpr int kShown = Engine::kMaxShownCompletions;
        constexpr int32_t kTypedUses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        constexpr char kTyped[] = "zq";
        const LearnedContinuations learned("zqa", 'a', kTyped, kTypedUses);
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        learned.loadInto(loaded.copy);
        Candidate out[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest(kTyped, std::strlen(kTyped), nullptr, 0, nullptr, 0,
                                                out, Engine::kMaxCandidates);
        bool typedOffered = false;
        int continuations = 0;
        int continuationsBeforeTyped = 0;
        int32_t fewestShown = std::numeric_limits<int32_t>::max();
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(out[i], &length);
            if (text == nullptr) {
                continue;
            }
            if (std::string(text, length) == kTyped) {
                typedOffered = true;
                continuationsBeforeTyped = continuations;
            }
            const int32_t uses = learned.usesOf(text, length);
            if (uses >= 0) {
                ++continuations;
                fewestShown = std::min(fewestShown, uses);
            }
        }
        check(typedOffered, "the word typed is offered beside stronger continuations");
        check(continuations == kShown, "as many continuations as the strip shows are offered");
        check(fewestShown ==
                  LearnedContinuations::kFewestUses + LearnedContinuations::kCount - kShown,
              "and they are the ones used most");
        check(continuationsBeforeTyped == kShown,
              "in score order: the continuations before the weaker word typed");
    }

    section("a word reached by an edit is not one of the continuations the strip shows");
    {
        // LearnedContinuations::kCount learned words continue "thez"; the pack's "these" is
        // "thez" with a neighbouring key's slip, continued.
        constexpr int kShown = Engine::kMaxShownCompletions;
        constexpr char kTyped[] = "thez";
        constexpr char kEdited[] = "these";
        const LearnedContinuations learned(kTyped, 'f');
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        learned.loadInto(loaded.copy);
        Candidate out[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest(kTyped, std::strlen(kTyped), nullptr, 0, nullptr, 0,
                                                out, Engine::kMaxCandidates);
        int editedAt = -1;
        int continuations = 0;
        int lastContinuationAt = -1;
        for (int i = 0; i < found; ++i) {
            uint32_t length = 0;
            const char* const text = loaded.engine.candidateText(out[i], &length);
            if (text == nullptr) {
                continue;
            }
            if (std::string(text, length) == kEdited) {
                editedAt = i;
            }
            if (learned.usesOf(text, length) >= 0) {
                ++continuations;
                lastContinuationAt = i;
            }
        }
        check(editedAt >= 0, "\"these\", one slip from \"thez\" and continued, is offered");
        check(continuations == kShown, "beside as many continuations as the strip shows");
        check(editedAt > lastContinuationAt, "and below them, by its score");
    }

    section("the tap decoder weighs each word against all the others");
    {
        DecodedWords words;
        check(!words.found(), "nothing reached, nothing found");
        words.offer(Candidate{0, 7, 0.f}, -10.0f);
        words.offer(Candidate{0, 9, 0.f}, -10.2f);
        words.offer(Candidate{0, 7, 0.f}, -12.0f);
        checkNear(words.bestTotal(), logAddExp(-10.0f, -12.0f), 0.0001f,
                  "a word reached by two paths holds both paths' probability");
        check(words.best().wordIndex == 7, "the likeliest word is the best");
        checkNear(words.othersTotal(), -10.2f, 0.0001f, "the rest is every other word together");
        checkNear(logAddExp(kDecodeNegativeInfinity, -3.0f), -3.0f, 0.0f,
                  "adding nothing changes nothing");
    }

    section("the beam's cut depends on the scores alone");
    {
        Arena arena;
        check(arena.init(64 * 1024), "the arena opens");
        DecoderLevel ascending;
        DecoderLevel descending;
        check(ascending.allocate(arena, 64, 8) && descending.allocate(arena, 64, 8),
              "two levels of 64, kept to 8");
        const float scores[] = {-1.f, -1.f, -1.f, -1.f, -2.f, -2.f, -2.f, -2.f, -2.f, -2.f,
                                -2.f, -2.f, -2.f, -2.f, -3.f, -3.f, -3.f, -3.f, -3.f, -3.f};
        const int count = static_cast<int>(sizeof(scores) / sizeof(scores[0]));
        for (int i = 0; i < count; ++i) {
            ascending.push(DecoderState{i, scores[i], 0});
            descending.push(DecoderState{count - i, scores[i], 0});
        }
        ascending.prune();
        descending.prune();
        const auto worst = [](const DecoderLevel& level) {
            float low = 0.f;
            for (int i = 0; i < level.count(); ++i) {
                low = std::min(low, level[i].score);
            }
            return low;
        };
        check(ascending.count() == 14 && descending.count() == 14,
              "every hypothesis tied with the eighth best is kept");
        check(worst(ascending) == -2.f && worst(descending) == -2.f,
              "and none below it, whatever the nodes are numbered");

        DecoderLevel small;
        check(small.allocate(arena, 16, 4), "a level of 16, kept to 4");
        for (int i = 0; i < 40; ++i) {
            small.push(DecoderState{i, -1.f, 0});
        }
        check(small.count() <= 16, "ties past the capacity still leave room for the next push");
    }

    section("a word the decoder only offers goes second on the strip");
    {
        const auto sameWord = [](const Candidate& a, const Candidate& b) {
            return a.wordIndex == b.wordIndex;
        };
        // The word offered, and the three the engine ranked, best first.
        constexpr int32_t kOffered = 99;
        constexpr int32_t kBest = 1;
        constexpr int32_t kNext = 2;
        constexpr int32_t kLast = 3;
        constexpr int kRanked = 3;
        constexpr int kRoom = kRanked + 1;
        const Candidate offered{0, kOffered, -40.f};
        Candidate out[kRoom] = {{0, kBest, 5.f}, {0, kNext, 4.f}, {0, kLast, 3.f}, {}};
        const int written = placeOffered(out, kRanked, kRoom, offered, sameWord);
        check(written == kRoom && out[0].wordIndex == kBest &&
                  out[kOfferedPlace].wordIndex == kOffered && out[2].wordIndex == kNext &&
                  out[3].wordIndex == kLast,
              "after the engine's best, the rest moved down");
        check(out[kOfferedPlace].score == out[0].score, "taking the score of the word before it");
        Candidate full[kRanked] = {{0, kBest, 5.f}, {0, kNext, 4.f}, {0, kLast, 3.f}};
        check(placeOffered(full, kRanked, kRanked, offered, sameWord) == kRanked &&
                  full[kOfferedPlace].wordIndex == kOffered && full[2].wordIndex == kNext,
              "a full strip drops its last word");
        Candidate empty[kRoom] = {};
        check(placeOffered(empty, 0, kRoom, offered, sameWord) == 1 && empty[0].wordIndex == kOffered,
              "an empty strip takes it first");
        Candidate listed[kRoom] = {{0, kOffered, 5.f}, {}};
        check(placeOffered(listed, 1, kRoom, offered, sameWord) == 1,
              "a word already listed is not listed twice");
        Candidate lower[kRoom] = {{0, kBest, 5.f}, {0, kNext, 4.f}, {0, kLast, 3.f}, {0, kOffered, 2.f}};
        check(placeOffered(lower, kRoom, kRoom, offered, sameWord) == kRoom &&
                  lower[0].wordIndex == kBest && lower[kOfferedPlace].wordIndex == kOffered &&
                  lower[2].wordIndex == kNext && lower[3].wordIndex == kLast,
              "a word listed below second place moves up to second, once");
        check(lower[kOfferedPlace].score == lower[0].score,
              "taking the score of the word before it there too");
        Candidate second[kRanked] = {{0, kBest, 5.f}, {0, kOffered, 4.f}, {0, kLast, 3.f}};
        const float secondScore = second[kOfferedPlace].score;
        check(placeOffered(second, kRanked, kRanked, offered, sameWord) == kRanked &&
                  second[kOfferedPlace].wordIndex == kOffered &&
                  second[kOfferedPlace].score == secondScore,
              "a word already second stays as it is");
    }

    section("the letter model reads spellings like the pack's own");
    {
        struct stat info {};
        LanguagePack pack;
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        const bool opened = fd >= 0 && stat(BORDERKEYS_TEST_PACK, &info) == 0 &&
                            pack.open("ro-RO", fd, 0, info.st_size) == kBkdOk;
        if (fd >= 0) {
            ::close(fd);
        }
        check(opened && pack.letters().isBuilt(), "a pack builds its letter model as it opens");
        const auto spelling = [&pack](const char* text) {
            uint32_t folded[16];
            const int count = foldUtf8(text, std::strlen(text), folded, 16);
            return pack.letters().spellingLogProb(pack.trie(), folded, count);
        };
        check(spelling("theme") > spelling("emhet"),
              "a pack's spelling outscores its own letters in an order no spelling holds");
        check(spelling("theme") > spelling("them\xD0\xB6"),
              "a letter outside the pack's alphabet is the least likely of all");
        pack.close();
        check(!pack.letters().isBuilt(), "closing the pack drops its letter model");
    }

    section("the tap decoder reads where each tap landed");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads the test pack");
        // Three of eight letters on a neighbouring key: past the walk's ceiling.
        const char typed[] = "jeubpard";
        const char meant[] = "keyboard";
        float xs[8];
        float ys[8];
        bool accepted = false;
        float leaning = 0.f;
        loaded.leanTaps(typed, meant, 0.45f, xs, ys);
        check(loaded.decoded(typed, xs, ys, &accepted, &leaning) == meant && accepted,
              "taps at the edge of the keys meant decode to the word, and it is accepted");

        float centred = 0.f;
        loaded.leanTaps(typed, meant, 0.f, xs, ys);
        loaded.decoded(typed, xs, ys, &accepted, &centred);
        check(centred < leaning, "the same letters tapped at their own centres fit the word less");

        check(loaded.decoded("there", nullptr, nullptr).empty(),
              "a word a dictionary spells is not decoded");
        check(loaded.decoded("thr", nullptr, nullptr).empty(), "nor a word under four letters");
        check(loaded.decoded("jeubpard'", nullptr, nullptr).empty() &&
                  loaded.decoded("'jeubpard", nullptr, nullptr).empty(),
              "nor a word that starts or ends with a mark");
        check(loaded.decoded(typed, nullptr, nullptr) == meant &&
                  loaded.decoded("jeub'pard", nullptr, nullptr) != meant,
              "a typed mark is read as itself, never dropped as a stray tap");

        const char nameTyped[] = "bprder";
        const char nameMeant[] = "border";
        loaded.leanTaps(nameTyped, nameMeant, 0.45f, xs, ys);
        const std::string pastName = loaded.decoded(nameTyped, xs, ys);
        check(!pastName.empty() && pastName != nameMeant,
              "a name is never decoded to; the decoder reads past it to another word");

        // A learned word's boost joins the decoder's evidence for it.
        float base = 0.f;
        loaded.leanTaps(typed, meant, 0.f, xs, ys);
        loaded.decoded(typed, xs, ys, nullptr, &base);
        const char* const learnedWord[1] = {meant};
        const size_t learnedLength[1] = {sizeof(meant) - 1};
        const int32_t uses[1] = {9};
        loaded.copy.loadWords(learnedWord, learnedLength, uses, 1);
        float boosted = 0.f;
        loaded.decoded(typed, xs, ys, nullptr, &boosted);
        checkRise(boosted, base, PersonalScore::wordBoostForCount(static_cast<uint32_t>(uses[0])),
                  "a learned word's margin rises by its word boost");
        loaded.engine.setPersonalModelEnabled(false);
        float off = 0.f;
        loaded.decoded(typed, xs, ys, nullptr, &off);
        check(off == base, "and with the personal model off it reads as before");
        loaded.engine.setPersonalModelEnabled(true);
        loaded.copy.loadWords(nullptr, nullptr, nullptr, 0);

        const char* const personal[1] = {typed};
        const size_t personalLength[1] = {sizeof(typed) - 1};
        const int32_t once[1] = {1};
        loaded.copy.loadWords(personal, personalLength, once, 1, nullptr, once);
        loaded.leanTaps(typed, meant, 0.45f, xs, ys);
        check(loaded.decoded(typed, xs, ys).empty(),
              "the letters as typed are not decoded once chosen on purpose as a word of the user's");
        loaded.copy.loadWords(nullptr, nullptr, nullptr, 0);

        const char* const blocked[1] = {meant};
        const size_t blockedLength[1] = {sizeof(meant) - 1};
        loaded.engine.setBlockedWords(blocked, blockedLength, 1);
        loaded.leanTaps(typed, meant, 0.45f, xs, ys);
        check(loaded.decoded(typed, xs, ys) != meant, "nor a blocked word");
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
        check(loaded.rankOf("bard", "bars") >= 0 &&
                  loaded.rankOf("bard", "bars") < loaded.rankOf("bard", "board"),
              "a straight neighbour's slip outranks an inserted letter of an equally common word");

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

        // answerAs makes the requests answer as the pack it names, until called with kNoPack.
        const int32_t verdict = loaded.engine.dominantPack();
        check(loaded.engine.answerAs(1), "an open, active pack can be answered as");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0,
              "answering as pack 1 still corrects a typo through it");
        check(loaded.engine.dominantPack() == verdict,
              "answering as a pack leaves the detected language as it was");
        check(loaded.engine.answerAs(Engine::kNoPack), "kNoPack returns the requests to the evidence");
        check(!loaded.engine.answerAs(2), "a pack index with nothing loaded in it is refused");
        check(!loaded.engine.answerAs(Engine::kNoPack - 1), "and so is one below kNoPack");
        check(loaded.rankOf("keyboarf", "keyboard") >= 0,
              "a refused pack leaves the requests answering as before");
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
        loaded.copy.loadWords(words, lengths, counts, 2);
        // The pair the keyboard records for a word with nothing before it: the sentence start
        // itself as the context.
        const char* previous[1] = {"\x02start"};
        const size_t previousLengths[1] = {6};
        const char* next[1] = {"hello"};
        const size_t nextLengths[1] = {5};
        const int32_t pairCounts[1] = {5};
        // In the store of the test pack's language.
        const char* languages[1] = {"ro"};
        const size_t languageLengths[1] = {2};
        loaded.copy.loadPairs(languages, languageLengths, previous, previousLengths, next,
                                      nextLengths, pairCounts, 1);
        auto slotsOf = [&](int* helloAt, int* markerAt) {
            Candidate out[Engine::kMaxCandidates];
            const int found = loaded.copy.suggest("", 0, nullptr, 0, nullptr, 0, out,
                                                    Engine::kMaxCandidates, kPackLanguage,
                                                    kPackLanguageLength);
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

    section("the word table takes each entry as handed");
    {
        WordTable table;
        const char* spellings[] = {"Paris", "masă", "zqxv"};
        const size_t lengths[] = {5, std::strlen("masă"), 4};
        // Each row's count, deliberate capitals, choices and adds.
        const int32_t fields[] = {7, 2, 1, 0, 3, 0, 0, 1, 0, 0, 0, 0};
        int32_t ids[3] = {-1, -1, -1};
        check(table.load(spellings, lengths, fields, 3, ids) == 3, "every row is held");
        uint32_t length = 0;
        const char* const paris = table.entryText(static_cast<uint32_t>(ids[0]), &length);
        check(paris != nullptr && length == 5 && std::memcmp(paris, "Paris", 5) == 0,
              "an entry is spelled as handed");
        check(table.entryCount(static_cast<uint32_t>(ids[0])) == 7 &&
                  table.deliberateCapitals(static_cast<uint32_t>(ids[0])) == 2 &&
                  table.asserted(static_cast<uint32_t>(ids[0])) == 1 &&
                  table.added(static_cast<uint32_t>(ids[0])) == 0,
              "and carries the counts handed with it");
        check(table.added(static_cast<uint32_t>(ids[1])) == 1 &&
                  table.entryCount(static_cast<uint32_t>(ids[2])) == 0,
              "an added word and a word with no uses are held as handed");
        check(table.entryIndexFor("PARIS", 5) == ids[0], "an entry is found in any case");
        check(table.entryIndexFor("masa", 4) < 0, "and not without its marks");

        const int32_t again[] = {ids[0]};
        const char* respelled[] = {"paris"};
        const size_t respelledLengths[] = {5};
        const int32_t replaced[] = {1, 0, 0, 0};
        int32_t put[1] = {-1};
        check(table.put(again, respelled, respelledLengths, replaced, 1, put) == 1 &&
                  put[0] == ids[0],
              "a put keeps the entry's id");
        const char* const lowered = table.entryText(static_cast<uint32_t>(ids[0]), &length);
        check(lowered != nullptr && length == 5 && std::memcmp(lowered, "paris", 5) == 0 &&
                  table.entryCount(static_cast<uint32_t>(ids[0])) == 1 &&
                  table.deliberateCapitals(static_cast<uint32_t>(ids[0])) == 0 &&
                  table.asserted(static_cast<uint32_t>(ids[0])) == 0,
              "and replaces its spelling and counts with the ones handed, adding nothing");
        const char* unspelled[] = {nullptr};
        const size_t noLengths[] = {0};
        const int32_t counted[] = {4, 0, 0, 0};
        table.put(again, unspelled, noLengths, counted, 1, put);
        const char* const kept = table.entryText(static_cast<uint32_t>(ids[0]), &length);
        check(kept != nullptr && length == 5 && std::memcmp(kept, "paris", 5) == 0 &&
                  table.entryCount(static_cast<uint32_t>(ids[0])) == 4,
              "a put with no spelling keeps the one held");
        const char* otherWord[] = {"london"};
        const size_t otherLengths[] = {6};
        check(table.put(again, otherWord, otherLengths, counted, 1, put) == 0 && put[0] < 0 &&
                  table.entryIndexFor("london", 6) < 0,
              "a put spelling another word under an entry's id is refused");
    }

    section("word ids are dense and append only");
    {
        WordTable table;
        const char* spellings[] = {"one", "two", "three"};
        const size_t lengths[] = {3, 3, 5};
        const int32_t fields[] = {1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0};
        int32_t ids[3] = {-1, -1, -1};
        table.load(spellings, lengths, fields, 3, ids);
        check(ids[0] == 0 && ids[1] == 1 && ids[2] == 2, "a load numbers its rows from zero");

        const int32_t fresh[] = {WordTable::kNewWord, WordTable::kNewWord};
        const char* added[] = {"four", "five"};
        const size_t addedLengths[] = {4, 4};
        const int32_t addedFields[] = {1, 0, 0, 0, 1, 0, 0, 0};
        int32_t put[2] = {-1, -1};
        table.put(fresh, added, addedLengths, addedFields, 2, put);
        check(put[0] == 3 && put[1] == 4, "a new word takes the next id");

        const int32_t gone[] = {1};
        const int32_t zeroed[] = {0, 0, 0, 0};
        table.put(gone, nullptr, nullptr, zeroed, 1, put);
        check(put[0] == 1 && table.entryIndexFor("two", 3) == 1 &&
                  table.entryCount(1) == 0,
              "a deleted word keeps its id, its counts zeroed");
        const int32_t another[] = {WordTable::kNewWord};
        const char* six[] = {"six"};
        const size_t sixLength[] = {3};
        table.put(another, six, sixLength, addedFields, 1, put);
        check(put[0] == 5 && table.size() == 6, "and its id is not given to the next new word");
        const char* heldAgain[] = {"Three"};
        const size_t heldLength[] = {5};
        table.put(another, heldAgain, heldLength, addedFields, 1, put);
        uint32_t length = 0;
        const char* const three = table.entryText(2, &length);
        check(put[0] == WordTable::kNewWord && table.size() == 6 && table.entryCount(2) == 3 &&
                  three != nullptr && length == 5 && std::memcmp(three, "three", 5) == 0,
              "a new word the table holds already is refused, its entry left as it was");

        const char* reloaded[] = {"seven", "Seven", "eight"};
        const size_t reloadedLengths[] = {5, 5, 5};
        int32_t again[3] = {-1, -1, -1};
        check(table.load(reloaded, reloadedLengths, fields, 3, again) == 2 && again[0] == 0 &&
                  again[1] < 0 && again[2] == 1 && table.size() == 2,
              "a load starts the ids again, a second row for one word refused");
    }

    section("personal dictionary");
    {
        LoadedEngine loaded;
        loaded.open();
        const char* words[2] = {"Razvan", "borderkeys"};
        const size_t lengths[2] = {6, 10};
        const int32_t counts[2] = {12, 40};
        loaded.copy.loadWords(words, lengths, counts, 2);
        check(loaded.rankOf("raz", "Razvan") == 0, "a personal word is suggested");
        check(loaded.rankOf("border", "borderkeys") == 0,
              "a word confirmed forty times outranks a rare dictionary word");

        // A pack word the personal dictionary also holds, capitalised: one suggestion, not two
        // that the strip would then show as the same word twice.
        const char* capitalised[1] = {"Keyboard"};
        const size_t capitalisedLength[1] = {8};
        const int32_t capitalisedCount[1] = {40};
        loaded.copy.loadWords(capitalised, capitalisedLength, capitalisedCount, 1);
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
    }

    section("a link's context words are held without being counted");
    {
        LoadedEngine taught;
        check(taught.open(), "the engine loads");
        LoadedEngine wordOnly;
        check(wordOnly.open(), "a second engine loads");
        LoadedEngine pairOnly;
        check(pairOnly.open(), "a third engine loads");
        // Words before that the pack only knows, never offers, and the user model holds no count for.
        for (Engine* engine : {&taught.engine, &wordOnly.engine, &pairOnly.engine}) {
            engine->setKnownWordReach(-std::numeric_limits<float>::infinity());
        }
        const char* const first = "keyboardist";
        const char* const second = "timepiece";
        for (uint32_t i = 0; i < Engine::kDefaultLearnAfterUses; ++i) {
            taught.copy.commit("time", std::strlen("time"), first, std::strlen(first), nullptr, 0,
                                false, false, kPackLanguage, kPackLanguageLength);
            // The same word, with nothing before it.
            wordOnly.copy.commit("time", std::strlen("time"), nullptr, 0, nullptr, 0);
            // The pair's context has another word after it in both; the triple's only this one.
            for (WorkingCopyFixture* copy : {&taught.copy, &pairOnly.copy}) {
                copy->commit("noapte", std::strlen("noapte"), second, std::strlen(second), nullptr,
                             0, false, false, kPackLanguage, kPackLanguageLength);
            }
            taught.copy.commit("key", std::strlen("key"), second, std::strlen(second), first,
                                std::strlen(first), false, false, kPackLanguage,
                                kPackLanguageLength);
            pairOnly.copy.commit("key", std::strlen("key"), second, std::strlen(second), nullptr,
                                  0, false, false, kPackLanguage, kPackLanguageLength);
        }
        check(taught.scoreAfter("tim", "time", first) > wordOnly.scoreAfter("tim", "time", first),
              "a pair after a word with no count lifts its word");
        check(taught.rankOf("keyboardi", first) < 0,
              "and the word before is never offered as a completion");
        check(taught.scoreAfter("ke", "key", second, first) >
                  pairOnly.scoreAfter("ke", "key", second, first),
              "a triple whose words before have no count lifts its word past its pair");
    }

    section("a known word looked up again allocates nothing");
    {
        // Longer than a string holds without the heap.
        const char word[] = "pneumonoultramicroscopicsilicovolcanoconiosis";
        const size_t length = std::strlen(word);
        WordTable table;
        holdWords(table, {word});
        long lookups = 0;
        int32_t found = -1;
        {
            AllocationCount allocations;
            found = table.entryIndexFor(word, length);
            lookups = allocations.count();
        }
        check(found >= 0 && lookups == 0, "a known word is found without the heap");
    }

    section("a known word committed again, its learnable asked and its slice handed again, allocates nothing");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        // Longer than a string holds without the heap.
        const char word[] = "pneumonoultramicroscopicsilicovolcanoconiosis";
        const size_t length = std::strlen(word);
        loaded.copy.putWord(word, length, true);
        loaded.copy.putWord("acum", 4, true);
        const int32_t id = loaded.copy.idOf(word, length);
        const ContextSlice::Key key{0, loaded.copy.idOf("acum", 4), ContextSlice::kNone};
        const HandedSlice slice(key, 2, {{id, 2, 0}});
        bool learnable = false;
        const auto commitAgain = [&]() {
            loaded.copy.putWord(word, length, true);
            learnable = loaded.engine.learnable(word, length);
            return slice.into(loaded.engine);
        };
        commitAgain();
        long allocated = 0;
        bool handed = false;
        {
            AllocationCount allocations;
            handed = commitAgain();
            allocated = allocations.count();
        }
        check(handed && learnable && loaded.engine.slice().pairCount() == 1,
              "the word is put, counted learnable by its uses, and its context's slice handed");
        check(allocated == 0, "without the heap");
    }

    section("a request reads the working copy and never writes it, allocating nothing");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        for (uint32_t i = 0; i < Engine::kDefaultLearnAfterUses; ++i) {
            loaded.copy.commit("acum", 4, nullptr, 0, nullptr, 0);
            loaded.copy.commit("timp", 4, "acum", 4, nullptr, 0, false, false, kPackLanguage,
                               kPackLanguageLength);
            loaded.copy.commit("bun", 3, "timp", 4, "acum", 4, false, false, kPackLanguage,
                               kPackLanguageLength);
        }
        const ContextSlice::Key key = loaded.copy.push("timp", 4, "acum", 4, kPackLanguage,
                                                       kPackLanguageLength);
        const ContextSlice& slice = loaded.engine.slice();
        const auto held = [&slice]() {
            std::vector<uint64_t> values = {slice.pairTotal(), slice.tripleTotal(), slice.pairSeen(),
                                            slice.tripleSeen()};
            for (int i = 0; i < slice.pairCount() + slice.tripleCount(); ++i) {
                const ContextSlice::Link& link =
                    i < slice.pairCount() ? slice.pair(i) : slice.triple(i - slice.pairCount());
                values.insert(values.end(), {static_cast<uint64_t>(link.word), link.count,
                                             link.asserted, link.seen});
            }
            return values;
        };
        const std::vector<uint64_t> pushed = held();
        const uint32_t count = loaded.engine.personalWords().entryCount(
            static_cast<uint32_t>(loaded.copy.idOf("bun", 3)));
        Candidate out[Engine::kMaxCandidates];
        long allocated = 0;
        int found = 0;
        {
            AllocationCount allocations;
            for (const char* composing : {"", "b", "bu", "bun"}) {
                found = loaded.engine.suggest(composing, std::strlen(composing), "timp", 4, "acum", 4,
                                              out, Engine::kMaxCandidates, key);
            }
            allocated = allocations.count();
        }
        check(found > 0 && slice.tripleCount() == 1 && slice.pairCount() == 1,
              "the requests read a slice of one pair and one triple");
        check(allocated == 0, "and make no allocation");
        check(held() == pushed && slice.key().language == key.language &&
                  slice.key().context1 == key.context1 && slice.key().context2 == key.context2 &&
                  loaded.engine.personalWords().entryCount(
                      static_cast<uint32_t>(loaded.copy.idOf("bun", 3))) == count,
              "and leave the slice and the word table as they were handed");
    }

    section("a spelling keeps its marks, and only case is folded");
    {
        WordTable words;
        const std::vector<int32_t> held = holdWords(words, {"masă", "masa"});
        check(words.entryIndexFor("Masă", std::strlen("Masă")) == held[0],
              "a capital is the same spelling");
        check(words.entryIndexFor("masa", 4) == held[1] && held[1] != held[0],
              "a missing mark is another spelling, its own entry");

        uint32_t folded[8];
        const int length = foldUtf8("mas", 3, folded, 8);
        WordTable::Completion found[4];
        check(words.completions(folded, length, found, 4) == 2,
              "typed without marks, both spellings are reached");

    }

    section("a word is learnable once a pack knows it or the personal model holds it established");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        check(loaded.engine.learnable("time", 4), "a word the pack knows is learnable");
        check(!loaded.engine.learnable("zqxv", 4), "one it does not, never written, is not");
        for (uint32_t i = 0; i + 1 < uses; ++i) {
            loaded.copy.commit("zqxv", 4, nullptr, 0, nullptr, 0);
        }
        check(!loaded.engine.learnable("zqxv", 4), "nor one written once short of learn-after");
        loaded.copy.commit("zqxv", 4, nullptr, 0, nullptr, 0);
        check(loaded.engine.learnable("zqxv", 4), "written learn-after times, it is");
        loaded.copy.commit("zqxw", 4, nullptr, 0, nullptr, 0, false, true);
        check(loaded.engine.learnable("zqxw", 4), "and so is a word chosen on purpose once");

        for (uint32_t i = 0; i + 1 < uses; ++i) {
            loaded.copy.commit("zqxt", 4, nullptr, 0, nullptr, 0);
        }
        loaded.copy.commit("zqxt", 4, "time", 4, nullptr, 0, false, false, kPackLanguage,
                            kPackLanguageLength, false);
        check(!loaded.engine.learnable("zqxt", 4), "a word learned without its tally is not counted");
        loaded.copy.commit("zqxt", 4, nullptr, 0, nullptr, 0);
        check(loaded.engine.learnable("zqxt", 4), "and counts with it");

        Engine bare;
        check(bare.create(), "an engine with no pack is created");
        check(bare.learnable("zqxv", 4), "with no pack active, every word is learnable");
        bare.destroy();
    }

    section("a link chosen on purpose is offered whatever its count and share, and keeps its mark");
    {
        // "we" has twenty followers written six times each and one, "go", written once but
        // chosen on purpose; "home" follows "go" the same way.
        constexpr int kFollowers = 20;
        constexpr int kPairs = kFollowers + 2;
        constexpr int32_t kUses = 6;
        char followers[kFollowers][8] = {};
        const char* words[kFollowers + 3] = {"we", "go", "home"};
        size_t wordLengths[kFollowers + 3] = {2, 2, 4};
        int32_t wordCounts[kFollowers + 3] = {};
        for (int i = 0; i < kFollowers; ++i) {
            std::snprintf(followers[i], sizeof(followers[i]), "zq%02d", i);
            words[3 + i] = followers[i];
            wordLengths[3 + i] = std::strlen(followers[i]);
        }
        for (int i = 0; i < kFollowers + 3; ++i) {
            wordCounts[i] = kUses;
        }
        const char* languages[kPairs];
        size_t languageLengths[kPairs];
        const char* previous[kPairs];
        size_t previousLengths[kPairs];
        const char* next[kPairs];
        size_t nextLengths[kPairs];
        int32_t pairCounts[kPairs];
        int32_t chosen[kPairs] = {};
        for (int i = 0; i < kPairs; ++i) {
            languages[i] = "ro";
            languageLengths[i] = 2;
            previous[i] = i == kPairs - 1 ? "go" : "we";
            previousLengths[i] = 2;
            next[i] = i == 0 ? "go" : i == kPairs - 1 ? "home" : followers[i - 1];
            nextLengths[i] = std::strlen(next[i]);
            pairCounts[i] = i == 0 || i == kPairs - 1 ? 1 : kUses;
        }
        chosen[0] = 1;
        chosen[kPairs - 1] = 1;

        const auto load = [&](LoadedEngine& into, const int32_t* marks) {
            into.engine.setPhraseSuggestions(true);
            into.copy.loadWords(words, wordLengths, wordCounts, kFollowers + 3);
            into.copy.loadPairs(languages, languageLengths, previous, previousLengths, next,
                                nextLengths, pairCounts, kPairs, nullptr, marks);
        };
        LoadedEngine plain;
        check(plain.open(), "the engine loads");
        load(plain, nullptr);
        check(plain.rankOf("", followers[0], "we") >= 0, "an established follower is the next word");
        check(plain.rankOf("", "go", "we") < 0,
              "a pair written once among twenty established ones is not the next word");
        check(plain.rankOf("", "go home", "we") < 0, "nor a phrase");

        LoadedEngine marked;
        check(marked.open(), "the engine loads");
        load(marked, chosen);
        const int chosenAt = marked.rankOf("", "go", "we");
        check(chosenAt >= 0 && chosenAt < marked.rankOf("", followers[0], "we"),
              "chosen on purpose, it is offered as the next word, above every established follower");
        check(marked.rankOf("", "go home", "we") >= 0, "and as a phrase, with the chosen link after it");

        // "we" followed by the pack word "time" a hundred times, and by "go" once, chosen; "home"
        // after "go" once, chosen, then six times in six.
        const char* strongWords[4] = {"we", "go", "home", "time"};
        const size_t strongLengths[4] = {2, 2, 4, 4};
        const int32_t strongCounts[4] = {kUses, kUses, kUses, 100};
        const char* strongLanguages[3] = {"ro", "ro", "ro"};
        const size_t strongLanguageLengths[3] = {2, 2, 2};
        const char* strongPrevious[3] = {"we", "go", "we"};
        const size_t strongPreviousLengths[3] = {2, 2, 2};
        const char* strongNext[3] = {"go", "home", "time"};
        const size_t strongNextLengths[3] = {2, 4, 4};
        const int32_t strongChosen[3] = {1, 1, 0};
        const auto loadStrong = [&](LoadedEngine& into, int32_t homeUses) {
            const int32_t strongPairs[3] = {1, homeUses, 100};
            into.engine.setPhraseSuggestions(true);
            into.copy.loadWords(strongWords, strongLengths, strongCounts, 4);
            into.copy.loadPairs(strongLanguages, strongLanguageLengths, strongPrevious,
                                strongPreviousLengths, strongNext, strongNextLengths, strongPairs, 3,
                                nullptr, strongChosen);
        };
        LoadedEngine strong;
        check(strong.open(), "the engine loads");
        loadStrong(strong, 1);
        const int chosenOnce = strong.rankOf("", "go", "we");
        check(chosenOnce >= 0 && chosenOnce < strong.rankOf("", "time", "we"),
              "chosen at one use, it ranks above a pack word written a hundred times after the "
              "same context");
        const int chipOnce = strong.rankOf("", "go home", "we");
        check(chipOnce > chosenOnce && chipOnce < strong.rankOf("", "time", "we"),
              "its chip, both links chosen at one use, sits under it and above that word");
        LoadedEngine climbed;
        check(climbed.open(), "the engine loads");
        loadStrong(climbed, kUses);
        const int chipClimbed = climbed.rankOf("", "go home", "we");
        check(chipClimbed >= 0 && chipClimbed < climbed.rankOf("", "go", "we"),
              "and above it once its second word follows the first six times in six");

        // "nadia" added by hand and written once; "nadir" written five times.
        const char* addedWords[2] = {"nadia", "nadir"};
        const size_t addedLengths[2] = {5, 5};
        const int32_t addedCounts[2] = {1, 5};
        const int32_t addedChosen[2] = {1, 0};
        const int32_t addedByHand[2] = {1, 0};
        LoadedEngine added;
        check(added.open(), "the engine loads");
        added.copy.loadWords(addedWords, addedLengths, addedCounts, 2, nullptr, addedChosen,
                                   addedByHand);
        const int addedAt = added.rankOf("nad", "nadia");
        check(addedAt >= 0 && addedAt < added.rankOf("nad", "nadir"),
              "a word added by hand at one use completes before a word learned by use at five");

        // After "go": "home" chosen at one use, "hope" written six times; "ho" typed.
        const char* afterGoWords[3] = {"go", "home", "hope"};
        const size_t afterGoLengths[3] = {2, 4, 4};
        const int32_t afterGoCounts[3] = {kUses, kUses, kUses};
        const char* afterGoLanguages[2] = {"ro", "ro"};
        const size_t afterGoLanguageLengths[2] = {2, 2};
        const char* afterGoPrevious[2] = {"go", "go"};
        const size_t afterGoPreviousLengths[2] = {2, 2};
        const char* afterGoNext[2] = {"home", "hope"};
        const size_t afterGoNextLengths[2] = {4, 4};
        const int32_t afterGoPairs[2] = {1, kUses};
        const int32_t afterGoChosen[2] = {1, 0};
        LoadedEngine afterGo;
        check(afterGo.open(), "the engine loads");
        afterGo.copy.loadWords(afterGoWords, afterGoLengths, afterGoCounts, 3);
        afterGo.copy.loadPairs(afterGoLanguages, afterGoLanguageLengths, afterGoPrevious,
                                       afterGoPreviousLengths, afterGoNext, afterGoNextLengths,
                                       afterGoPairs, 2, nullptr, afterGoChosen);
        const int homeAt = afterGo.rankOf("ho", "home", "go");
        check(homeAt >= 0 && homeAt < afterGo.rankOf("ho", "hope", "go"),
              "with a prefix typed after the context, a word chosen there at one use ranks above "
              "one written six times after it");

        // "I see that" written three times; "see → you" chosen at one use; after "I see".
        LoadedEngine twoWords;
        check(twoWords.open(), "the engine loads");
        const char* seeWords[4] = {"I", "see", "that", "you"};
        const size_t seeLengths[4] = {1, 3, 4, 3};
        const int32_t seeCounts[4] = {kUses, kUses, kUses, kUses};
        const char* seeLanguages[1] = {"ro"};
        const size_t seeLanguageLengths[1] = {2};
        const char* seePrevious[1] = {"see"};
        const size_t seePreviousLengths[1] = {3};
        const char* seeNext[1] = {"you"};
        const size_t seeNextLengths[1] = {3};
        const int32_t seePairs[1] = {1};
        const int32_t seeChosen[1] = {1};
        twoWords.copy.loadWords(seeWords, seeLengths, seeCounts, 4);
        twoWords.copy.loadPairs(seeLanguages, seeLanguageLengths, seePrevious,
                                        seePreviousLengths, seeNext, seeNextLengths, seePairs, 1,
                                        nullptr, seeChosen);
        for (int i = 0; i < 3; ++i) {
            twoWords.copy.commit("that", 4, "see", 3, "I", 1, false, false, kPackLanguage,
                                  kPackLanguageLength);
        }
        const int youAt = twoWords.rankOf("", "you", "see", "I");
        const int thatAt = twoWords.rankOf("", "that", "see", "I");
        check(thatAt >= 0, "after two words, a triple learned after them is offered");
        check(youAt >= 0 && youAt < thatAt,
              "and a pair chosen on purpose after the last one is offered beside it, above it");
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
        const Candidate* none = nullptr;
        check(loaded.engine.corrections(&none) == 0, "and an empty list");

        // A word too long to search answers with nothing left by the request before it.
        const char* const typo = "thexx";
        loaded.engine.suggest(typo, std::strlen(typo), nullptr, 0, nullptr, 0, out,
                              Engine::kMaxCandidates);
        check(loaded.engine.corrections(&none) > 0, "a typo has corrections");
        const std::string overlong(Engine::kMaxComposing + 1, 'a');
        loaded.engine.suggest(overlong.c_str(), overlong.size(), nullptr, 0, nullptr, 0, out,
                              Engine::kMaxCandidates);
        check(loaded.engine.corrections(&none) == 0,
              "a word past kMaxComposing letters has no corrections");
        check(loaded.engine.bestCorrection() == nullptr, "nor a best correction");
        check(loaded.engine.decodedCorrection() == nullptr, "nor a decoded one");

        // The list: sorted, at most the respelling plus the heap, every entry resolving.
        loaded.engine.suggest("thexx", 5, nullptr, 0, nullptr, 0, out, Engine::kMaxCandidates);
        const Candidate* list = nullptr;
        const int count = loaded.engine.corrections(&list);
        check(count > 0 && count <= 5, "the list holds at most five candidates");
        bool sorted = true;
        bool resolves = true;
        for (int i = 0; i < count; ++i) {
            sorted = sorted && (i == 0 || list[i].score <= list[i - 1].score);
            uint32_t length = 0;
            resolves = resolves && loaded.engine.candidateText(list[i], &length) != nullptr;
        }
        check(sorted, "the list is in score order");
        check(resolves, "every entry resolves to text");
        check(loaded.engine.bestCorrection() != nullptr &&
                  loaded.engine.bestCorrection()->wordIndex == list[0].wordIndex,
              "the best correction is the list's first entry");
        check(loaded.corrections("thexx").find("thex/1/0 ") != std::string::npos,
              "a one-substitution candidate carries one edit and no letter past the typed ones");
        check(loaded.corrections("keyboar").find("keyboard/0/1 ") != std::string::npos,
              "a one-letter completion carries no edit and one letter past the typed ones");
        check(loaded.corrections("keybosr").find("keyboard/1/1 ") != std::string::npos,
              "an edit plus a letter past the typed ones carries one of each");
        check(loaded.slipsOnly("keubosrd", "keyboard"),
              "two neighbouring keys in place of typed ones are slips only");
        check(!loaded.slipsOnly("keybosr", "keyboard"),
              "a letter past the typed ones is not a slip");
        check(!loaded.slipsOnly("thexx", "thex"), "a discarded letter is not a slip");

        // A letter past the typed ones is priced below a deletion.
        const std::string ther = loaded.corrections("ther");
        const size_t there = ther.find("there/0/1 ");
        const size_t the = ther.find("the/1/0 ");
        check(there != std::string::npos,
              "a candidate with a letter past the typed ones reaches the list");
        check(the == std::string::npos || there < the,
              "and sits above the candidate reached by discarding a typed letter");
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
        loaded.copy.loadWords(words, lengths, counts, 3);

        check(loaded.rankOf("borderkeyso", "borderkeysonce") < 0,
              "a single sighting is not evidence enough to suggest");
        check(loaded.rankOf("borderkeystw", "borderkeystwice") < 0, "nor is a second");
        check(loaded.rankOf("borderkeysth", "borderkeysthrice") == 0,
              "a third use confirms it, and it is offered");

        // The number of uses is the setting's.
        loaded.engine.setLearnAfterUses(1);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "learned after one use, the first time counts");

        loaded.engine.setLearnAfterUses(9);
        check(loaded.rankOf("borderkeysth", "borderkeysthrice") < 0,
              "while learned after nine, three are not enough");
        loaded.engine.setLearnAfterUses(3);

        // Chosen on purpose once, and the count no longer matters, at any setting.
        const int32_t asserted[3] = {1, 0, 0};
        loaded.copy.loadWords(words, lengths, counts, 3, nullptr, asserted);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "a word chosen once is offered from then on");
        loaded.engine.setLearnAfterUses(9);
        check(loaded.rankOf("borderkeyso", "borderkeysonce") == 0,
              "however many uses the setting asks for");
        loaded.engine.setLearnAfterUses(3);
    }

    section("a learn-after setting past the largest asks for the largest");
    {
        LoadedEngine loaded;
        loaded.open();
        const char* words[1] = {"borderkeysmost"};
        const size_t lengths[1] = {std::strlen(words[0])};
        const int32_t counts[1] = {static_cast<int32_t>(Engine::kMaxLearnAfterUses)};
        loaded.copy.loadWords(words, lengths, counts, 1);
        loaded.engine.setLearnAfterUses(static_cast<int>(Engine::kMaxLearnAfterUses) + 1);
        check(loaded.rankOf("borderkeysmos", "borderkeysmost") == 0,
              "a word used the largest number of times is learned");
    }

    section("a word typed past does not vouch for itself");
    {
        // knownSpelling answers from the personal dictionary only for an established word.
        LoadedEngine loaded;
        loaded.open();
        char out[64];
        loaded.copy.commit("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        loaded.copy.commit("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        check(loaded.engine.knownSpelling("borderkeystypo", 14, out, sizeof(out)) == 0,
              "written twice, it is not a word the dictionaries know");
        loaded.copy.commit("borderkeystypo", 14, nullptr, 0, nullptr, 0);
        check(loaded.engine.knownSpelling("borderkeystypo", 14, out, sizeof(out)) == 14,
              "written a third time, it is");

        loaded.copy.commit("borderkeysmine", 14, nullptr, 0, nullptr, 0, false, true);
        check(loaded.engine.knownSpelling("borderkeysmine", 14, out, sizeof(out)) == 14,
              "chosen once, it is at once");

        // A pair answers to the same number of uses, its word learned first.
        loaded.copy.commit("keyboard", 8, nullptr, 0, nullptr, 0);
        for (uint32_t i = 0; i < Engine::kDefaultLearnAfterUses; ++i) {
            loaded.copy.commit("borderkeysnext", 14, nullptr, 0, nullptr, 0);
        }
        loaded.copy.commit("borderkeysnext", 14, "keyboard", 8, nullptr, 0, false, false,
                            kPackLanguage, kPackLanguageLength);
        check(loaded.rankOf("", "borderkeysnext", "keyboard") < 0,
              "a word written once after another is not predicted after it");
        loaded.copy.commit("borderkeysnext", 14, "keyboard", 8, nullptr, 0, false, true,
                            kPackLanguage, kPackLanguageLength);
        check(loaded.rankOf("", "borderkeysnext", "keyboard") < 0,
              "nor once chosen, the pair written twice");
        loaded.copy.commit("borderkeysnext", 14, "keyboard", 8, nullptr, 0, false, false,
                            kPackLanguage, kPackLanguageLength);
        check(loaded.rankOf("", "borderkeysnext", "keyboard") >= 0,
              "and is, the pair written a third time");
    }

    section("a private field does not consult the personal dictionary");
    {
        // Nothing learned is offered into a private field; the model stays loaded.
        LoadedEngine loaded;
        loaded.open();
        const char* words[1] = {"borderkeysword"};
        const size_t lengths[1] = {14};
        const int32_t counts[1] = {40};
        loaded.copy.loadWords(words, lengths, counts, 1);
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
        // learned with the one before it as context, once each word is learned.
        const char* phrase[] = {"vreau", "sa", "ma", "duc", "la"};
        for (const char* word : phrase) {
            for (uint32_t i = 0; i < Engine::kDefaultLearnAfterUses; ++i) {
                loaded.copy.commit(word, std::strlen(word), nullptr, 0, nullptr, 0);
            }
        }
        for (int round = 0; round < 4; ++round) {
            const char* previous = nullptr;
            for (const char* word : phrase) {
                loaded.copy.commit(word, std::strlen(word), previous,
                                    previous != nullptr ? std::strlen(previous) : 0, nullptr, 0,
                                    false, false, kPackLanguage, kPackLanguageLength);
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

    section("pairs are read in the store the request names");
    {
        LoadedEngine loaded;
        loaded.open();
        const char* phrase[] = {"vreau", "sa", "ma"};
        for (const char* word : phrase) {
            for (uint32_t i = 0; i < Engine::kDefaultLearnAfterUses; ++i) {
                loaded.copy.commit(word, std::strlen(word), nullptr, 0, nullptr, 0);
            }
        }
        const char other[] = "en";
        for (int round = 0; round < 4; ++round) {
            const char* previous = nullptr;
            for (const char* word : phrase) {
                loaded.copy.commit(word, std::strlen(word), previous,
                                    previous != nullptr ? std::strlen(previous) : 0, nullptr, 0,
                                    false, false, other, std::strlen(other));
                previous = word;
            }
        }
        check(loaded.rankOf("", "sa", "vreau") != 0,
              "pairs learned under another language are not read by a request naming the pack's");
        check(loaded.rankOf("", "sa", "vreau", nullptr, other) == 0,
              "a request naming their language reads them");
        check(!loaded.copy.commit("sa", 2, "vreau", 5, nullptr, 0).pair,
              "with no language named, no pair is learned");
    }

    section("an answer leans on the pack the engine answers in");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        check(loaded.openSecondPack("en-US"), "a second pack loads under another tag");
        const char* tags[] = {"ro-RO", "en-US"};
        const float lighter = 1.0f;
        const float weights[] = {lighter, lighter + lighter};
        const int packs = static_cast<int>(sizeof(tags) / sizeof(tags[0]));
        loaded.engine.setActiveLanguages(tags, weights, packs);
        const int englishSlot = 1;
        const auto leansOn = [&loaded](const char* tag) {
            check(loaded.rankOf("keyboar", "keyboard") >= 0, "the request is answered");
            const char* const leaned = loaded.engine.packTag(loaded.engine.answerPack());
            return leaned != nullptr && std::strcmp(leaned, tag) == 0;
        };

        check(leansOn("en-US"), "with nothing decided, the heavier pack");
        loaded.engine.setPreferredLanguage("ro-RO");
        check(leansOn("ro-RO"), "a preferred language before the heavier pack");
        check(loaded.engine.answerAs(englishSlot), "the lighter pack's slot can be answered as");
        check(leansOn("en-US"), "the pack answerAs names before the preferred one");
        check(loaded.engine.answerAs(Engine::kNoPack), "kNoPack returns the requests to the preference");

        {
            const Engine::AnsweringScope answering(loaded.engine, englishSlot);
            check(answering.held(), "a scope takes an open, active pack");
            check(leansOn("en-US"), "inside it, that pack");
        }
        check(leansOn("ro-RO"), "after it, the preference's again");
        {
            const Engine::AnsweringScope refused(loaded.engine, packs);
            check(!refused.held(), "a scope over the slot past the loaded packs holds nothing");
            check(leansOn("ro-RO"), "and the requests lean as before");
        }

        loaded.engine.setActiveLanguages(nullptr, nullptr, 0);
        check(loaded.engine.answerPack() == Engine::kNoPack,
              "with no pack active, an answer leans on none");
    }

    section("a learned chain leads the strip");
    {
        LoadedEngine loaded;
        loaded.open();
        LoadedEngine unwritten;
        unwritten.open();

        // Two phrases from the same word: one written as many times as learning asks, in both
        // engines, and one written once, in the first only. Both words of a phrase are learned.
        const auto write = [](LoadedEngine& into, const char* first, const char* second) {
            into.copy.commit(first, std::strlen(first), nullptr, 0, nullptr, 0);
            into.copy.commit(second, std::strlen(second), first, std::strlen(first),
                              nullptr, 0, false, false, kPackLanguage, kPackLanguageLength);
        };
        for (uint32_t round = 0; round < Engine::kDefaultLearnAfterUses; ++round) {
            write(loaded, "the", "them");
            write(unwritten, "the", "them");
        }
        write(loaded, "the", "test");

        check(loaded.rankOf("", "them", "the") == 0,
              "the phrase written as many times as learning asks leads");
        check(unwritten.rankOf("", "them", "the") == 0, "as it does where the other is unwritten");
        check(loaded.rankOf("", "test", "the") == unwritten.rankOf("", "test", "the"),
              "and the one written once is not learned: it ranks where it does unwritten");

        // The dictionary's own candidates are not thrown away; they sit behind the personal
        // ones rather than being replaced by them.
        check(loaded.rankOf("", "time", "the") > 0,
              "a word the pack predicts is still in the list");
    }

    section("a word the dictionary has gains nothing until it is learned");
    {
        // Typed fewer times than the setting asks, a dictionary word scores as if never typed;
        // learned, it climbs one curve whatever the setting, from every use counted.
        const auto scoreAfter = [](int uses, int learnAfter) {
            LoadedEngine loaded;
            loaded.open();
            loaded.engine.setLearnAfterUses(learnAfter);
            for (int i = 0; i < uses; ++i) {
                loaded.copy.commit("testing", 7, nullptr, 0, nullptr, 0);
            }
            return loaded.scoreOf("test", "testing");
        };
        const float untyped = scoreAfter(0, 3);
        check(untyped != 0.0f, "the dictionary offers \"testing\" for \"test\" to begin with");
        check(scoreAfter(2, 3) == untyped, "two uses of three weigh nothing");
        check(scoreAfter(3, 3) > untyped, "the third learns it, and it gains");
        check(scoreAfter(8, 9) == untyped, "eight of nine weigh nothing");
        check(scoreAfter(9, 9) == scoreAfter(9, 1),
              "learned, the climb is the same whatever the setting asked for");
        check(scoreAfter(10, 1) > scoreAfter(3, 1), "and each further use adds");

        // The word actually typed still leads at every setting; the learned one follows it.
        LoadedEngine loaded;
        loaded.open();
        loaded.engine.setLearnAfterUses(1);
        for (int i = 0; i < 3; ++i) {
            loaded.copy.commit("testing", 7, nullptr, 0, nullptr, 0, false, true);
        }
        check(loaded.rankOf("test", "test") == 0, "the word typed exactly is first");
        check(loaded.rankOf("test", "testing") == 1, "the learned word follows it, not replaces it");
    }

    section("a learned context adds its share, the triple before the pair");
    {
        WordTable words;
        const std::vector<int32_t> held = holdWords(words, {"the", "border", "key", "theme", "there"}, 0);
        const int32_t the = held[0];
        const int32_t border = held[1];
        const int32_t key = held[2];
        const int32_t theme = held[3];
        const int32_t there = held[4];
        const int32_t uses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        const int32_t more = uses + 1;
        const int32_t keyTotal = uses + more;
        constexpr int32_t kLanguage = 0;
        const ContextSlice::Key afterTwo{kLanguage, key, the};
        const ContextSlice::Key afterOne{kLanguage, key, ContextSlice::kNone};
        const auto bonusOf = [](const PersonalScore& personal, int32_t word) {
            return personal.contextBonus(static_cast<uint32_t>(word));
        };

        ContextSlice slice;
        PersonalScore personal(words, slice, static_cast<uint32_t>(uses));
        HandedSlice(afterTwo, keyTotal, {{there, more, 0}, {theme, uses, 0}}, uses, {{theme, uses, 0}})
            .into(slice);
        personal.resolve(afterTwo);
        check(bonusOf(personal, theme) == personalShareOf(uses, uses),
              "after two words, a word takes its triple's share of them");
        check(bonusOf(personal, there) == personalShareOf(more, keyTotal),
              "a word with no triple there takes its pair's share of the last word");
        check(bonusOf(personal, border) == 0.0f, "a word never written there takes nothing");
        check(personal.ceiling() == PersonalScore::kMaxUserBoost +
                                        std::max(personalShareOf(uses, uses), personalShareOf(more, keyTotal)),
              "the ceiling is the word boost's cap and the largest share there");
        check(personal.ceiling() <= PersonalScore::kMaxPersonalScore,
              "within the most the personal score adds");

        HandedSlice(afterOne, keyTotal, {{there, more, 0}, {theme, uses, 0}}).into(slice);
        personal.resolve(afterOne);
        check(bonusOf(personal, theme) == personalShareOf(uses, keyTotal),
              "after one word, a word takes its pair's share");

        HandedSlice(afterTwo, keyTotal, {{there, more, 0}, {theme, uses, 0}}, uses, {{theme, uses, 0}})
            .into(slice);
        personal.setLearnAfterUses(static_cast<uint32_t>(more));
        personal.resolve(afterTwo);
        check(bonusOf(personal, theme) == 0.0f,
              "a triple below learn-after gives way to the pair, and a pair below it to nothing");
        check(bonusOf(personal, there) == personalShareOf(more, keyTotal),
              "while a pair that reached it still counts");
        check(personal.ceiling() == PersonalScore::kMaxUserBoost + personalShareOf(more, keyTotal),
              "and the ceiling follows what counts");

        personal.setLearnAfterUses(static_cast<uint32_t>(more + 1));
        personal.resolve(afterTwo);
        check(personal.ceiling() == PersonalScore::kMaxUserBoost,
              "a context with nothing learned leaves the ceiling at the word boost's cap");

        // After "key": "theme" chosen on purpose at one use, "there" written four times.
        HandedSlice(afterOne, 5, {{theme, 1, 1}, {there, 4, 0}}).into(slice);
        personal.setLearnAfterUses(static_cast<uint32_t>(uses));
        personal.resolve(afterOne);
        check(bonusOf(personal, theme) == PersonalScore::kMaxUserBigramBoost,
              "a word chosen on purpose after the context takes the whole bonus at one use");
        check(bonusOf(personal, there) == personalShareOf(4, 5),
              "a word learned there takes its share");
        check(personal.ceiling() == PersonalScore::kMaxUserBoost + PersonalScore::kMaxUserBigramBoost,
              "and the ceiling is the most the two add");

        personal.resolve(ContextSlice::Key{});
        check(bonusOf(personal, theme) == 0.0f && personal.ceiling() == PersonalScore::kMaxUserBoost,
              "with no context, nothing");
        personal.resolve(afterOne);
        personal.setEnabled(false);
        personal.resolve(afterOne);
        check(bonusOf(personal, theme) == 0.0f, "nor with the personal model off");
    }

    section("the context bonus reads every link of the slice");
    {
        // More pairs after one word than a context keeps, the most used first.
        constexpr int kLinks = 40;
        std::vector<std::string> spelled{"context"};
        for (int i = 0; i < kLinks; ++i) {
            spelled.push_back("w" + std::to_string(i));
        }
        std::vector<const char*> spellings;
        for (const std::string& spelling : spelled) {
            spellings.push_back(spelling.c_str());
        }
        WordTable words;
        const std::vector<int32_t> held = holdWords(words, spellings, 0);
        const int32_t uses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        std::vector<HandedLink> links;
        int32_t total = 0;
        for (int i = 0; i < kLinks; ++i) {
            const int32_t count = uses + kLinks - i;
            links.push_back(HandedLink{held[static_cast<size_t>(i) + 1], count, 0});
            total += count;
        }
        const ContextSlice::Key key{0, held[0], ContextSlice::kNone};
        ContextSlice slice;
        check(HandedSlice(key, total, links).into(slice) && slice.pairCount() == kLinks,
              "the slice holds every link handed");
        PersonalScore personal(words, slice, static_cast<uint32_t>(uses));
        personal.resolve(key);
        bool every = true;
        for (const HandedLink& link : links) {
            every = every && personal.contextBonus(static_cast<uint32_t>(link.word)) ==
                                 personalShareOf(static_cast<uint32_t>(link.count),
                                                 static_cast<uint32_t>(total));
        }
        check(every, "each link, the last handed too, lifts its word by its share");
        check(personal.ceiling() == PersonalScore::kMaxUserBoost +
                                        personalShareOf(static_cast<uint32_t>(links[0].count),
                                                        static_cast<uint32_t>(total)),
              "and the ceiling is the first link's share");

        // A context holding a whole store's pairs, the most used first.
        constexpr int kFullStore = 10000;
        WordTable fullWords;
        std::vector<std::string> fullSpelled{"context"};
        for (int i = 0; i < kFullStore; ++i) {
            fullSpelled.push_back("f" + std::to_string(i));
        }
        std::vector<const char*> fullSpellings;
        for (const std::string& spelling : fullSpelled) {
            fullSpellings.push_back(spelling.c_str());
        }
        const std::vector<int32_t> fullHeld = holdWords(fullWords, fullSpellings, 0);
        const int32_t most = uses + 1;
        std::vector<HandedLink> fullLinks;
        int32_t fullTotal = 0;
        for (int i = 0; i < kFullStore; ++i) {
            const int32_t count = i == 0 ? most : 1 + i % most;
            fullLinks.push_back(HandedLink{fullHeld[static_cast<size_t>(i) + 1], count, 0});
            fullTotal += count;
        }
        const ContextSlice::Key fullKey{0, fullHeld[0], ContextSlice::kNone};
        ContextSlice fullSlice;
        HandedSlice(fullKey, fullTotal, fullLinks).into(fullSlice);
        PersonalScore full(fullWords, fullSlice, static_cast<uint32_t>(uses));
        full.resolve(fullKey);
        const float largest =
            personalShareOf(static_cast<uint32_t>(most), static_cast<uint32_t>(fullTotal));
        check(full.ceiling() == PersonalScore::kMaxUserBoost + largest,
              "a context holding a whole store's pairs keeps the cap and its most used pair's share");
        bool bounded = true;
        for (const HandedLink& link : fullLinks) {
            bounded = bounded && full.contextBonus(static_cast<uint32_t>(link.word)) <= largest;
        }
        check(bounded, "and no word's share passes it");
    }

    section("a slice for another context lends nothing");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        const char* const words[] = {"cheie", "border", "timp"};
        const size_t lengths[] = {5, 6, 4};
        const int32_t counts[] = {1, 1, static_cast<int32_t>(Engine::kDefaultLearnAfterUses)};
        loaded.copy.loadWords(words, lengths, counts, 3);
        const int32_t cheie = loaded.copy.idOf("cheie", 5);
        const int32_t border = loaded.copy.idOf("border", 6);
        const int32_t timp = loaded.copy.idOf("timp", 4);
        const int32_t uses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        constexpr int32_t kLanguage = 0;
        const ContextSlice::Key afterCheie{kLanguage, cheie, ContextSlice::kNone};
        check(HandedSlice(afterCheie, uses, {{timp, uses, 0}}).into(loaded.engine),
              "the slice of the context after one word is handed");
        const auto scoreOf = [&loaded](const char* previous, const ContextSlice::Key& key) {
            Candidate out[Engine::kMaxCandidates];
            const int found = loaded.engine.suggest("tim", 3, previous, std::strlen(previous), nullptr,
                                                    0, out, Engine::kMaxCandidates, key);
            return loaded.scoreIn(out, found, "timp");
        };
        const float none = scoreOf("cheie", ContextSlice::Key{});
        check(none != 0.0f, "the word is offered with no context");
        checkRise(scoreOf("cheie", afterCheie), none, personalShareOf(uses, uses),
                  "a request naming the slice's context reads its link");
        check(scoreOf("border", ContextSlice::Key{kLanguage, border, ContextSlice::kNone}) ==
                  scoreOf("border", ContextSlice::Key{}),
              "one naming another word before reads nothing");
        check(scoreOf("cheie", ContextSlice::Key{kLanguage + 1, cheie, ContextSlice::kNone}) == none,
              "nor one naming another language");
        check(scoreOf("cheie", ContextSlice::Key{kLanguage, cheie, border}) == none,
              "nor one naming a word two before the slice does not");
        loaded.copy.loadWords(words, lengths, counts, 3);
        check(loaded.engine.slice().pairCount() == 0 && scoreOf("cheie", afterCheie) == none,
              "and a load of the words empties the slice, whose ids named the words before");
        HandedSlice disagreeing(afterCheie, uses, {{timp, uses, 0}});
        disagreeing.links.pop_back();
        check(!disagreeing.into(loaded.engine) && loaded.engine.slice().pairCount() == 0 &&
                  scoreOf("cheie", afterCheie) == none,
              "a slice whose arrays disagree is refused, leaving none");
        HandedSlice longer(afterCheie, uses, {{timp, uses, 0}});
        longer.links.push_back(uses);
        check(!longer.into(loaded.engine) && loaded.engine.slice().pairCount() == 0,
              "and so is one holding more links than its header counts");
    }

    section("successors are offered in the slice's order");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        loaded.engine.setPhraseSuggestions(true);
        // Nine followers of "cheie" no pack spells, the last one handed the most used.
        const char* const followers[] = {"zqxa", "zqxb", "zqxc", "zqxd", "zqxe", "zqxf", "zqxg",
                                         "zqxh", "zqxi"};
        constexpr int kFollowers = static_cast<int>(sizeof(followers) / sizeof(followers[0]));
        std::vector<const char*> words{"cheie"};
        std::vector<size_t> lengths{5};
        std::vector<int32_t> counts{1};
        for (const char* follower : followers) {
            words.push_back(follower);
            lengths.push_back(std::strlen(follower));
            counts.push_back(static_cast<int32_t>(Engine::kDefaultLearnAfterUses));
        }
        loaded.copy.loadWords(words.data(), lengths.data(), counts.data(),
                              static_cast<int>(words.size()));
        const int32_t uses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        std::vector<HandedLink> links;
        int32_t total = 0;
        for (int i = 0; i < kFollowers; ++i) {
            const int32_t count = i == kFollowers - 1 ? 10 * uses : uses;
            links.push_back(HandedLink{loaded.copy.idOf(followers[i], std::strlen(followers[i])),
                                       count, 0});
            total += count;
        }
        const ContextSlice::Key key{0, loaded.copy.idOf("cheie", 5), ContextSlice::kNone};
        HandedSlice(key, total, links).into(loaded.engine);
        Candidate out[Engine::kMaxCandidates];
        const int found = loaded.engine.suggest("", 0, "cheie", 5, nullptr, 0, out,
                                                Engine::kMaxCandidates, key);
        int offered = 0;
        for (int i = 0; i + 1 < kFollowers; ++i) {
            offered += loaded.scoreIn(out, found, followers[i]) != 0.0f ? 1 : 0;
        }
        check(offered == kFollowers - 1, "the first eight links handed are offered as the next word");
        check(loaded.scoreIn(out, found, followers[kFollowers - 1]) == 0.0f,
              "the ninth is not, though handed the most used");
    }

    section("a phrase hop reads the handed second link");
    {
        LoadedEngine loaded;
        check(loaded.open(), "the engine loads");
        loaded.engine.setPhraseSuggestions(true);
        const char* const words[] = {"cheie", "acum", "timp", "bun"};
        const size_t lengths[] = {5, 4, 4, 3};
        const int32_t uses = static_cast<int32_t>(Engine::kDefaultLearnAfterUses);
        const int32_t counts[] = {uses, uses, uses, uses};
        loaded.copy.loadWords(words, lengths, counts, 4);
        const int32_t acum = loaded.copy.idOf("acum", 4);
        const int32_t timp = loaded.copy.idOf("timp", 4);
        const int32_t bun = loaded.copy.idOf("bun", 3);
        const ContextSlice::Key key{0, loaded.copy.idOf("cheie", 5), ContextSlice::kNone};
        const int32_t strong = 2 * PersonalScore::kPhraseUsesPerWordUses * uses;
        const auto phrases = [&loaded, &key](HandedSlice slice) {
            slice.into(loaded.engine);
            Candidate out[Engine::kMaxCandidates];
            const int found = loaded.engine.suggest("", 0, "cheie", 5, nullptr, 0, out,
                                                    Engine::kMaxCandidates, key);
            return std::make_pair(loaded.scoreIn(out, found, "acum timp"),
                                  loaded.scoreIn(out, found, "acum bun"));
        };
        const auto [timpAfter, bunAfter] =
            phrases(HandedSlice(key, strong, {{acum, strong, 0}}).hop(timp, strong, 0, strong));
        check(timpAfter != 0.0f && bunAfter == 0.0f,
              "the phrase is the first link and the link handed after its word");
        const auto [timpElsewhere, bunHanded] =
            phrases(HandedSlice(key, strong, {{acum, strong, 0}}).hop(bun, strong, 0, strong));
        check(timpElsewhere == 0.0f && bunHanded != 0.0f, "whichever link is handed there");
        const auto [noTimp, noBun] =
            phrases(HandedSlice(key, strong, {{acum, strong, 0}}).hop(ContextSlice::kNone, 0, 0, 0));
        check(noTimp == 0.0f && noBun == 0.0f, "and a hop naming none makes no phrase");
        const auto [unshared, unsharedBun] =
            phrases(HandedSlice(key, strong, {{acum, strong, 0}}).hop(timp, strong, 0, 100 * strong));
        check(unshared == 0.0f && unsharedBun == 0.0f,
              "nor one whose link holds too small a share of the total handed with it");
    }

    section("an empty personal model answers as one switched off");
    {
        LoadedEngine empty;
        empty.open();
        LoadedEngine off;
        off.open();
        off.engine.setPersonalModelEnabled(false);
        const char* const requests[][3] = {
            {"th", nullptr, nullptr}, {"th", "key", nullptr}, {"thene", "key", "the"},
            {"keyboarf", nullptr, nullptr}, {"", "the", nullptr}, {"", nullptr, nullptr},
        };
        bool same = true;
        for (const auto& request : requests) {
            if (empty.answer(request[0], nullptr, nullptr, request[1], request[2]) !=
                off.answer(request[0], nullptr, nullptr, request[1], request[2])) {
                same = false;
            }
        }
        check(same, "for prefixes, slips and an empty prefix, with and without words before");
    }

    section("a learned pair lifts its word by its share, and only that word");
    {
        // One word learned after one context in one engine, and after another in the other.
        LoadedEngine learned;
        learned.open();
        LoadedEngine elsewhere;
        elsewhere.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        learned.learnAfter("key", "time", uses);
        elsewhere.learnAfter("border", "time", uses);
        const float share = personalShareOf(uses, uses);

        checkRise(learned.scoreAfter("tim", "time", "key"), elsewhere.scoreAfter("tim", "time", "key"),
                  share, "on the strip, a word rises by its share after the word before it");
        checkRise(elsewhere.scoreAfter("tim", "time", "border"),
                  learned.scoreAfter("tim", "time", "border"), share, "and only after that word");
        const float other = learned.scoreAfter("tim", "timp", "key");
        check(other != 0.0f, "another word is offered for the same letters");
        check(other == elsewhere.scoreAfter("tim", "timp", "key"), "no other word moves");
        check(learned.rankOf("t", "time", "key") == 0 && elsewhere.rankOf("t", "the", "key") == 0,
              "enough to lead the strip where a commoner word led");

        const float corrected = learned.correctionScoreAfter("tine", "time", "key");
        check(corrected != 0.0f, "autocorrect's list holds the word for a slip");
        checkRise(corrected, elsewhere.correctionScoreAfter("tine", "time", "key"), share,
                  "where it rises by the same share");
        float learnedBest = 0.0f;
        float elsewhereBest = 0.0f;
        check(learned.bestAfter("tine", "key", &learnedBest) == "time" &&
                  elsewhere.bestAfter("tine", "key", &elsewhereBest) == "time",
              "autocorrect takes it");
        checkRise(learnedBest, elsewhereBest, share, "at a score raised by the share");
    }

    section("a dropped pair and triple no longer lift their word");
    {
        // One word learned after one context in one engine, and after another in the other.
        LoadedEngine learned;
        learned.open();
        LoadedEngine elsewhere;
        elsewhere.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        const auto learnTriple = [](LoadedEngine& loaded, const char* previous2,
                                    const char* previous1, const char* word) {
            for (uint32_t i = 0; i < uses; ++i) {
                loaded.copy.commit(previous2, std::strlen(previous2), nullptr, 0, nullptr, 0);
                loaded.copy.commit(previous1, std::strlen(previous1), previous2,
                                    std::strlen(previous2), nullptr, 0, false, false,
                                    kPackLanguage, kPackLanguageLength);
                loaded.copy.commit(word, std::strlen(word), previous1, std::strlen(previous1),
                                    previous2, std::strlen(previous2), false, false, kPackLanguage,
                                    kPackLanguageLength);
            }
        };
        learnTriple(learned, "the", "key", "time");
        learnTriple(elsewhere, "the", "border", "time");
        check(learned.scoreAfter("tim", "time", "key", "the") >
                  elsewhere.scoreAfter("tim", "time", "key", "the"),
              "a learned pair and triple lift their word");

        const char* languages[] = {kPackLanguage};
        const size_t languageLengths[] = {kPackLanguageLength};
        const char* before2[] = {"the"};
        const size_t before2Lengths[] = {3};
        const char* before1[] = {"key"};
        const size_t before1Lengths[] = {3};
        const char* after[] = {"time"};
        const size_t afterLengths[] = {4};
        learned.copy.dropTriples(languages, languageLengths, before2, before2Lengths, before1,
                                       before1Lengths, after, afterLengths, 1);
        check(learned.scoreAfter("tim", "time", "key", "the") >
                  elsewhere.scoreAfter("tim", "time", "key", "the"),
              "with its triple dropped, its pair still lifts it");
        learned.copy.dropPairs(languages, languageLengths, before1, before1Lengths, after,
                                     afterLengths, 1);
        check(learned.scoreAfter("tim", "time", "key", "the") ==
                  elsewhere.scoreAfter("tim", "time", "key", "the"),
              "with its pair dropped too, nothing does");
    }

    section("a pair below learn-after lifts nothing until the setting reaches it");
    {
        LoadedEngine learned;
        learned.open();
        LoadedEngine elsewhere;
        elsewhere.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses - 1;
        learned.learnAfter("key", "time", uses);
        elsewhere.learnAfter("border", "time", uses);
        const auto lift = [&learned, &elsewhere]() {
            return learned.scoreAfter("tim", "time", "key") -
                   elsewhere.scoreAfter("tim", "time", "key");
        };
        const auto learnAfterUses = [&learned, &elsewhere](uint32_t setting) {
            learned.engine.setLearnAfterUses(setting);
            elsewhere.engine.setLearnAfterUses(setting);
        };
        check(lift() == 0.0f, "one use short of the setting, a pair weighs nothing");
        learnAfterUses(uses);
        checkRise(learned.scoreAfter("tim", "time", "key"), elsewhere.scoreAfter("tim", "time", "key"),
                  personalShareOf(uses, uses), "with the setting lowered to its uses, it lifts its word");
        learnAfterUses(Engine::kDefaultLearnAfterUses);
        check(lift() == 0.0f, "and raised again, it weighs nothing again");
    }

    section("learned sentence openers lift a prefix");
    {
        LoadedEngine opener;
        opener.open();
        LoadedEngine inside;
        inside.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        for (uint32_t i = 0; i < uses; ++i) {
            opener.copy.commit("key", 3, WorkingCopyFixture::kSentenceStart,
                                WorkingCopyFixture::kSentenceStartLength, nullptr, 0, false, false,
                                kPackLanguage, kPackLanguageLength);
        }
        inside.learnAfter("border", "key", uses);
        checkRise(opener.scoreAfter("ke", "key", nullptr), inside.scoreAfter("ke", "key", nullptr),
                  personalShareOf(uses, uses),
                  "a word this person opens sentences with rises by its share at a sentence start");
        const float insideSentence = opener.scoreAfter("ke", "key", "the");
        check(insideSentence != 0.0f, "the word is offered inside a sentence too");
        check(insideSentence == inside.scoreAfter("ke", "key", "the"), "and not inside a sentence");

        Engine::ScoreParts parts;
        Engine::ScoreParts unlearned;
        LoadedEngine plain;
        plain.open();
        check(opener.copy.explainScore("ke", 2, "key", 3, &parts, kPackLanguage,
                                         kPackLanguageLength) &&
                  plain.copy.explainScore("ke", 2, "key", 3, &unlearned, kPackLanguage,
                                            kPackLanguageLength),
              "the score of an opener is explained");
        check(parts.context == personalShareOf(uses, uses),
              "its context part is its share at a sentence start");
        check(parts.personal == PersonalScore::wordBoostForCount(uses) + parts.context,
              "its personal part adds its word boost");
        check(parts.rest == unlearned.rest, "and the rest is as if nothing were learned");
    }

    section("the tap decoder reads a learned context");
    {
        LoadedEngine learned;
        learned.open();
        LoadedEngine elsewhere;
        elsewhere.open();
        LoadedEngine plain;
        plain.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        learned.learnAfter("the", "keyboard", uses);
        elsewhere.learnAfter("border", "keyboard", uses);
        const char typed[] = "jeubpard";
        const char meant[] = "keyboard";
        float xs[8];
        float ys[8];
        learned.leanTaps(typed, meant, 0.f, xs, ys);

        float learnedMargin = 0.f;
        float elsewhereMargin = 0.f;
        check(learned.decoded(typed, xs, ys, nullptr, &learnedMargin, "the") == meant &&
                  learned.stands(meant),
              "after the word it was learned after, the decoder takes the word and it stands");
        elsewhere.decoded(typed, xs, ys, nullptr, &elsewhereMargin, "the");
        checkRise(learnedMargin, elsewhereMargin, personalShareOf(uses, uses), "its margin rising by its share");

        learned.engine.setPersonalModelEnabled(false);
        float offMargin = 0.f;
        float plainMargin = 0.f;
        learned.decoded(typed, xs, ys, nullptr, &offMargin, "the");
        const bool offStands = learned.stands(meant);
        plain.decoded(typed, xs, ys, nullptr, &plainMargin, "the");
        check(offMargin == plainMargin && offStands == plain.stands(meant),
              "with the personal model off, it reads as if nothing were learned");
    }

    section("a case form's links are its word's, an accent form's are its own");
    {
        LoadedEngine learned;
        learned.open();
        LoadedEngine elsewhere;
        elsewhere.open();
        const uint32_t uses = Engine::kDefaultLearnAfterUses;
        learned.learnAfter("key", "Masă", uses);
        elsewhere.learnAfter("border", "Masă", uses);
        // The spelling without the mark, learned as a word alone in both, so both offer it.
        const char unmarked[] = "masa";
        for (uint32_t i = 0; i < uses; ++i) {
            learned.copy.commit(unmarked, std::strlen(unmarked), nullptr, 0, nullptr, 0);
            elsewhere.copy.commit(unmarked, std::strlen(unmarked), nullptr, 0, nullptr, 0);
        }
        checkRise(learned.scoreAfter("mas", "masă", "key"), elsewhere.scoreAfter("mas", "masă", "key"),
                  personalShareOf(uses, uses), "a pair learned capitalised lifts the word");
        const float withoutMark = learned.scoreAfter("mas", unmarked, "key");
        check(withoutMark != 0.0f, "its spelling without the mark is offered too");
        check(withoutMark == elsewhere.scoreAfter("mas", unmarked, "key"), "and not its spelling without the mark");
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
        loaded.copy.commit("Border", 6, nullptr, 0, nullptr, 0);
        loaded.copy.commit("Border", 6, nullptr, 0, nullptr, 0);
        loaded.copy.commit("Border", 6, nullptr, 0, nullptr, 0);

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
        // "emanuel" is absent from the test pack, so this checks WordTable::deliberateCapitals
        // alone.
        LoadedEngine loaded;
        loaded.open();
        // Three times, so the word is established; the subject here is the capitalisation flag.
        loaded.copy.commit("emanuel", 7, nullptr, 0, nullptr, 0, false);
        loaded.copy.commit("emanuel", 7, nullptr, 0, nullptr, 0, false);
        loaded.copy.commit("emanuel", 7, nullptr, 0, nullptr, 0, false);

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
        loaded.copy.commit("emanuel", 7, nullptr, 0, nullptr, 0, true);
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
            loaded.copy.commit(a, std::strlen(a), nullptr, 0, nullptr, 0);
            loaded.copy.commit(b, std::strlen(b), a, std::strlen(a), nullptr, 0, false, false,
                                kPackLanguage, kPackLanguageLength);
            loaded.copy.commit(c, std::strlen(c), b, std::strlen(b), a, std::strlen(a), false,
                                false, kPackLanguage, kPackLanguageLength);
        };

        // Off by default.
        LoadedEngine off;
        off.open();
        for (int i = 0; i < 6; ++i) {
            write(off, "the", "test", "keys");
        }
        check(off.rankOf("", "test keys", "the") < 0,
              "no two-word suggestion unless it is switched on");

        // A phrase's link needs twice the learn-after uses.
        const int phraseUses = static_cast<int>(PersonalScore::kPhraseUsesPerWordUses *
                                                Engine::kDefaultLearnAfterUses);
        LoadedEngine on;
        on.open();
        on.engine.setPhraseSuggestions(true);
        for (int i = 0; i < phraseUses - 1; ++i) {
            write(on, "the", "test", "keys");
        }
        check(on.rankOf("", "test keys", "the") < 0,
              "one use short of twice learn-after is no phrase");
        write(on, "the", "test", "keys");
        const int phraseAt = on.rankOf("", "test keys", "the");
        check(phraseAt >= 0, "twice learn-after is");
        check(phraseAt < on.rankOf("", "test", "the"),
              "and ahead of its own first word, which \"keys\" follows six times in six");

        // "test" followed by "keys" six times and by eight other pack words once each.
        LoadedEngine shared;
        shared.open();
        shared.engine.setPhraseSuggestions(true);
        for (int i = 0; i < phraseUses; ++i) {
            write(shared, "the", "test", "keys");
        }
        const char* alsoAfterTest[8] = {"time", "water", "keyboard", "key",
                                        "border", "theme", "them", "there"};
        for (const char* other : alsoAfterTest) {
            shared.copy.commit(other, std::strlen(other), "test", 4, "the", 3, false, false,
                                kPackLanguage, kPackLanguageLength);
        }
        const int sharedAt = shared.rankOf("", "test keys", "the");
        check(sharedAt >= 0 && sharedAt > shared.rankOf("", "test", "the"),
              "under its own first word while \"keys\" is one of several words that follow it");

        LoadedEngine soon;
        soon.open();
        soon.engine.setPhraseSuggestions(true);
        soon.engine.setLearnAfterUses(1);
        write(soon, "the", "test", "keys");
        check(soon.rankOf("", "test keys", "the") < 0, "at learn-after 1, one use is no phrase");
        write(soon, "the", "test", "keys");
        check(soon.rankOf("", "test keys", "the") > 0, "and two uses are");

        // A link under a third of its context's continuations is no phrase.
        LoadedEngine spread;
        spread.open();
        spread.engine.setPhraseSuggestions(true);
        const char* others[] = {"time", "water", "keyboard", "key", "border", "theme"};
        for (int i = 0; i < phraseUses; ++i) {
            write(spread, "the", "test", "keys");
            for (const char* other : others) {
                write(spread, "the", other, "keys");
            }
        }
        check(spread.rankOf("", "test keys", "the") < 0,
              "a link under a third of its context is no phrase");

        // The second link is gated on its own count.
        LoadedEngine second;
        second.open();
        second.engine.setPhraseSuggestions(true);
        for (int i = 0; i < phraseUses / 2; ++i) {
            write(second, "the", "test", "keys");
        }
        for (int i = 0; i < phraseUses - phraseUses / 2; ++i) {
            second.copy.commit("the", 3, nullptr, 0, nullptr, 0);
            second.copy.commit("test", 4, "the", 3, nullptr, 0, false, false, kPackLanguage,
                                kPackLanguageLength);
        }
        check(second.rankOf("", "test keys", "the") < 0,
              "a second link at learn-after but short of twice it is no phrase");
    }

    section("a phrase is not learned from a single word");
    {
        LoadedEngine loaded;
        loaded.open();
        loaded.copy.commit("vreau", 5, nullptr, 0, nullptr, 0);
        loaded.copy.commit("sa", 2, nullptr, 0, nullptr, 0);
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
        loaded.copy.loadWords(personal, personalLengths, personalCounts, 1);
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
            phrases.copy.commit("the", 3, nullptr, 0, nullptr, 0);
            phrases.copy.commit("test", 4, "the", 3, nullptr, 0, false, false, kPackLanguage,
                                 kPackLanguageLength);
            phrases.copy.commit("keys", 4, "test", 4, "the", 3, false, false, kPackLanguage,
                                 kPackLanguageLength);
        }
        check(phrases.rankOf("", "test keys", "the") >= 0, "a phrase is offered");
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

    section("a word table call takes no more rows than one int array holds the fields of");
    {
        using borderkeys::bridge::kMaxWordRowsPerCall;
        using borderkeys::bridge::kWordFields;
        using borderkeys::bridge::wordFieldsAgree;
        check(wordFieldsAgree(kMaxWordRowsPerCall, kMaxWordRowsPerCall * kWordFields),
              "the most rows agree with their fields");
        check(!wordFieldsAgree(kMaxWordRowsPerCall, kMaxWordRowsPerCall * kWordFields - 1),
              "one int short does not");
        // One row more counts its fields past an int: wrapped, the count names no array's length.
        const int32_t past = kMaxWordRowsPerCall + 1;
        const auto wrapped = static_cast<int32_t>(static_cast<uint32_t>(past) * kWordFields);
        check(!wordFieldsAgree(past, wrapped), "one row more agrees with no length");
    }
}
