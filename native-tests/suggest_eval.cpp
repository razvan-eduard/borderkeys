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
 * The corpus form measures where the strip ranks the expected word. `--autocorrect` names an
 * autocorrect corpus and measures it the same way; what the space bar commits for it is read
 * through the app's own commit, by the Kotlin pipeline tests. The explain form also prints the
 * tap decoder's word and its margin.
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
 * when a row falls below its floor. A line `# sentence <genre> <sentence>` names the sentence the
 * runs after it come from.
 *
 *     suggest_eval <dict dir> --context <corpus.txt> <tag> [next one two three]
 *
 * `--centre-taps`, anywhere after the dict dir, taps each letter of a corpus case at its key's
 * centre, where the default touch patterns price every substitution as the key geometry does.
 *
 * `--rows <file>`, anywhere after the dict dir, writes one row per corpus row into `file`, for
 * scripts/compare_readings.py: a line `# measures: <name> ...`, then each row's verdicts (1 or 0,
 * one per measure), its key and its reading, tab-separated, in corpus order. The measures are
 * `first` and `top-three` for the corpus form and `--autocorrect`, `reachable` for `--reachable`,
 * and for
 * `--context` the first place and the top three with the words before and without them, one row
 * per word and letters typed, a word the pack does not hold reading 0 on all four. When the
 * corpus has sentence lines, a line `# fields: sentence genre` follows the measures and each row
 * carries its sentence and genre after its key.
 *
 * `--start-rows <file>`, with `--context`, also asks each run's first word with nothing before
 * it, as a sentence opens, at each depth, and writes its rows into `file`: the measures `first`
 * and `top-three`, the key `^ <letters typed>|<word>`.
 */

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include "context_runs.hpp"
#include "engine.hpp"
#include "proximity.hpp"
#include "rows_file.hpp"
#include "test_support.hpp"

using namespace borderkeys;

namespace {

/** `candidate`'s text, or empty when it has none. */
std::string textOf(const Engine& engine, const Candidate& candidate) {
    uint32_t length = 0;
    const char* const text = engine.candidateText(candidate, &length);
    return text != nullptr ? std::string(text, length) : std::string();
}

}  // namespace

