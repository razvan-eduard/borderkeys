// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEST_SUPPORT_HPP
#define BORDERKEYS_TEST_SUPPORT_HPP

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <map>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include "context_slice.hpp"
#include "engine.hpp"
#include "personal_score.hpp"
#include "word_table.hpp"

// The whole test framework: a counter, a macro and a name.

namespace borderkeys_test {

inline int failures = 0;
inline int checks = 0;
inline const char* currentSection = "";

inline void section(const char* name) {
    currentSection = name;
    std::printf("\n== %s ==\n", name);
}

inline void check(bool condition, const char* what) {
    ++checks;
    if (condition) {
        std::printf("  ok   %s\n", what);
    } else {
        ++failures;
        std::printf("  FAIL %s\n", what);
    }
}

inline void checkNear(float actual, float expected, float tolerance, const char* what) {
    check(std::fabs(actual - expected) <= tolerance, what);
}

/** Reads a whole file. Returns an empty string when it is missing. */
inline std::string readFile(const char* path) {
    std::string out;
    std::FILE* const file = std::fopen(path, "rb");
    if (file == nullptr) {
        return out;
    }
    char buffer[8192];
    size_t read;
    while ((read = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
        out.append(buffer, read);
    }
    std::fclose(file);
    return out;
}

inline bool writeFile(const char* path, const std::string& contents) {
    std::FILE* const file = std::fopen(path, "wb");
    if (file == nullptr) {
        return false;
    }
    const size_t written = std::fwrite(contents.data(), 1, contents.size(), file);
    std::fclose(file);
    return written == contents.size();
}

/**
 * Loads the pack `<directory>/<tag with '-' as '_'>.bkd`, as the packs are named on disk, under
 * `tag`.
 */
inline bool loadPack(borderkeys::Engine& engine, const char* directory, const std::string& tag) {
    std::string file = tag;
    for (char& c : file) {
        if (c == '-') {
            c = '_';
        }
    }
    const std::string path = std::string(directory) + "/" + file + ".bkd";
    struct stat info {};
    if (stat(path.c_str(), &info) != 0) {
        std::printf("cannot stat %s\n", path.c_str());
        return false;
    }
    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        std::printf("cannot open %s\n", path.c_str());
        return false;
    }
    const int32_t status = engine.loadLanguage(tag.c_str(), fd, 0, info.st_size, 1.0f);
    ::close(fd);
    if (status != borderkeys::kBkdOk) {
        std::printf("loadLanguage(%s) refused the pack: %d\n", tag.c_str(), status);
        return false;
    }
    return true;
}

/** What a reading writes for a candidate that is not there. */
inline constexpr char kNoWord[] = "-";

/** The text of the first of the [found] candidates in [out], or kNoWord when there is none. */
inline std::string topWord(const borderkeys::Engine& engine, const borderkeys::Candidate* out, int found) {
    uint32_t length = 0;
    const char* const text = found > 0 ? engine.candidateText(out[0], &length) : nullptr;
    return text != nullptr && length != 0 ? std::string(text, length) : std::string(kNoWord);
}

/**
 * A phone QWERTY in pixels: 1080 wide, 108 by 160 keys, the usual row stagger.
 *
 * The same arrangement native-tests/data/qwerty_1080.layout describes, so a gesture recorded
 * against that file and a gesture synthesised here are in the same coordinate space.
 */
struct TestLayout {
    int32_t codes[64] = {};
    float xs[64] = {};
    float ys[64] = {};
    int count = 0;
    float keyWidth = 108.f;
    float keyHeight = 160.f;

    TestLayout() {
        const char* rows[3] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        const float indent[3] = {0.f, 0.5f, 1.5f};
        for (int r = 0; r < 3; ++r) {
            for (const char* p = rows[r]; *p != '\0'; ++p) {
                codes[count] = *p;
                xs[count] = (indent[r] + static_cast<float>(p - rows[r]) + 0.5f) * keyWidth;
                ys[count] = (static_cast<float>(r) + 0.5f) * keyHeight;
                ++count;
            }
        }
    }

