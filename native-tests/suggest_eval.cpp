// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * What the strip would offer, measured against a corpus. It asserts only when asked to:
 * `--reachable`'s budget, `--context`'s floors and `--first-floor` set the exit status.
 *
 * Corpus format, one case per line, `#` comments and blank lines ignored, any column after the
 * second ignored:
 *
 *     typed<TAB>expected
 *
 * `expected` is what the strip ought to put first; a case where the word should be left alone
 * names the typed word itself.
 *
 * The bare form measures where the strip ranks the expected word; `--autocorrect` measures what
 * the space bar commits, which is a different heap, walking autocorrect's list: a word the
 * dictionaries spell is left alone; a candidate past the edit ceiling (one edit, two from eight
 * letters, two from four when every edit was a neighbouring key in place of a typed one) or a
 * name whose letters are not the typed ones is passed over; a typed word under three letters
 * stops the walk unless the candidate only restores its accents. The inflection guard and the
 * contraction and possessive rewrites are not modelled.
 *
 * Usage:
 *     suggest_eval <dict dir> <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --autocorrect <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --explain <typed> <candidate> [tag ...]
 *     suggest_eval <dict dir> --reachable <dictionary.tsv> <tag> [budget]
 *
 * Packs are named by tag (default en-US). The corpus form prints per-case ranks, then rank-1
 * accuracy, top-3 accuracy, and the mean rank of the cases it found at all; with
 * `--first-floor P`, anywhere after the dict dir, its exit status is non-zero when the rank-1
 * share falls below P percent. The explain form prints where one candidate's score came from.
 *
 * `--reachable` checks that every word compiled into a shipped pack is retrievable from that
 * pack as itself: it reads the source `.tsv` and queries the pack built from it. `budget` is the
 * number of unreachable rows tolerated, zero when omitted; the exit status is non-zero above it.
 *
 * `--context` measures the context model on held-out text, a corpus from
 * tools/make_context_corpus.py: each word that has a word before it, asked for with nothing
 * typed and with its first one, two and three letters typed, once without the words before it
 * and once with them. A word the pack does not hold is left out. The optional floors are the
 * top-three shares with context, in percent, for the four rows; the exit status is non-zero
 * when a row falls below its floor.
 *
 *     suggest_eval <dict dir> --context <corpus.txt> <tag> [next one two three]
 *
 * `--centre-taps`, anywhere after the dict dir, taps each letter of a corpus case at its key's
 * centre, where the default touch patterns price every substitution as the key geometry does.
 */

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "proximity.hpp"
#include "test_support.hpp"

using namespace borderkeys;

namespace {

/** Optimal string alignment distance: Levenshtein with a swap of two adjacent code points as
 *  one edit. */
int osaDistance(const uint32_t* a, int aLength, const uint32_t* b, int bLength) {
    constexpr int kCap = 64;
    if (aLength > kCap || bLength > kCap) {
        return kCap;
    }
    int rows[3][kCap + 1];
    int* twoBack = rows[0];
    int* previous = rows[1];
    int* current = rows[2];
    for (int j = 0; j <= bLength; ++j) {
        previous[j] = j;
    }
    for (int i = 1; i <= aLength; ++i) {
        current[0] = i;
        for (int j = 1; j <= bLength; ++j) {
            const int cost = a[i - 1] == b[j - 1] ? 0 : 1;
            int best = previous[j] + 1;
            best = std::min(best, current[j - 1] + 1);
            best = std::min(best, previous[j - 1] + cost);
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                best = std::min(best, twoBack[j - 2] + 1);
            }
            current[j] = best;
        }
        int* const rotate = twoBack;
        twoBack = previous;
        previous = current;
        current = rotate;
    }
    return previous[bLength];
}

