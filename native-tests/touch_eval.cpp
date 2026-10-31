// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * The touch model measured on a corpus from tools/make_tap_corpus.py. With `--floor P` it is
 * also a gate: the exit status is non-zero when autocorrect's right word, with the taps and the
 * default patterns alone, falls below P percent.
 *
 * The words typed within the first `--train-taps` taps train the key patterns the way the
 * keyboard learns them: each letter that hit its own key or a ring neighbour gives the key it
 * meant one sample, its offset from that key's centre in key units, dropped beyond one key unit
 * and weighted down by `--half-life-taps` (0 keeps every tap at full weight). The
 * `--test-words` words from the tap `--test-from-taps`, or else right after the training, are
 * the test. For each one typed with a slip into a string no dictionary spells, at least three
 * letters long, it records what autocorrect commits and where the strip ranks the word meant:
 * with no taps, priced by the key geometry; with the taps and the default patterns alone; and
 * with the learned patterns at each weight and minimum. `--rows <file>` writes a rows file: per
 * such word and setting, whether the strip ranks the word meant first and among its first three,
 * keyed by the word's place among the corpus's words, the setting, the typed string and the word
 * meant; what autocorrect commits stays in the report.
 *
 * Usage:
 *     touch_eval <dict dir> <tag> <layout> <taps.tsv> [--train-taps N] [--test-words N]
 *                [--test-from-taps N] [--half-life-taps N] [--weights a,b,...]
 *                [--min-taps a,b,...] [--floor P] [--rows <file>]
 */

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "replay_corpora.hpp"
#include "rows_file.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using borderkeys_eval::Layout;
using borderkeys_eval::RowsFile;
using borderkeys_eval::TappedWord;
using borderkeys_eval::loadLayout;
using borderkeys_eval::placeOf;
using borderkeys_eval::rankInTopThree;
using borderkeys_eval::readTapCorpus;
using borderkeys_eval::spelledAsTyped;
using borderkeys_eval::typedWithSlip;