namespace {

struct Case {
    std::string typed;
    std::string expected;
};

using borderkeys_eval::Asked;
using borderkeys_eval::RowsFile;
using borderkeys_eval::Run;
using borderkeys_eval::forEachAsked;
using borderkeys_eval::kContextDepths;
using borderkeys_eval::kTopThree;
using borderkeys_eval::rankInTopThree;
using borderkeys_eval::readRuns;

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

/** A --context row's counts: the words asked for, and how many came first and in the top three. */
struct Tally {
    int asked = 0;
    int first = 0;
    int topThree = 0;
};

/**
 * `target`'s place in the top three when its first `typedBytes` bytes are typed after
 * `previous1` and `previous2`, each null for none; -1 when it is not there.
 */
int placeOf(Engine& engine, const std::string& target, size_t typedBytes,
            const std::string* previous1, const std::string* previous2, Candidate* out) {
    const int count = engine.suggest(
        target.c_str(), typedBytes, previous1 != nullptr ? previous1->c_str() : nullptr,
        previous1 != nullptr ? previous1->size() : 0,
        previous2 != nullptr ? previous2->c_str() : nullptr,
        previous2 != nullptr ? previous2->size() : 0, out, Engine::kMaxCandidates);
    for (int i = 0; i < count && i < kTopThree; ++i) {
        uint32_t length = 0;
        const char* const text = engine.candidateText(out[i], &length);
        if (text != nullptr &&
            sameSpellingIgnoringCase(text, length, target.c_str(), target.size())) {
            return i;
        }
    }
    return -1;
}

/**
 * The --context measurement: prints a row per depth and returns the exit status, non-zero when a
 * row with a floor in `floors` falls below it.
 */
int measureContext(Engine& engine, const char* corpusPath, const std::string& tag,
                   char** floors, int floorCount, const RowsFile& rows,
                   const RowsFile& startRows) {
    const std::vector<Run> runs = readRuns(corpusPath);
    if (runs.empty()) {
        std::printf("no runs read from %s\n", corpusPath);
        return 1;
    }
    rows.measures({"first", "top-three", "first-without-context", "top-three-without-context"});
    startRows.measures({"first", "top-three"});
    if (!runs.front().fields.empty()) {
        rows.fields({"sentence", "genre"});
        startRows.fields({"sentence", "genre"});
    }
    // [0] without the words before, [1] with them.
    Tally tallies[2][kContextDepths];
    Tally startTallies[kContextDepths];
    int withContext = 0;
    int notHeld = 0;
    Candidate out[Engine::kMaxCandidates];
    // Whether the pack holds the target, read once per target, at its first depth.
    bool held = false;
    forEachAsked(runs, startRows.writes(), [&](const Asked& asked) {
        const bool start = asked.previous1 == nullptr;
        if (asked.depth == 0) {
            held = engine.exactSpelling(asked.target.c_str(), asked.target.size(), nullptr,
                                        nullptr);
            withContext += start ? 0 : 1;
            notHeld += (start || held) ? 0 : 1;
        }
        const std::string& fields = asked.run->fields;
        if (start) {
            if (!held) {
                startRows.row({false, false}, asked.key, "not in the pack", fields);
                return;
            }
            const int rank = placeOf(engine, asked.target, asked.typedBytes, nullptr, nullptr, out);
            Tally& tally = startTallies[asked.depth];
            ++tally.asked;
            tally.first += (rank == 0) ? 1 : 0;
            tally.topThree += (rank >= 0) ? 1 : 0;
            startRows.row({rank == 0, rank >= 0}, asked.key,
                          rank < 0 ? "-" : std::to_string(rank + 1), fields);
            return;
        }
        if (!held) {
            rows.row({false, false, false, false}, asked.key, "not in the pack", fields);
            return;
        }
        // [arm]'s place in the top three, or -1.
        int ranks[2] = {-1, -1};
        for (int arm = 0; arm < 2; ++arm) {
            const bool context = arm == 1;
            const int rank = placeOf(engine, asked.target, asked.typedBytes,
                                     context ? asked.previous1 : nullptr,
                                     context ? asked.previous2 : nullptr, out);
            Tally& tally = tallies[arm][asked.depth];
            ++tally.asked;
            tally.first += (rank == 0) ? 1 : 0;
            tally.topThree += (rank >= 0) ? 1 : 0;
            ranks[arm] = rank;
        }
        auto place = [](int rank) { return rank < 0 ? std::string("-") : std::to_string(rank + 1); };
        rows.row({ranks[1] == 0, ranks[1] >= 0, ranks[0] == 0, ranks[0] >= 0}, asked.key,
                 place(ranks[1]) + "\t" + place(ranks[0]), fields);
    });

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
    if (startRows.writes()) {
        std::printf("\nsentence starts, nothing before the word:\n");
        std::printf("                     words   first  top three\n");
        for (int depth = 0; depth < kContextDepths; ++depth) {
            const Tally& start = startTallies[depth];
            std::printf("  %-16s %7d   %5.1f%%   %5.1f%%\n", kRowNames[depth], start.asked,
                        share(start.first, start.asked), share(start.topThree, start.asked));
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
    std::unique_ptr<FILE, int (*)(FILE*)> rowsOut(nullptr, &std::fclose);
    const auto rowsFlag = std::find_if(arguments.begin(), arguments.end(), [](const char* each) {
        return std::strcmp(each, "--rows") == 0;
    });
    if (rowsFlag != arguments.end()) {
        if (rowsFlag + 1 == arguments.end()) {
            std::printf("--rows needs a file\n");
            return 2;
        }
        rowsOut.reset(std::fopen(*(rowsFlag + 1), "w"));
        if (rowsOut == nullptr) {
            std::printf("cannot write %s\n", *(rowsFlag + 1));
            return 2;
        }
        arguments.erase(rowsFlag, rowsFlag + 2);
    }
    const RowsFile rowsFile(rowsOut.get());
    std::unique_ptr<FILE, int (*)(FILE*)> startRowsOut(nullptr, &std::fclose);
    const auto startFlag = std::find_if(arguments.begin(), arguments.end(), [](const char* each) {
        return std::strcmp(each, "--start-rows") == 0;
    });
    if (startFlag != arguments.end()) {
        if (startFlag + 1 == arguments.end()) {
            std::printf("--start-rows needs a file\n");
            return 2;
        }
        startRowsOut.reset(std::fopen(*(startFlag + 1), "w"));
        if (startRowsOut == nullptr) {
            std::printf("cannot write %s\n", *(startFlag + 1));
            return 2;
        }
        arguments.erase(startFlag, startFlag + 2);
    }
    const RowsFile startRowsFile(startRowsOut.get());
    argc = static_cast<int>(arguments.size());
    argv = arguments.data();

    if (argc < 3) {
        std::printf("usage: suggest_eval <dict dir> <corpus.tsv> [tag ...]\n");
        return 2;
    }
    const char* const directory = argv[1];
    // `--autocorrect <corpus>` names an autocorrect corpus, measured as the bare corpus form
    // measures one: where the strip ranks the right word.
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
        if (!borderkeys_test::loadPack(engine, directory, tag)) {
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
        return measureContext(engine, corpusPath, tags.front(), argv + 5, argc - 5, rowsFile,
                              startRowsFile);
    }

    if (reachability) {
        const std::vector<Case> rows = readCorpus(corpusPath);
        if (rows.empty()) {
            std::printf("no rows read from %s\n", corpusPath);
            return 1;
        }
        rowsFile.measures({"reachable"});
        // A row that comes back in another casing is reachable; a different spelling is not.
        std::vector<std::string> unreachable;
        for (const Case& row : rows) {
            Engine::ScoreParts parts;
            if (engine.explainScore(row.typed.c_str(), row.typed.size(), row.typed.c_str(),
                                    row.typed.size(), &parts)) {
                rowsFile.row({true}, row.typed, "");
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
            rowsFile.row({reachable}, row.typed, "");
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
        // The tap decoder's word, for the request just made.
        const auto reportDecoder = [&engine]() {
            Candidate decoded{};
            float margin = 0.0f;
            if (engine.decoderBest(&decoded, &margin)) {
                std::printf("  tap decoder: '%s', %+.2f nats over the letters as typed, %s\n",
                            textOf(engine, decoded).c_str(), margin,
                            engine.decodedCorrection() != nullptr ? "accepted" : "not accepted");
            } else {
                std::printf("  tap decoder: no word\n");
            }
        };
        Engine::ScoreParts parts;
        if (!engine.explainScore(typed, std::strlen(typed), wanted, std::strlen(wanted), &parts)) {
            std::printf("\n'%s' is not offered for '%s' at all.\n", wanted, typed);
            reportDecoder();
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
            std::printf("  personal score      %+9.3f\n", parts.personal);
            std::printf("    of which context  %+9.3f\n", parts.context);
        }
        std::printf("  edit + completion   %+9.3f\n", parts.rest);
        std::printf("    edits %d, past typed %d, cost %.2f: penalty %+.3f, surcharge %+.3f, "
                    "completion %+.3f\n",
                    parts.edits, parts.pastTyped, parts.editCost, -parts.editPenalty,
                    -parts.surcharge, -parts.completion);
        std::printf("  ---\n");
        std::printf("  total               %+9.3f\n", parts.total);

        // Autocorrect's list, copied out before the explain calls below make the request again.
        const Candidate* settled = nullptr;
        const int count = engine.corrections(&settled);
        std::vector<Candidate> list(settled, settled + count);
        std::vector<std::string> words;
        for (const Candidate& entry : list) {
            const std::string text = textOf(engine, entry);
            words.push_back(text.empty() ? std::string("?") : text);
        }
        std::printf("\n  autocorrect's list, best first (%d):\n", count);
        std::printf("  %-16s %5s %10s %5s %9s %10s %9s\n", "word", "edits", "past typed", "cost",
                    "LM", "edit terms", "total");
        for (int i = 0; i < count; ++i) {
            Engine::ScoreParts entry;
            const bool onStrip = engine.explainScore(typed, std::strlen(typed), words[i].c_str(),
                                                     words[i].size(), &entry);
            const float languageModel = onStrip ? entry.languageModel + entry.packWeight : 0.0f;
            std::printf("  %-16s %5d %10d %5.2f %+9.3f %+10.3f %+9.3f %s\n", words[i].c_str(),
                        list[i].edits, list[i].pastTyped, list[i].editCost, languageModel,
                        onStrip ? list[i].score - languageModel : 0.0f, list[i].score,
                        engine.candidateIsProperNoun(list[i]) ? "name" : "");
        }
        // The request made once more for the decoder's word below.
        engine.explainScore(typed, std::strlen(typed), wanted, std::strlen(wanted), &parts);
        reportDecoder();
        return 0;
    }

    const std::vector<Case> cases = readCorpus(corpusPath);
    if (cases.empty()) {
        std::printf("no cases in %s\n", corpusPath);
        return 1;
    }

    int firstPlace = 0;
    int topThree = 0;
    int found = 0;
    long rankTotal = 0;

    std::printf("%-18s %-18s %s\n", "typed", "expected", "rank");
    rowsFile.measures({"first", "top-three"});
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
        const bool first = rank == 0;
        const bool inTopThree = rankInTopThree(rank);
        firstPlace += first ? 1 : 0;
        topThree += inTopThree ? 1 : 0;
        if (rank >= 0) {
            ++found;
            rankTotal += rank + 1;
        }
        if (rank < 0) {
            std::printf("%-18s %-18s %s\n", item.typed.c_str(), item.expected.c_str(), "absent");
        } else {
            std::printf("%-18s %-18s %d\n", item.typed.c_str(), item.expected.c_str(), rank + 1);
        }
        rowsFile.row({first, inTopThree}, item.typed,
                     item.expected + "\t" + (rank < 0 ? std::string("absent") : std::to_string(rank + 1)));
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