/** What the keyboard commits for `typed` from autocorrect's list, or empty for nothing. */
std::string committedFor(Engine& engine, const std::string& typed) {
    // A word the dictionaries already spell is never replaced.
    char spelling[128];
    const int spelled =
        engine.knownSpelling(typed.c_str(), typed.size(), spelling, sizeof(spelling) - 1);
    if (spelled > 0 && typed.size() == static_cast<size_t>(spelled) &&
        std::memcmp(spelling, typed.c_str(), typed.size()) == 0) {
        return std::string();
    }
    uint32_t typedFolded[64];
    const int typedCount = foldUtf8(typed.c_str(), typed.size(), typedFolded, 64);
    if (typedCount <= 0) {
        return std::string();
    }
    const int maxEdits = typedCount >= 8 ? 2 : 1;
    const int maxSlipEdits = typedCount >= 4 ? 2 : maxEdits;
    const Candidate* list = nullptr;
    const int count = engine.corrections(&list);
    for (int i = 0; i < count; ++i) {
        uint32_t length = 0;
        const char* const text = engine.candidateText(list[i], &length);
        if (text == nullptr || length == 0) {
            continue;
        }
        uint32_t wordFolded[64];
        const int wordCount = foldUtf8(text, length, wordFolded, 64);
        if (wordCount <= 0) {
            continue;
        }
        const bool sameLetters =
            wordCount == typedCount &&
            std::memcmp(wordFolded, typedFolded, sizeof(uint32_t) * typedCount) == 0;
        const int ceiling = list[i].slipsOnly() ? std::max(maxEdits, maxSlipEdits) : maxEdits;
        if (osaDistance(typedFolded, typedCount, wordFolded, wordCount) > ceiling) {
            continue;
        }
        if (engine.candidateIsProperNoun(list[i]) && !sameLetters) {
            continue;
        }
        if (length == typed.size() && std::memcmp(text, typed.c_str(), length) == 0) {
            continue;
        }
        if (typedCount < 3 && !(typedCount >= 2 && sameLetters)) {
            break;
        }
        return std::string(text, length);
    }
    return std::string();
}

}  // namespace

namespace {

struct Case {
    std::string typed;
    std::string expected;
};

/** `<dir>/<tag with '-' as '_'>.bkd`, which is how the packs are named on disk. */
std::string packPath(const char* directory, const std::string& tag) {
    std::string file = tag;
    for (char& c : file) {
        if (c == '-') {
            c = '_';
        }
    }
    return std::string(directory) + "/" + file + ".bkd";
}

bool loadPack(Engine& engine, const std::string& tag, const std::string& path) {
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
    if (status != kBkdOk) {
        std::printf("loadLanguage(%s) refused the pack: %d\n", tag.c_str(), status);
        return false;
    }
    return true;
}

std::vector<Case> readCorpus(const char* path) {
    std::vector<Case> cases;
    FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return cases;
    }
    char line[512];
    while (std::fgets(line, sizeof(line), file) != nullptr) {
        std::string text(line);
        while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
            text.pop_back();
        }
        if (text.empty() || text[0] == '#') {
            continue;
        }
        const size_t tab = text.find('\t');
        if (tab == std::string::npos || tab == 0 || tab + 1 >= text.size()) {
            continue;
        }
        const size_t end = text.find('\t', tab + 1);
        cases.push_back(Case{text.substr(0, tab),
                             text.substr(tab + 1, end == std::string::npos ? end : end - tab - 1)});
    }
    std::fclose(file);
    return cases;
}

/** The lines of a --context corpus, each split at its spaces into words. */
std::vector<std::vector<std::string>> readRuns(const char* path) {
    std::vector<std::vector<std::string>> runs;
    FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return runs;
    }
    char* line = nullptr;
    size_t capacity = 0;
    while (getline(&line, &capacity, file) > 0) {
        std::string text(line);
        while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
            text.pop_back();
        }
        if (text.empty() || text[0] == '#') {
            continue;
        }
        std::vector<std::string> words;
        size_t start = 0;
        while (start < text.size()) {
            size_t end = text.find(' ', start);
            if (end == std::string::npos) {
                end = text.size();
            }
            if (end > start) {
                words.push_back(text.substr(start, end - start));
            }
            start = end + 1;
        }
        runs.push_back(std::move(words));
    }
    std::free(line);
    std::fclose(file);
    return runs;
}

