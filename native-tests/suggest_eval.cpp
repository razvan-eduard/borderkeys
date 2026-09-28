// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * What the strip would offer, measured against a corpus. A measurement, not a test: it does not
 * assert.
 *
 * Corpus format, one case per line, `#` comments and blank lines ignored:
 *
 *     typed<TAB>expected
 *
 * `expected` is what the strip ought to put first; a case where the word should be left alone
 * names the typed word itself.
 *
 * The bare form measures where the strip ranks the expected word; `--autocorrect` measures what
 * the space bar commits, which is a different heap.
 *
 * Usage:
 *     suggest_eval <dict dir> <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --autocorrect <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --explain <typed> <candidate> [tag ...]
 *     suggest_eval <dict dir> --reachable <dictionary.tsv> <tag> [budget]
 *
 * Packs are named by tag (default en-US). The corpus form prints per-case ranks, then rank-1
 * accuracy, top-3 accuracy, and the mean rank of the cases it found at all. The explain form
 * prints where one candidate's score came from.
 *
 * `--reachable` checks that every word compiled into a shipped pack is retrievable from that
 * pack as itself: it reads the source `.tsv` and queries the pack built from it. `budget` is the
 * number of unreachable rows tolerated, zero when omitted; the exit status is non-zero above it.
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "test_support.hpp"

using namespace borderkeys;

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
        cases.push_back(Case{text.substr(0, tab), text.substr(tab + 1)});
    }
    std::fclose(file);
    return cases;
}

}  // namespace

int main(int argc, char** argv) {
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
    const char* const corpusPath =
        explaining ? nullptr : ((autocorrectMode || reachability) ? argv[3] : argv[2]);

    std::vector<std::string> tags;
    // One pack for --reachable: the question is about one dictionary and the pack built from it,
    // and argv[5] there is the budget rather than a second tag.
    const int tagEnd = reachability ? (argc < 5 ? argc : 5) : argc;
    for (int i = explaining ? 5 : ((autocorrectMode || reachability) ? 4 : 3); i < tagEnd; ++i) {
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
        std::printf("  ---\n");
        std::printf("  total               %+9.3f\n", parts.total);
        const Candidate* const best = engine.bestCorrection();
        if (best != nullptr) {
            uint32_t length = 0;
            const char* const text = engine.candidateText(*best, &length);
            std::printf("  autocorrect would take '%.*s'\n", (int)length, text ? text : "?");
        } else {
            std::printf("  autocorrect has no correction for this\n");
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
            engine.suggest(item.typed.c_str(), item.typed.size(), nullptr, 0, nullptr, 0,
                           scratch, Engine::kMaxCandidates);
            std::string applied;
            // AutoCorrection.correctionFor's known-word guard: a word the dictionaries already
            // spell is never replaced. The same call PredictionEngine makes for knownWord.
            char spelling[128];
            const int spelled = engine.knownSpelling(item.typed.c_str(), item.typed.size(),
                                                     spelling, sizeof(spelling) - 1);
            const bool alreadyAWord =
                spelled > 0 && item.typed.size() == static_cast<size_t>(spelled) &&
                std::memcmp(spelling, item.typed.c_str(), item.typed.size()) == 0;
            const Candidate* const best = alreadyAWord ? nullptr : engine.bestCorrection();
            if (best != nullptr) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(*best, &length);
                if (text != nullptr) {
                    applied.assign(text, length);
                }
            }
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
        const int count = engine.suggest(item.typed.c_str(), item.typed.size(), nullptr, 0,
                                         nullptr, 0, out, Engine::kMaxCandidates);
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
    return 0;
}
