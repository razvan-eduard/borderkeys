// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_CONTEXT_RUNS_HPP
#define BORDERKEYS_CONTEXT_RUNS_HPP

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

// The held-out text a --context reading asks, as tools/make_context_corpus.py writes it, and the
// rows asked of it: suggest_eval measures each one, timing_eval times each one.

namespace borderkeys_eval {

// The line that opens a sentence's runs in a --context corpus: "# sentence <genre> <sentence>".
inline constexpr char kSentenceLine[] = "# sentence ";

/** One line of a --context corpus, and the sentence and genre its sentence line named. */
struct Run {
    std::vector<std::string> words;
    // "<sentence>\t<genre>", or empty when the corpus has no sentence lines.
    std::string fields;
};

/** The runs of a --context corpus, each split at its spaces into words, each with the fields of
 *  the last sentence line before it. */
inline std::vector<Run> readRuns(const char* path) {
    std::vector<Run> runs;
    FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return runs;
    }
    char* line = nullptr;
    size_t capacity = 0;
    std::string fields;
    while (getline(&line, &capacity, file) > 0) {
        std::string text(line);
        while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
            text.pop_back();
        }
        if (text.compare(0, std::strlen(kSentenceLine), kSentenceLine) == 0) {
            // "<genre> <sentence>" on the line, "<sentence>\t<genre>" on the rows.
            const std::string named = text.substr(std::strlen(kSentenceLine));
            const size_t space = named.find(' ');
            fields = named.substr(space + 1) + "\t" + named.substr(0, space);
            continue;
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
        runs.push_back(Run{std::move(words), fields});
    }
    std::free(line);
    std::fclose(file);
    return runs;
}

/** How many code points `text` holds. */
inline int codePointCount(const std::string& text) {
    int count = 0;
    for (const char byte : text) {
        if ((static_cast<unsigned char>(byte) & 0xC0u) != 0x80u) {
            ++count;
        }
    }
    return count;
}

/** The bytes of `text`'s first `count` code points. */
inline size_t codePointBytes(const std::string& text, int count) {
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

/** Letters typed before the context model is asked, one row each; zero asks for the next word. */
inline constexpr int kContextDepths = 4;

/** `piece` from its first letter, as the keyboard's word starts; empty when it has none. */
inline std::string fromFirstLetter(const std::string& piece) {
    const size_t letter = piece.find_first_not_of("'-");
    return letter == std::string::npos ? std::string() : piece.substr(letter);
}


/**
 * A row --context asks: its run, its target from the first letter, how many letters are typed
 * and their bytes, the words before it (none at a sentence's start), and the row's key.
 */
struct Asked {
    const Run* run;
    std::string target;
    int depth;
    size_t typedBytes;
    const std::string* previous1;
    const std::string* previous2;
    std::string key;
};

/**
 * Each row --context asks of [runs], in the order it writes them, to [visit]: with [starts], a
 * run's first word with nothing before it, then each word with a word before it; each at no
 * letters typed and at each prefix shorter than the word, up to kContextDepths.
 */
template <typename Visit>
void forEachAsked(const std::vector<Run>& runs, bool starts, Visit visit) {
    const auto depths = [&visit](const Run& run, const std::string& target,
                                 const std::string* previous1, const std::string* previous2) {
        const int letters = codePointCount(target);
        for (int depth = 0; depth < kContextDepths; ++depth) {
            if (depth > 0 && depth >= letters) {
                continue;
            }
            const size_t typedBytes = codePointBytes(target, depth);
            const std::string typed = target.substr(0, typedBytes);
            visit(Asked{&run, target, depth, typedBytes, previous1, previous2,
                        (previous1 != nullptr ? *previous1 : std::string("^")) + " " + typed +
                            "|" + target});
        }
    };
    for (const Run& run : runs) {
        if (starts && !run.words.empty()) {
            const std::string target = fromFirstLetter(run.words[0]);
            if (!target.empty()) {
                depths(run, target, nullptr, nullptr);
            }
        }
        for (size_t at = 1; at < run.words.size(); ++at) {
            const std::string target = fromFirstLetter(run.words[at]);
            if (!target.empty()) {
                depths(run, target, &run.words[at - 1], (at >= 2) ? &run.words[at - 2] : nullptr);
            }
        }
    }
}

}  // namespace borderkeys_eval

#endif  // BORDERKEYS_CONTEXT_RUNS_HPP