namespace {

std::vector<float> readList(const char* text) {
    std::vector<float> values;
    for (const char* p = text; *p != '\0';) {
        char* end = nullptr;
        values.push_back(std::strtof(p, &end));
        if (end == p) {
            break;
        }
        p = (*end == ',') ? end + 1 : end;
    }
    return values;
}

/** Weighted sums of one key's samples: weight, offsets, squares and product, in key units. */
struct Totals {
    double weight = 0.0;
    double x = 0.0;
    double y = 0.0;
    double xx = 0.0;
    double yy = 0.0;
    double xy = 0.0;
};

struct Tally {
    int fixed = 0;
    int kept = 0;
    int other = 0;
    int first = 0;
    int topThree = 0;
};

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        std::printf("usage: touch_eval <dict dir> <tag> <layout> <taps.tsv> [--train-taps N] "
                    "[--test-words N] [--test-from-taps N] [--half-life-taps N] "
                    "[--weights a,b] [--min-taps a,b] [--floor P] [--rows <file>]\n");
        return 2;
    }
    long trainTaps = 20000;
    size_t testWords = 4000;
    long testFromTaps = 0;
    double halfLifeTaps = 0.0;
    double floor = -1.0;
    std::vector<float> weights = {1.0f};
    std::vector<float> minimums = {30.0f};
    const char* rowsPath = nullptr;
    for (int i = 5; i + 1 < argc; i += 2) {
        if (std::strcmp(argv[i], "--rows") == 0) {
            rowsPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--floor") == 0) {
            floor = std::strtod(argv[i + 1], nullptr);
        } else if (std::strcmp(argv[i], "--train-taps") == 0) {
            trainTaps = std::strtol(argv[i + 1], nullptr, 10);
        } else if (std::strcmp(argv[i], "--test-words") == 0) {
            testWords = std::strtoul(argv[i + 1], nullptr, 10);
        } else if (std::strcmp(argv[i], "--test-from-taps") == 0) {
            testFromTaps = std::strtol(argv[i + 1], nullptr, 10);
        } else if (std::strcmp(argv[i], "--half-life-taps") == 0) {
            halfLifeTaps = std::strtod(argv[i + 1], nullptr);
        } else if (std::strcmp(argv[i], "--weights") == 0) {
            weights = readList(argv[i + 1]);
        } else if (std::strcmp(argv[i], "--min-taps") == 0) {
            minimums = readList(argv[i + 1]);
        }
    }

    Layout layout;
    if (!loadLayout(argv[3], &layout)) {
        std::printf("could not read the layout %s\n", argv[3]);
        return 1;
    }
    const std::vector<TappedWord> words = readTapCorpus(argv[4]);
    if (words.empty()) {
        std::printf("no words in %s\n", argv[4]);
        return 1;
    }
    std::unique_ptr<FILE, int (*)(FILE*)> rowsOut(nullptr, &std::fclose);
    if (rowsPath != nullptr) {
        rowsOut.reset(std::fopen(rowsPath, "w"));
        if (rowsOut == nullptr) {
            std::printf("cannot write %s\n", rowsPath);
            return 1;
        }
    }
    const RowsFile rows(rowsOut.get());
    rows.measures({"first", "top-three"});

    Engine engine;
    if (!engine.create() || !borderkeys_test::loadPack(engine, argv[1], argv[2])) {
        std::printf("the engine would not start on %s\n", argv[2]);
        return 1;
    }
    const char* tags[1] = {argv[2]};
    const float packWeights[1] = {1.0f};
    engine.setActiveLanguages(tags, packWeights, 1);
    engine.setLanguageLock(0.0f, false);
    if (!engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                               layout.keyHeight)) {
        std::printf("the layout was refused\n");
        return 1;
    }

    // Training: the words typed within the first trainTaps taps.
    Totals totals[64];
    size_t next = 0;
    long tapIndex = 0;
    long samples = 0;
    long dropped = 0;
    for (; next < words.size() && tapIndex < trainTaps; ++next) {
        const TappedWord& word = words[next];
        for (size_t i = 0; i < word.intended.size(); ++i, ++tapIndex) {
            const int meant = layout.indexOf(word.intended[i]);
            const int hit = layout.indexOf(word.typed[i]);
            if (meant < 0 || hit < 0) {
                continue;
            }
            const float apartX = (layout.xs[hit] - layout.xs[meant]) / layout.keyWidth;
            const float apartY = (layout.ys[hit] - layout.ys[meant]) / layout.keyHeight;
            if (std::sqrt(apartX * apartX + apartY * apartY) > KeyGeometry::kNeighbourRadius) {
                continue;
            }
            const double x = (word.xs[i] - layout.xs[meant]) / layout.keyWidth;
            const double y = (word.ys[i] - layout.ys[meant]) / layout.keyHeight;
            if (std::sqrt(x * x + y * y) > 1.0) {
                ++dropped;
                continue;
            }
            // Samples are weighted by their age at the end of training.
            const double age = static_cast<double>(trainTaps > tapIndex ? trainTaps - tapIndex : 0);
            const double weight = halfLifeTaps > 0.0 ? std::pow(0.5, age / halfLifeTaps) : 1.0;
            Totals& key = totals[meant];
            key.weight += weight;
            key.x += weight * x;
            key.y += weight * y;
            key.xx += weight * x * x;
            key.yy += weight * y * y;
            key.xy += weight * x * y;
            ++samples;
        }
    }

    int32_t codes[64];
    float taps[64];
    float meanX[64];
    float meanY[64];
    float varianceX[64];
    float varianceY[64];
    float covariance[64];
    int patterns = 0;
    for (int i = 0; i < layout.count; ++i) {
        const Totals& key = totals[i];
        if (key.weight <= 0.0) {
            continue;
        }
        const double mx = key.x / key.weight;
        const double my = key.y / key.weight;
        codes[patterns] = layout.codes[i];
        taps[patterns] = static_cast<float>(key.weight);
        meanX[patterns] = static_cast<float>(mx);
        meanY[patterns] = static_cast<float>(my);
        varianceX[patterns] = static_cast<float>(key.xx / key.weight - mx * mx);
        varianceY[patterns] = static_cast<float>(key.yy / key.weight - my * my);
        covariance[patterns] = static_cast<float>(key.xy / key.weight - mx * my);
        ++patterns;
    }
    engine.setTouchPatterns(codes, taps, meanX, meanY, varianceX, varianceY, covariance, patterns);

    std::printf("trained on %ld taps (%zu words): %ld samples, %ld beyond one key unit dropped, "
                "%d keys\n",
                tapIndex, next, samples, dropped, patterns);
    for (const float minimum : minimums) {
        int ready = 0;
        for (int i = 0; i < patterns; ++i) {
            ready += taps[i] >= minimum ? 1 : 0;
        }
        std::printf("  %d keys with at least %.0f taps\n", ready, minimum);
    }

    // The settings measured: no taps, the default patterns, then each weight at each minimum on
    // the learned ones.
    struct Setting {
        bool tapped;
        bool learned;
        float weight;
        int minimum;
    };
    std::vector<Setting> settings = {{false, false, 1.0f, 1}, {true, false, 1.0f, 1}};
    for (const float minimum : minimums) {
        for (const float weight : weights) {
            settings.push_back({true, true, weight, static_cast<int>(minimum)});
        }
    }
    std::vector<Tally> tallies(settings.size());
    // Each setting's name, in the key of its rows.
    std::vector<std::string> settingNames;
    for (const Setting& setting : settings) {
        char name[64];
        if (setting.learned) {
            std::snprintf(name, sizeof(name), "learned %.2f %d", setting.weight, setting.minimum);
        } else {
            std::snprintf(name, sizeof(name), "%s", setting.tapped ? "default" : "off");
        }
        settingNames.emplace_back(name);
    }

    for (; next < words.size() && tapIndex < testFromTaps; ++next) {
        tapIndex += static_cast<long>(words[next].intended.size());
    }
    int slipped = 0;
    int realWords = 0;
    int tested = 0;
    Candidate out[Engine::kMaxCandidates];
    for (size_t end = next + testWords; next < words.size() && next < end; ++next) {
        const TappedWord& word = words[next];
        ++tested;
        if (!typedWithSlip(word)) {
            continue;
        }
        if (spelledAsTyped(engine, word.typed)) {
            ++realWords;
            continue;
        }
        ++slipped;
        for (size_t s = 0; s < settings.size(); ++s) {
            const Setting& setting = settings[s];
            engine.setTouchModel(setting.learned, setting.weight, setting.minimum);
            const int found = engine.suggest(
                word.typed.c_str(), word.typed.size(), nullptr, 0, nullptr, 0,
                setting.tapped ? word.xs.data() : nullptr,
                setting.tapped ? word.ys.data() : nullptr, static_cast<int>(word.xs.size()), out,
                Engine::kMaxCandidates);
            Tally& tally = tallies[s];
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
                ++tally.kept;
            } else if (applied == word.intended) {
                ++tally.fixed;
            } else {
                ++tally.other;
            }
            int rank = -1;
            for (int i = 0; i < found; ++i) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(out[i], &length);
                if (text != nullptr && length == word.intended.size() &&
                    std::memcmp(text, word.intended.c_str(), length) == 0) {
                    rank = i;
                    break;
                }
            }
            const bool first = rank == 0;
            const bool topThree = rankInTopThree(rank);
            tally.first += first ? 1 : 0;
            tally.topThree += topThree ? 1 : 0;
            if (rows.writes()) {
                rows.row({first, topThree},
                         std::to_string(next) + " " + settingNames[s] + " " + word.typed + " " +
                             word.intended,
                         placeOf(rank) + "\t" + borderkeys_test::topWord(engine, out, found));
            }
        }
    }

    std::printf("tested %d words: %d slipped into no word, %d into another word\n", tested,
                slipped, realWords);
    if (slipped == 0) {
        if (floor >= 0.0) {
            std::printf("no slipped words to hold the floor of %.1f%% against\n", floor);
            return 1;
        }
        return 0;
    }
    const double all = static_cast<double>(slipped);
    std::printf("%-7s %6s %5s  %7s %7s %7s  %7s %7s\n", "model", "weight", "min", "fixed", "kept",
                "other", "first", "top3");
    for (size_t s = 0; s < settings.size(); ++s) {
        const Tally& tally = tallies[s];
        if (settings[s].learned) {
            std::printf("%-7s %6.2f %5d", "learned", settings[s].weight, settings[s].minimum);
        } else if (settings[s].tapped) {
            std::printf("%-7s %6s %5s", "default", "-", "-");
        } else {
            std::printf("%-7s %6s %5s", "off", "-", "-");
        }
        std::printf("  %6.1f%% %6.1f%% %6.1f%%  %6.1f%% %6.1f%%\n", 100.0 * tally.fixed / all,
                    100.0 * tally.kept / all, 100.0 * tally.other / all,
                    100.0 * tally.first / all, 100.0 * tally.topThree / all);
    }
    if (floor >= 0.0) {
        // settings[1] is the taps with the default patterns alone, compared as printed.
        const double fixedShare = std::round(1000.0 * tallies[1].fixed / all) / 10.0;
        const bool held = fixedShare + 1e-9 >= floor;
        std::printf("default patterns: right word %.1f%%, floor %.1f%%: %s\n", fixedShare, floor,
                    held ? "held" : "BELOW THE FLOOR");
        return held ? 0 : 1;
    }
    return 0;
}