    bool centreOf(char character, float* x, float* y) const {
        for (int i = 0; i < count; ++i) {
            if (codes[i] == character) {
                *x = xs[i];
                *y = ys[i];
                return true;
            }
        }
        return false;
    }
};

/** A small deterministic generator, the same on every standard library. */
class Random {
public:
    explicit Random(uint32_t seed) : state_(seed) {}
    uint32_t next() {
        state_ = state_ * 1664525u + 1013904223u;
        return state_;
    }
    float unit() { return static_cast<float>((next() >> 8) & 0xFFFFu) / 65535.f; }
    int below(int bound) { return bound <= 0 ? 0 : static_cast<int>(next() % static_cast<uint32_t>(bound)); }

private:
    uint32_t state_;
};

/**
 * Synthesises a swipe over a word: interpolate through the key centres, jitter, sample
 * unevenly. Recordings use the same corpus format.
 */
inline void synthesiseGesture(const TestLayout& layout, const char* word, float jitter,
                              Random& random, std::vector<float>& xs, std::vector<float>& ys,
                              std::vector<int64_t>& times) {
    xs.clear();
    ys.clear();
    times.clear();
    std::vector<float> anchorX;
    std::vector<float> anchorY;
    for (const char* p = word; *p != '\0'; ++p) {
        float x = 0.f;
        float y = 0.f;
        if (!layout.centreOf(*p, &x, &y)) {
            return;
        }
        if (!anchorX.empty() && anchorX.back() == x && anchorY.back() == y) {
            continue;  // a doubled letter is one pass over the key
        }
        anchorX.push_back(x);
        anchorY.push_back(y);
    }
    if (anchorX.size() < 2) {
        return;
    }
    int64_t time = 0;
    for (size_t s = 0; s + 1 < anchorX.size(); ++s) {
        const float dx = anchorX[s + 1] - anchorX[s];
        const float dy = anchorY[s + 1] - anchorY[s];
        const int steps = 8 + static_cast<int>(std::sqrt(dx * dx + dy * dy) / 18.f);
        for (int k = 0; k < steps; ++k) {
            const float u = static_cast<float>(k) / static_cast<float>(steps);
            xs.push_back(anchorX[s] + dx * u + (random.unit() - 0.5f) * 2.f * jitter);
            ys.push_back(anchorY[s] + dy * u + (random.unit() - 0.5f) * 2.f * jitter);
            time += 8 + static_cast<int64_t>(random.unit() * 6.f);
            times.push_back(time);
        }
    }
    xs.push_back(anchorX.back());
    ys.push_back(anchorY.back());
    times.push_back(time + 10);
}

/** The bonus PersonalScore gives a link used `count` times in a context seen `total` times. */
inline float personalShareOf(uint32_t count, uint32_t total) {
    return borderkeys::PersonalScore::shareBonus(count, total);
}

/** The roundings a rise checked by checkRise carries: the sum that adds it and the difference that reads it. */
inline constexpr float kRiseRoundings = 2.0f;

/** Whether `with` is `without` raised by `rise`, within kRiseRoundings roundings of the larger score. */
inline void checkRise(float with, float without, float rise, const char* what) {
    const float scale = std::max(std::fabs(with), std::fabs(without));
    check(std::fabs((with - without) - rise) <= kRiseRoundings * std::numeric_limits<float>::epsilon() * scale,
          what);
}

/**
 * Counts the heap allocations made through `new` while it lives (allocation_count.cpp replaces the
 * global allocation functions in the test binary); one at a time.
 */
class AllocationCount {
public:
    AllocationCount();
    ~AllocationCount();
    AllocationCount(const AllocationCount&) = delete;
    AllocationCount& operator=(const AllocationCount&) = delete;