/** How many code points `text` holds. */
int codePointCount(const std::string& text) {
    int count = 0;
    for (const char byte : text) {
        if ((static_cast<unsigned char>(byte) & 0xC0u) != 0x80u) {
            ++count;
        }
    }
    return count;
}

/** The bytes of `text`'s first `count` code points. */
size_t codePointBytes(const std::string& text, int count) {
    size_t at = 0;
    for (int seen = 0; at < text.size(); ++at) {
        if ((static_cast<unsigned char>(text[at]) & 0xC0u) != 0x80u) {
            if (seen == count) {
                break;
            }
            ++seen;
        }
    }
    return at;
}

/** A --context row's counts: the words asked for, and how many came first and in the top three. */
struct Tally {
    int asked = 0;
    int first = 0;
    int topThree = 0;
};

/** Letters typed before the context model is asked, one row each; zero asks for the next word. */
constexpr int kContextDepths = 4;

/**
 * The --context measurement: prints a row per depth and returns the exit status, non-zero when a
 * row with a floor in `floors` falls below it.
 */
int measureContext(Engine& engine, const char* corpusPath, const std::string& tag,
                   char** floors, int floorCount) {
    const std::vector<std::vector<std::string>> runs = readRuns(corpusPath);
    if (runs.empty()) {
        std::printf("no runs read from %s\n", corpusPath);
        return 1;
    }
    // [0] without the words before, [1] with them.
    Tally tallies[2][kContextDepths];
    int withContext = 0;
    int notHeld = 0;
    Candidate out[Engine::kMaxCandidates];
    for (const std::vector<std::string>& run : runs) {
        for (size_t at = 1; at < run.size(); ++at) {
            // The keyboard's word starts at its first letter.
            std::string target = run[at];
            const size_t letter = target.find_first_not_of("'-");
            if (letter == std::string::npos) {
                continue;
            }
            target.erase(0, letter);
            ++withContext;
            if (!engine.exactSpelling(target.c_str(), target.size(), nullptr, nullptr)) {
                ++notHeld;
                continue;
            }
            const std::string& previous1 = run[at - 1];
            const std::string* const previous2 = (at >= 2) ? &run[at - 2] : nullptr;
            const int letters = codePointCount(target);
            for (int depth = 0; depth < kContextDepths; ++depth) {
                // Prefixes shorter than the word only.
                if (depth > 0 && depth >= letters) {
                    continue;
                }
                const size_t typedBytes = codePointBytes(target, depth);
                for (int arm = 0; arm < 2; ++arm) {
                    const bool context = arm == 1;
                    const int count = engine.suggest(
                        target.c_str(), typedBytes,
                        context ? previous1.c_str() : nullptr, context ? previous1.size() : 0,
                        (context && previous2 != nullptr) ? previous2->c_str() : nullptr,
                        (context && previous2 != nullptr) ? previous2->size() : 0, out,
                        Engine::kMaxCandidates);
                    int rank = -1;
                    for (int i = 0; i < count && i < 3; ++i) {
                        uint32_t length = 0;
                        const char* const text = engine.candidateText(out[i], &length);
                        if (text != nullptr &&
                            sameSpellingIgnoringCase(text, length, target.c_str(), target.size())) {
                            rank = i;
                            break;
                        }
                    }
                    Tally& tally = tallies[arm][depth];
                    ++tally.asked;
                    tally.first += (rank == 0) ? 1 : 0;
                    tally.topThree += (rank >= 0) ? 1 : 0;
                }
            }
        }
    }

    auto share = [](int part, int whole) {
        return whole > 0 ? 100.0 * static_cast<double>(part) / static_cast<double>(whole) : 0.0;
    };
    std::printf("\ncontext model, %s: %d words with a word before, %d not in the pack (%.1f%%), "
                "left out\n\n",
                tag.c_str(), withContext, notHeld, share(notHeld, withContext));
    std::printf("                     words   without context      with context        gain\n");
    std::printf("                             first  top three     first  top three   top three\n");
    static const char* const kRowNames[kContextDepths] = {
        "next word", "1 letter typed", "2 letters typed", "3 letters typed"};
    int status = 0;
    for (int depth = 0; depth < kContextDepths; ++depth) {
        const Tally& without = tallies[0][depth];
        const Tally& with = tallies[1][depth];
        const double withTop = share(with.topThree, with.asked);
        std::printf("  %-16s %7d   %5.1f%%   %5.1f%%        %5.1f%%   %5.1f%%      %+5.1f\n",
                    kRowNames[depth], with.asked, share(without.first, without.asked),
                    share(without.topThree, without.asked), share(with.first, with.asked),
                    withTop, withTop - share(without.topThree, without.asked));
        if (depth < floorCount) {
            const double floor = std::strtod(floors[depth], nullptr);
            if (withTop + 1e-9 < floor) {
                std::printf("    %s: top three with context %.1f%%, floor %.1f%%\n",
                            kRowNames[depth], withTop, floor);
                status = 1;
            }
        }
    }
    return status;
}

