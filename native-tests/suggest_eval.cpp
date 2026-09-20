// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * What the strip would offer, measured against a corpus instead of by eye.
 *
 * Every scoring constant in engine.cpp carries a comment naming a case it was set to fix, and
 * every one of those was found by typing something and looking. That works until two of them
 * pull against each other: raising the edit penalty to stop a frequent word replacing a rare
 * real one is the same change that buries "the" under "tehachapi" when someone types "teh",
 * and no amount of looking at one example tells you the cost of the other.
 *
 * So this is the other half of `gesture_replay`, for typed words rather than gestures, and it
 * follows the same contract deliberately: a corpus in, a measurement out, no verdict. It does
 * not assert and it is not a test. `borderkeys_tests` says whether the engine is correct; this
 * says how good its answers are, which is a number that moves rather than a line that passes.
 *
 * Corpus format, one case per line, `#` comments and blank lines ignored:
 *
 *     typed<TAB>expected
 *
 * `expected` is what the strip ought to put first. A case where the right answer is to leave
 * the word alone is written with the typed word as its own expectation -- "snobul" against
 * "snobul" -- because "offers nothing better than what I wrote" is a result worth measuring,
 * and it is the result the guards in AutoCorrection exist to produce.
 *
 * Two heaps, two questions, two corpus forms. The bare form asks where the *strip* ranks the
 * right word; `--autocorrect` asks what the *space bar* commits, which is a different heap and
 * can disagree completely -- "believ" ranked "believe" second and committed "belief", a word the
 * same search put seventh. A measurement that only ever read the strip could not see that.
 *
 * Usage:
 *     suggest_eval <dict dir> <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --autocorrect <corpus.tsv> [tag ...]
 *     suggest_eval <dict dir> --explain <typed> <candidate> [tag ...]
 *
 * with the packs named by tag (default en-US). The corpus form prints per-case ranks and then
 * rank-1 accuracy, top-3 accuracy, and the mean rank of the cases it found at all. The explain
 * form prints where one candidate's score came from, which is the question every scoring change
 * starts with and the one a list of ranked words cannot answer.
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
    // measures where the strip ranks the right word. Both, because they are separate heaps.
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
    const char* const corpusPath = explaining ? nullptr : (autocorrectMode ? argv[3] : argv[2]);

    std::vector<std::string> tags;
    for (int i = explaining ? 5 : (autocorrectMode ? 4 : 3); i < argc; ++i) {
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

    // Undecided on purpose: a corpus case is one word with no sentence around it, so there is no
    // evidence for the detector to work from and pinning it to one pack would measure a
    // different engine than the one a person types into on their first word.
    engine.setLanguageLock(0.0f, false);

    // Without this KeyGeometry::isSet() is false and the walk never leaves exact-match mode --
    // no substitution, deletion, transposition or insertion at all. A harness missing it
    // measures prefix completion and reports it as the whole engine.
    borderkeys_test::TestLayout layout;
    if (!engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                               layout.keyHeight)) {
        std::printf("the test layout was refused\n");
        return 1;
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

    // What the space bar would commit, rather than where the strip ranks a word. A different
    // question and a different heap, and the two have disagreed badly enough in practice to be
    // worth measuring apart: the strip ranked "believe" second for "believ" while autocorrect
    // committed "belief", which the same strip ranked seventh and sixty points worse.
    if (autocorrectMode) {
        int right = 0;
        int wrong = 0;
        int none = 0;
        std::printf("%-18s %-18s %s\n", "typed", "expected", "autocorrect commits");
        for (const Case& item : cases) {
            Candidate scratch[Engine::kMaxCandidates];
            engine.suggest(item.typed.c_str(), item.typed.size(), nullptr, 0, nullptr, 0,
                           scratch, Engine::kMaxCandidates);
            const Candidate* const best = engine.bestCorrection();
            std::string applied;
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