    long count() const;
};

/**
 * The keyboard's lookup as the native tests keep it: each word goes into the engine's word table
 * as a load or a commit puts it, and each language's pairs and triples are kept here by their
 * words' ids, counted, totalled and observed as the keyboard counts them, and handed to the engine
 * as the slice of the context a request names, in the order the keyboard hands them: chosen on
 * purpose first, then the most used, then the first recorded.
 *
 * A copy of the lookup's slice rules for the engine's tests, not the lookup: it keeps no language
 * cap (PhraseBudget.LANGUAGE_CAP), and it reads a previous1 of zero length as a sentence start,
 * where the lookup reads it as a word it does not hold. The lookup's own rules are tested in
 * Kotlin (PersonalLookupTest, ContextSliceBridgeTest).
 */
class WorkingCopyFixture {
public:
    /** The context word of a word with nothing before it, as the keyboard records it. */
    static constexpr char kSentenceStart[] = "\x02start";
    static constexpr size_t kSentenceStartLength = sizeof(kSentenceStart) - 1;

    /** Which links a commit recorded. */
    struct Learned {
        bool pair = false;
        bool triple = false;
    };

    explicit WorkingCopyFixture(borderkeys::Engine& engine) : engine_(engine) {}

    WorkingCopyFixture(const WorkingCopyFixture&) = delete;
    WorkingCopyFixture& operator=(const WorkingCopyFixture&) = delete;

    /**
     * Replaces the words, and with them every pair and triple, as the keyboard's load hands them:
     * each row of `words` with a count above zero, its deliberate capitals, choices and adds when
     * given, and the sentence start with no counts.
     */
    void loadWords(const char* const* words, const size_t* lengths, const int32_t* counts, int count,
                   const int32_t* deliberateCapitals = nullptr, const int32_t* asserted = nullptr,
                   const int32_t* added = nullptr) {
        using borderkeys::WordTable;
        stores_.clear();
        std::vector<const char*> spellings;
        std::vector<size_t> spellingLengths;
        std::vector<int32_t> fields;
        for (int i = 0; i < count; ++i) {
            if (counts[i] <= 0) {
                continue;
            }
            spellings.push_back(words[i]);
            spellingLengths.push_back(lengths[i]);
            fields.push_back(counts[i]);
            fields.push_back(deliberateCapitals != nullptr ? deliberateCapitals[i] : 0);
            fields.push_back(asserted != nullptr ? asserted[i] : 0);
            fields.push_back(added != nullptr ? added[i] : 0);
        }
        spellings.push_back(kSentenceStart);
        spellingLengths.push_back(kSentenceStartLength);
        fields.insert(fields.end(), WordTable::kFields, 0);
        std::vector<int32_t> ids(spellings.size(), WordTable::kNewWord);
        engine_.loadWordTable(spellings.data(), spellingLengths.data(), fields.data(),
                              static_cast<int>(spellings.size()), ids.data());
    }

    /**
     * Puts `word` as a commit does before its links are recorded: counted once when `counted`,
     * spelled as committed, with a deliberate capital and a choice when given; left as held, or
     * put with no counts when new, otherwise.
     */
    void putWord(const char* word, size_t length, bool counted, bool deliberateCapital = false,
                 bool asserted = false) {
        using borderkeys::WordTable;
        if (word == nullptr || length == 0) {
            return;
        }
        const WordTable& words = engine_.personalWords();
        const int32_t held = words.entryIndexFor(word, length);
        if (held >= 0 && !counted) {
            return;
        }
        int32_t fields[WordTable::kFields] = {};
        if (held >= 0) {
            const uint32_t entry = static_cast<uint32_t>(held);
            fields[WordTable::kCountField] = static_cast<int32_t>(words.entryCount(entry));
            fields[WordTable::kDeliberateCapitalsField] =
                static_cast<int32_t>(words.deliberateCapitals(entry));
            fields[WordTable::kAssertedField] = static_cast<int32_t>(words.asserted(entry));
            fields[WordTable::kAddedField] = static_cast<int32_t>(words.added(entry));
        }
        if (counted) {
            fields[WordTable::kCountField] += 1;
            fields[WordTable::kDeliberateCapitalsField] += deliberateCapital ? 1 : 0;
            fields[WordTable::kAssertedField] += asserted ? 1 : 0;
        }
        const int32_t id = held >= 0 ? held : WordTable::kNewWord;
        int32_t out = WordTable::kNewWord;
        engine_.putWords(&id, &word, &length, fields, 1, &out);
    }