/**
 * Where each code point of `typed` is tapped: an ASCII letter, in either case, at its key's
 * centre; anything else nowhere, NaN.
 */
void centreTaps(const borderkeys_test::TestLayout& layout, const std::string& typed,
                std::vector<float>& xs, std::vector<float>& ys) {
    xs.clear();
    ys.clear();
    for (const char byte : typed) {
        const unsigned char unit = static_cast<unsigned char>(byte);
        // A continuation byte belongs to the code point before it.
        if ((unit & 0xC0u) == 0x80u) {
            continue;
        }
        float x = std::numeric_limits<float>::quiet_NaN();
        float y = x;
        const char letter =
            (unit >= 'A' && unit <= 'Z') ? static_cast<char>(unit - 'A' + 'a') : byte;
        layout.centreOf(letter, &x, &y);
        xs.push_back(x);
        ys.push_back(y);
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<char*> arguments(argv, argv + argc);
    const auto tapsFlag = std::find_if(arguments.begin(), arguments.end(), [](const char* each) {
        return std::strcmp(each, "--centre-taps") == 0;
    });
    const bool centreTapped = tapsFlag != arguments.end();
    if (centreTapped) {
        arguments.erase(tapsFlag);
    }
    double firstFloor = -1.0;
    const auto floorFlag = std::find_if(arguments.begin(), arguments.end(), [](const char* each) {
        return std::strcmp(each, "--first-floor") == 0;
    });
    if (floorFlag != arguments.end()) {
        if (floorFlag + 1 == arguments.end()) {
            std::printf("--first-floor needs a percentage\n");
            return 2;
        }
        firstFloor = std::strtod(*(floorFlag + 1), nullptr);
        arguments.erase(floorFlag, floorFlag + 2);
    }
    argc = static_cast<int>(arguments.size());
    argv = arguments.data();

    if (argc < 3) {
        std::printf("usage: suggest_eval <dict dir> <corpus.tsv> [tag ...]\n");
        return 2;
    }
    const char* const directory = argv[1];
    // `--autocorrect <corpus>` measures what the space bar commits; the bare corpus form
    // measures where the strip ranks the right word.
    const bool autocorrectMode = std::strcmp(argv[2], "--autocorrect") == 0;
    if (autocorrectMode && argc < 4) {
        std::printf("usage: suggest_eval <dict dir> --autocorrect <corpus.tsv> [tag ...]\n");
        return 2;
    }
    const bool explaining = std::strcmp(argv[2], "--explain") == 0;
    if (explaining && argc < 5) {
        std::printf("usage: suggest_eval <dict dir> --explain <typed> <candidate> [tag ...]\n");
        return 2;
    }
    const bool reachability = std::strcmp(argv[2], "--reachable") == 0;
    if (reachability && argc < 5) {
        std::printf("usage: suggest_eval <dict dir> --reachable <dictionary.tsv> <tag>\n");
        return 2;
    }
    const bool contextMode = std::strcmp(argv[2], "--context") == 0;
    if (contextMode && argc < 5) {
        std::printf("usage: suggest_eval <dict dir> --context <corpus.txt> <tag> "
                    "[next one two three]\n");
        return 2;
    }
    const bool onePack = reachability || contextMode;
    const char* const corpusPath =
        explaining ? nullptr : ((autocorrectMode || onePack) ? argv[3] : argv[2]);

    std::vector<std::string> tags;
    // One pack for --reachable and --context; after its tag come a budget or floors.
    const int tagEnd = onePack ? (argc < 5 ? argc : 5) : argc;
    for (int i = explaining ? 5 : ((autocorrectMode || onePack) ? 4 : 3); i < tagEnd; ++i) {
        tags.emplace_back(argv[i]);
    }
    if (tags.empty()) {
        tags.emplace_back("en-US");
    }

    Engine engine;
    if (!engine.create()) {
        std::printf("the engine would not start\n");
        return 1;
    }
    for (const std::string& tag : tags) {
        if (!loadPack(engine, tag, packPath(directory, tag))) {
            return 1;
        }
    }

    std::vector<const char*> tagPointers;
    std::vector<float> weights;
    for (const std::string& tag : tags) {
        tagPointers.push_back(tag.c_str());
        weights.push_back(1.0f);
    }
    engine.setActiveLanguages(tagPointers.data(), weights.data(),
                              static_cast<int>(tagPointers.size()));

    // Undecided, as on the first word typed.
    engine.setLanguageLock(0.0f, false);

    // Key geometry; without it the walk never leaves exact-match mode.
    borderkeys_test::TestLayout layout;
    if (!engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                               layout.keyHeight)) {
        std::printf("the test layout was refused\n");
        return 1;
    }
    std::vector<float> tapXs;
    std::vector<float> tapYs;

    if (contextMode) {
        return measureContext(engine, corpusPath, tags.front(), argv + 5, argc - 5);
    }

    if (reachability) {
        const std::vector<Case> rows = readCorpus(corpusPath);
        if (rows.empty()) {
            std::printf("no rows read from %s\n", corpusPath);
            return 1;
        }
        // A row that comes back in another casing is reachable; a different spelling is not.
        std::vector<std::string> unreachable;
        for (const Case& row : rows) {
            Engine::ScoreParts parts;
            if (engine.explainScore(row.typed.c_str(), row.typed.size(), row.typed.c_str(),
                                    row.typed.size(), &parts)) {
                continue;
            }
            // Tried lower-cased and capitalised; only ASCII letters change case.
            std::string lowered = row.typed;
            for (char& c : lowered) {
                if (c >= 'A' && c <= 'Z') {
                    c = static_cast<char>(c - 'A' + 'a');
                }
            }
            std::string capitalised = lowered;
            if (!capitalised.empty() && capitalised[0] >= 'a' && capitalised[0] <= 'z') {
                capitalised[0] = static_cast<char>(capitalised[0] - 'a' + 'A');
            }
            bool reachable = false;
            for (const std::string& variant : {lowered, capitalised}) {
                if (variant == row.typed) {
                    continue;
                }
                if (engine.explainScore(row.typed.c_str(), row.typed.size(), variant.c_str(),
                                        variant.size(), &parts)) {
                    reachable = true;
                    break;
                }
            }
            if (!reachable) {
                unreachable.push_back(row.typed);
            }
        }
        std::printf("\n%s: %zu rows, %zu unreachable (%.2f%%)\n", tags.front().c_str(),
                    rows.size(), unreachable.size(),
                    100.0 * static_cast<double>(unreachable.size()) /
                        static_cast<double>(rows.size()));
        const size_t shown = unreachable.size() < 20 ? unreachable.size() : 20;
        for (size_t i = 0; i < shown; ++i) {
            std::printf("  %s\n", unreachable[i].c_str());
        }
        if (unreachable.size() > shown) {
            std::printf("  ... and %zu more\n", unreachable.size() - shown);
        }
        const size_t budget = (argc > 5) ? std::strtoul(argv[5], nullptr, 10) : 0;
        if (unreachable.size() > budget) {
            std::printf("  budget is %zu; %zu more words became unreachable\n", budget,
                        unreachable.size() - budget);
            return 1;
        }
        return 0;
    }

    if (explaining) {
        const char* const typed = argv[3];
        const char* const wanted = argv[4];
        Engine::ScoreParts parts;
        if (!engine.explainScore(typed, std::strlen(typed), wanted, std::strlen(wanted), &parts)) {
            std::printf("\n'%s' is not offered for '%s' at all.\n", wanted, typed);
            return 0;
        }
        std::printf("\n'%s' -> '%s'\n", typed, wanted);
        std::printf("  rank                %d\n", parts.rank + 1);
        std::printf("  pack                %d\n", parts.packIndex);
        std::printf("  edit distance       %d\n", parts.editDistance);
        std::printf("  characters added    %d\n", parts.addedCharacters);
        std::printf("  ---\n");
        std::printf("  language model      %+9.3f\n", parts.languageModel);
        std::printf("  pack weight         %+9.3f\n", parts.packWeight);
        if (parts.personal != 0.0f) {
            std::printf("  personal boost      %+9.3f\n", parts.personal);
        }
        std::printf("  edit + completion   %+9.3f\n", parts.rest);
        std::printf("    edits %d, run-on %d, cost %.2f: penalty %+.3f, surcharge %+.3f, "
                    "completion %+.3f\n",
                    parts.edits, parts.runOn, parts.editCost, -parts.editPenalty,
                    -parts.surcharge, -parts.completion);
        std::printf("  ---\n");
        std::printf("  total               %+9.3f\n", parts.total);

        // Autocorrect's list, copied out before the explain calls below make the request again.
        const Candidate* settled = nullptr;
        const int count = engine.corrections(&settled);
        std::vector<Candidate> list(settled, settled + count);
        std::vector<std::string> words;
        for (const Candidate& entry : list) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(entry, &length);
            words.emplace_back(text != nullptr ? text : "?", text != nullptr ? length : 1u);
        }
        std::printf("\n  autocorrect's list, best first (%d):\n", count);
        std::printf("  %-16s %5s %6s %5s %9s %10s %9s\n", "word", "edits", "run-on", "cost",
                    "LM", "edit terms", "total");
        for (int i = 0; i < count; ++i) {
            Engine::ScoreParts entry;
            const bool onStrip = engine.explainScore(typed, std::strlen(typed), words[i].c_str(),
                                                     words[i].size(), &entry);
            const float languageModel = onStrip ? entry.languageModel + entry.packWeight : 0.0f;
            std::printf("  %-16s %5d %6d %5.2f %+9.3f %+10.3f %+9.3f %s\n", words[i].c_str(),
                        list[i].edits, list[i].runOn, list[i].editCost, languageModel,
                        onStrip ? list[i].score - languageModel : 0.0f, list[i].score,
                        engine.candidateIsProperNoun(list[i]) ? "name" : "");
        }
        // The request made once more for the walk below.
        engine.explainScore(typed, std::strlen(typed), wanted, std::strlen(wanted), &parts);
        const std::string committed = committedFor(engine, typed);
        if (!committed.empty()) {
            std::printf("  autocorrect would take '%s'\n", committed.c_str());
        } else {
            std::printf("  autocorrect would leave it\n");
        }
        return 0;
    }

    const std::vector<Case> cases = readCorpus(corpusPath);
    if (cases.empty()) {
        std::printf("no cases in %s\n", corpusPath);
        return 1;
    }

    // What the space bar would commit, rather than where the strip ranks a word.
    if (autocorrectMode) {
        int right = 0;
        int wrong = 0;
        int none = 0;
        std::printf("%-18s %-18s %s\n", "typed", "expected", "autocorrect commits");
        for (const Case& item : cases) {
            Candidate scratch[Engine::kMaxCandidates];
            if (centreTapped) {
                centreTaps(layout, item.typed, tapXs, tapYs);
            }
            engine.suggest(item.typed.c_str(), item.typed.size(), nullptr, 0, nullptr, 0,
                           centreTapped ? tapXs.data() : nullptr,
                           centreTapped ? tapYs.data() : nullptr, static_cast<int>(tapXs.size()),
                           scratch, Engine::kMaxCandidates);
            const std::string applied = committedFor(engine, item.typed);
            if (applied.empty()) {
                ++none;
            } else if (applied == item.expected) {
                ++right;
            } else {
                ++wrong;
            }
            std::printf("%-18s %-18s %s\n", item.typed.c_str(), item.expected.c_str(),
                        applied.empty() ? "(nothing)" : applied.c_str());
        }
        const double all = static_cast<double>(cases.size());
        std::printf("\ncases            %zu\n", cases.size());
        std::printf("commits expected %d  (%.1f%%)\n", right, 100.0 * right / all);
        std::printf("commits other    %d  (%.1f%%)\n", wrong, 100.0 * wrong / all);
        std::printf("commits nothing  %d  (%.1f%%)\n", none, 100.0 * none / all);
        return 0;
    }

    int firstPlace = 0;
    int topThree = 0;
    int found = 0;
    long rankTotal = 0;

    std::printf("%-18s %-18s %s\n", "typed", "expected", "rank");
    Candidate out[Engine::kMaxCandidates];
    for (const Case& item : cases) {
        if (centreTapped) {
            centreTaps(layout, item.typed, tapXs, tapYs);
        }
        const int count = engine.suggest(
            item.typed.c_str(), item.typed.size(), nullptr, 0, nullptr, 0,
            centreTapped ? tapXs.data() : nullptr, centreTapped ? tapYs.data() : nullptr,
            static_cast<int>(tapXs.size()), out, Engine::kMaxCandidates);
        int rank = -1;
        for (int i = 0; i < count; ++i) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(out[i], &length);
            if (text != nullptr && length == item.expected.size() &&
                std::memcmp(text, item.expected.c_str(), length) == 0) {
                rank = i;
                break;
            }
        }
        if (rank == 0) {
            ++firstPlace;
        }
        if (rank >= 0 && rank < 3) {
            ++topThree;
        }
        if (rank >= 0) {
            ++found;
            rankTotal += rank + 1;
        }
        if (rank < 0) {
            std::printf("%-18s %-18s %s\n", item.typed.c_str(), item.expected.c_str(), "absent");
        } else {
            std::printf("%-18s %-18s %d\n", item.typed.c_str(), item.expected.c_str(), rank + 1);
        }
    }

    const double total = static_cast<double>(cases.size());
    std::printf("\ncases            %zu\n", cases.size());
    std::printf("first            %d  (%.1f%%)\n", firstPlace, 100.0 * firstPlace / total);
    std::printf("top three        %d  (%.1f%%)\n", topThree, 100.0 * topThree / total);
    std::printf("absent           %zu\n", cases.size() - static_cast<size_t>(found));
    if (found > 0) {
        std::printf("mean rank found  %.2f\n", static_cast<double>(rankTotal) / found);
    }
    if (firstFloor >= 0.0) {
        // Compared as printed, to one decimal.
        const double firstShare = total > 0.0 ? std::round(1000.0 * firstPlace / total) / 10.0 : 0.0;
        const bool held = total > 0.0 && firstShare + 1e-9 >= firstFloor;
        std::printf("first %.1f%%, floor %.1f%%: %s\n", firstShare, firstFloor,
                    held ? "held" : "BELOW THE FLOOR");
        return held ? 0 : 1;
    }
    return 0;
}