    /**
     * Commits `word` after `previous1` and `previous2` as the keyboard does: the three put first
     * ([putWord]), the word counted when `tally`; then, in `language`'s store, the pair when the
     * word before and the word are learnable, and the triple when the word two before is too,
     * each when its words differ from the word before ([recordPair]); the sentence start is
     * always learnable.
     */
    Learned commit(const char* word, size_t length, const char* previous1, size_t previous1Length,
                   const char* previous2, size_t previous2Length, bool deliberateCapital = false,
                   bool asserted = false, const char* language = nullptr, size_t languageLength = 0,
                   bool tally = true, bool pairAsserted = false, bool tripleAsserted = false) {
        putWord(word, length, tally, deliberateCapital, asserted);
        putWord(previous1, previous1Length, false);
        putWord(previous2, previous2Length, false);
        Learned learned;
        if (language == nullptr || languageLength == 0 || previous1 == nullptr ||
            previous1Length == 0 || !learnable(word, length) ||
            !learnable(previous1, previous1Length)) {
            return learned;
        }
        Store& store = storeFor(language, languageLength);
        const int32_t next = idOf(word, length);
        const int32_t before1 = idOf(previous1, previous1Length);
        if (next >= 0 && before1 >= 0 && next != before1) {
            record(store.pairs[before1], next, pairAsserted);
            learned.pair = true;
        }
        if (previous2 != nullptr && previous2Length > 0 && learnable(previous2, previous2Length)) {
            const int32_t before2 = idOf(previous2, previous2Length);
            if (next >= 0 && before1 >= 0 && before2 >= 0 && next != before1) {
                record(store.triples[{before2, before1}], next, tripleAsserted);
                learned.triple = true;
            }
        }
        return learned;
    }

    /**
     * Adds the rows as the keyboard's load hands its pairs: each row's count, choices and stamp to
     * its pair in its language's store, a row whose words the table does not hold, or whose word
     * follows itself, left out; `contextSeen` and `asserted` may be null for zeros.
     */
    void loadPairs(const char* const* languages, const size_t* languageLengths,
                   const char* const* previous, const size_t* previousLengths,
                   const char* const* next, const size_t* nextLengths, const int32_t* counts,
                   int count, const uint64_t* contextSeen = nullptr,
                   const int32_t* asserted = nullptr) {
        for (int i = 0; i < count; ++i) {
            const int32_t before = idOf(previous[i], previousLengths[i]);
            const int32_t after = idOf(next[i], nextLengths[i]);
            if (counts[i] <= 0 || before < 0 || after < 0 || before == after) {
                continue;
            }
            add(storeFor(languages[i], languageLengths[i]).pairs[before], after, counts[i],
                asserted != nullptr ? asserted[i] : 0, contextSeen != nullptr ? contextSeen[i] : 0);
        }
    }

    /** [loadPairs], for the triples. */
    void loadTriples(const char* const* languages, const size_t* languageLengths,
                     const char* const* previous2, const size_t* previous2Lengths,
                     const char* const* previous1, const size_t* previous1Lengths,
                     const char* const* next, const size_t* nextLengths, const int32_t* counts,
                     int count, const uint64_t* contextSeen = nullptr,
                     const int32_t* asserted = nullptr) {
        for (int i = 0; i < count; ++i) {
            const int32_t before2 = idOf(previous2[i], previous2Lengths[i]);
            const int32_t before1 = idOf(previous1[i], previous1Lengths[i]);
            const int32_t after = idOf(next[i], nextLengths[i]);
            if (counts[i] <= 0 || before2 < 0 || before1 < 0 || after < 0 || before1 == after) {
                continue;
            }
            add(storeFor(languages[i], languageLengths[i]).triples[{before2, before1}], after,
                counts[i], asserted != nullptr ? asserted[i] : 0,
                contextSeen != nullptr ? contextSeen[i] : 0);
        }
    }

    /** Raises each row's pair context's total to the row's, never lowering it; a context not held is left out. */
    void loadPairTotals(const char* const* languages, const size_t* languageLengths,
                        const char* const* previous, const size_t* previousLengths,
                        const uint32_t* totals, int count) {
        for (int i = 0; i < count; ++i) {
            auto& contexts = storeFor(languages[i], languageLengths[i]).pairs;
            const auto found = contexts.find(idOf(previous[i], previousLengths[i]));
            if (found != contexts.end()) {
                found->second.total = std::max(found->second.total, totals[i]);
            }
        }
    }

    /** Takes each row's pair out of its language's store; its context keeps its uses. */
    void dropPairs(const char* const* languages, const size_t* languageLengths,
                   const char* const* previous, const size_t* previousLengths,
                   const char* const* next, const size_t* nextLengths, int count) {
        for (int i = 0; i < count; ++i) {
            auto& contexts = storeFor(languages[i], languageLengths[i]).pairs;
            const auto found = contexts.find(idOf(previous[i], previousLengths[i]));
            if (found != contexts.end()) {
                drop(found->second, idOf(next[i], nextLengths[i]));
            }
        }
    }

    /** [dropPairs], for the triples. */
    void dropTriples(const char* const* languages, const size_t* languageLengths,
                     const char* const* previous2, const size_t* previous2Lengths,
                     const char* const* previous1, const size_t* previous1Lengths,
                     const char* const* next, const size_t* nextLengths, int count) {
        for (int i = 0; i < count; ++i) {
            auto& contexts = storeFor(languages[i], languageLengths[i]).triples;
            const auto found = contexts.find(
                {idOf(previous2[i], previous2Lengths[i]), idOf(previous1[i], previous1Lengths[i])});
            if (found != contexts.end()) {
                drop(found->second, idOf(next[i], nextLengths[i]));
            }
        }
    }

    /** A slice as Engine::pushSlice takes it, and the key a request names it by. */
    struct Slice {
        borderkeys::ContextSlice::Key key;
        std::vector<int32_t> header;
        std::vector<int32_t> links;
        std::vector<int32_t> hops;
        std::vector<int64_t> seen;
    };

    /**
     * Hands the engine the slice of the context of `previous1`, or the sentence start when it is
     * empty, and `previous2` in `language`'s store, and returns the key a request names it by; a
     * key naming no language, and nothing handed, when `language` is empty.
     */
    borderkeys::ContextSlice::Key push(const char* previous1, size_t previous1Length,
                                       const char* previous2, size_t previous2Length,
                                       const char* language, size_t languageLength) {
        const Slice built = sliceOf(previous1, previous1Length, previous2, previous2Length, language,
                                    languageLength);
        if (!built.header.empty()) {
            engine_.pushSlice(built.header.data(), built.header.size(), built.links.data(),
                              built.links.size(), built.hops.data(), built.hops.size(),
                              built.seen.data(), built.seen.size());
        }
        return built.key;
    }

    /**
     * The slice [push] hands the engine; its key names no language, and it holds nothing, when
     * `language` is empty.
     */
    Slice sliceOf(const char* previous1, size_t previous1Length, const char* previous2,
                  size_t previous2Length, const char* language, size_t languageLength) {
        using borderkeys::ContextSlice;
        Slice built;
        ContextSlice::Key& key = built.key;
        if (language == nullptr || languageLength == 0) {
            return built;
        }
        const bool started = previous1 == nullptr || previous1Length == 0;
        key.language = languageIdOf(language, languageLength);
        key.context1 = started ? idOf(kSentenceStart, kSentenceStartLength)
                               : idOf(previous1, previous1Length);
        key.context2 = previous2 != nullptr && previous2Length > 0 ? idOf(previous2, previous2Length)
                                                                    : ContextSlice::kNone;
        const Store& store = storeFor(language, languageLength);
        const Context* pairs = find(store.pairs, key.context1);
        const Context* triples =
            key.context2 == ContextSlice::kNone ? nullptr : find(store.triples, {key.context2, key.context1});
        const std::vector<Link> pairLinks = offered(pairs);
        const std::vector<Link> tripleLinks = offered(triples);
        std::vector<int32_t> header(ContextSlice::kHeaderFields, 0);
        header[ContextSlice::kLanguageField] = key.language;
        header[ContextSlice::kContext1Field] = key.context1;
        header[ContextSlice::kContext2Field] = key.context2;
        header[ContextSlice::kPairTotalField] = pairs != nullptr ? static_cast<int32_t>(pairs->total) : 0;
        header[ContextSlice::kTripleTotalField] =
            triples != nullptr ? static_cast<int32_t>(triples->total) : 0;
        header[ContextSlice::kPairsField] = static_cast<int32_t>(pairLinks.size());
        header[ContextSlice::kTriplesField] = static_cast<int32_t>(tripleLinks.size());
        std::vector<int32_t> links;
        std::vector<int64_t> seen = {pairs != nullptr ? static_cast<int64_t>(pairs->seen) : 0,
                                     triples != nullptr ? static_cast<int64_t>(triples->seen) : 0};
        for (const std::vector<Link>* kind : {&pairLinks, &tripleLinks}) {
            for (const Link& link : *kind) {
                links.insert(links.end(), {link.word, static_cast<int32_t>(link.count),
                                           static_cast<int32_t>(link.asserted)});
                seen.push_back(static_cast<int64_t>(link.seen));
            }
        }
        std::vector<int32_t> hops;
        const int hopCount = std::min(static_cast<int>(pairLinks.size()), ContextSlice::kMaxHops);
        for (int i = 0; i < hopCount; ++i) {
            const Context* middle = find(store.pairs, pairLinks[static_cast<size_t>(i)].word);
            const std::vector<Link> after = offered(middle);
            hops.insert(hops.end(), {after.empty() ? ContextSlice::kNone : after[0].word,
                                     after.empty() ? 0 : static_cast<int32_t>(after[0].count),
                                     after.empty() ? 0 : static_cast<int32_t>(after[0].asserted),
                                     middle != nullptr ? static_cast<int32_t>(middle->total) : 0});
        }
        header[ContextSlice::kHopsField] = hopCount;
        built.header = std::move(header);
        built.links = std::move(links);
        built.hops = std::move(hops);
        built.seen = std::move(seen);
        return built;
    }

    /** Engine::suggest after [push]ing the slice of the request's context in `language`'s store. */
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                borderkeys::Candidate* out, int maxOut, const char* language = nullptr,
                size_t languageLength = 0) {
        const borderkeys::ContextSlice::Key key =
            push(previous1, previous1Length, previous2, previous2Length, language, languageLength);
        return engine_.suggest(composing, composingLength, previous1, previous1Length, previous2,
                               previous2Length, out, maxOut, key);
    }

    /** [suggest], with where each code point of `composing` was tapped. */
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                const float* tapX, const float* tapY, int tapCount, borderkeys::Candidate* out,
                int maxOut, const char* language = nullptr, size_t languageLength = 0) {
        const borderkeys::ContextSlice::Key key =
            push(previous1, previous1Length, previous2, previous2Length, language, languageLength);
        return engine_.suggest(composing, composingLength, previous1, previous1Length, previous2,
                               previous2Length, tapX, tapY, tapCount, out, maxOut, key);
    }

    /** Engine::decodeGesture after [push]ing the slice, as [suggest] does. */
    int decodeGesture(const float* xs, const float* ys, const int64_t* ts, int count,
                      const char* previous1, size_t previous1Length, const char* previous2,
                      size_t previous2Length, borderkeys::Candidate* out, int maxOut,
                      const char* language = nullptr, size_t languageLength = 0) {
        const borderkeys::ContextSlice::Key key =
            push(previous1, previous1Length, previous2, previous2Length, language, languageLength);
        return engine_.decodeGesture(xs, ys, ts, count, previous1, previous1Length, previous2,
                                     previous2Length, out, maxOut, key);
    }

    /** Engine::explainScore after [push]ing the slice of a request with no word before it. */
    bool explainScore(const char* typed, size_t typedLength, const char* candidate,
                      size_t candidateLength, borderkeys::Engine::ScoreParts* out,
                      const char* language = nullptr, size_t languageLength = 0) {
        const borderkeys::ContextSlice::Key key = push(nullptr, 0, nullptr, 0, language, languageLength);
        return engine_.explainScore(typed, typedLength, candidate, candidateLength, out, key);
    }

    /** The id the engine's word table holds `word` under, in any case; -1 for none. */
    int32_t idOf(const char* word, size_t length) const {
        return word == nullptr || length == 0 ? -1
                                              : engine_.personalWords().entryIndexFor(word, length);
    }

private:
    struct Link {
        int32_t word;
        uint32_t count;
        uint32_t asserted;
        uint64_t seen;
    };

    // One context's links, in the order recorded, its total and its observations.
    struct Context {
        std::vector<Link> links;
        uint32_t total = 0;
        uint64_t seen = 0;
    };

    struct Store {
        std::string language;
        std::map<int32_t, Context> pairs;
        std::map<std::pair<int32_t, int32_t>, Context> triples;
    };

    /** Whether the keyboard counts `word` learnable: the sentence start, or as the engine says. */
    bool learnable(const char* word, size_t length) const {
        return (length == kSentenceStartLength && std::memcmp(word, kSentenceStart, length) == 0) ||
               engine_.learnable(word, length);
    }

    /** One use of the link to `word` in `context`: its context seen once more, the link stamped. */
    static void record(Context& context, int32_t word, bool asserted) {
        context.seen += 1;
        add(context, word, 1, asserted ? 1 : 0, context.seen);
    }

    /** Adds `count` uses and `asserted` choices to the link to `word`, held at the later stamp. */
    static void add(Context& context, int32_t word, int32_t count, int32_t asserted, uint64_t seen) {
        context.total += static_cast<uint32_t>(count);
        for (Link& link : context.links) {
            if (link.word == word) {
                link.count += static_cast<uint32_t>(count);
                link.asserted += static_cast<uint32_t>(asserted);
                link.seen = std::max(link.seen, seen);
                return;
            }
        }
        context.links.push_back(Link{word, static_cast<uint32_t>(count),
                                     static_cast<uint32_t>(asserted), seen});
    }

    static void drop(Context& context, int32_t word) {
        context.links.erase(std::remove_if(context.links.begin(), context.links.end(),
                                           [word](const Link& link) { return link.word == word; }),
                            context.links.end());
    }

    /** `context`'s links in the order the keyboard hands them; none for no context. */
    static std::vector<Link> offered(const Context* context) {
        if (context == nullptr) {
            return {};
        }
        std::vector<Link> links = context->links;
        std::stable_sort(links.begin(), links.end(), [](const Link& a, const Link& b) {
            const bool aChosen = a.asserted > 0u;
            const bool bChosen = b.asserted > 0u;
            return aChosen != bChosen ? aChosen : a.count > b.count;
        });
        return links;
    }

    template <typename Key>
    static const Context* find(const std::map<Key, Context>& contexts, const Key& key) {
        const auto found = contexts.find(key);
        return found == contexts.end() ? nullptr : &found->second;
    }

    Store& storeFor(const char* language, size_t length) {
        const int32_t id = languageIdOf(language, length);
        return stores_[static_cast<size_t>(id)];
    }

    /** The id of `language`'s store, made when new. */
    int32_t languageIdOf(const char* language, size_t length) {
        const std::string named(language, length);
        for (size_t i = 0; i < stores_.size(); ++i) {
            if (stores_[i].language == named) {
                return static_cast<int32_t>(i);
            }
        }
        stores_.push_back(Store{named, {}, {}});
        return static_cast<int32_t>(stores_.size() - 1);
    }

    borderkeys::Engine& engine_;
    std::vector<Store> stores_;
};

}  // namespace borderkeys_test

#endif  // BORDERKEYS_TEST_SUPPORT_HPP
