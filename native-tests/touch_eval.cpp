// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * The touch model measured on a corpus from tools/make_tap_corpus.py. A measurement, not a
 * test: it does not assert.
 *
 * The words typed within the first `--train-taps` taps train the key patterns the way the
 * keyboard learns them: each letter that hit its own key or a ring neighbour gives the key it
 * meant one sample, its offset from that key's centre in key units, dropped beyond one key unit
 * and weighted down by `--half-life-taps` (0 keeps every tap at full weight). The
 * `--test-words` words from the tap `--test-from-taps`, or else right after the training, are
 * the test. For each one typed with a slip into a string no dictionary
 * spells, at least three letters long, it records what autocorrect commits and where the strip
 * ranks the word meant: with the model off; with every key a pattern centred on it at the
 * reference spread, which uses where the tap landed and nothing learned; and with the learned
 * patterns at each weight and minimum.
 *
 * Usage:
 *     touch_eval <dict dir> <tag> <layout> <taps.tsv> [--train-taps N] [--test-words N]
 *                [--test-from-taps N] [--half-life-taps N] [--weights a,b,...]
 *                [--min-taps a,b,...]
 */

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "touch_model.hpp"

using namespace borderkeys;

namespace {

struct Layout {
    int32_t codes[64] = {};
    float xs[64] = {};
    float ys[64] = {};
    int count = 0;
    float keyWidth = 0.f;
    float keyHeight = 0.f;

    int indexOf(char letter) const {
        for (int i = 0; i < count; ++i) {
            if (codes[i] == static_cast<unsigned char>(letter)) {
                return i;
            }
        }
        return -1;
    }
};

bool loadLayout(const char* path, Layout* layout) {
    std::FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return false;
    }
    char line[256];
    while (std::fgets(line, sizeof(line), file) != nullptr) {
        if (line[0] == '#' || line[0] == '\n') {
            continue;
        }
        char code[16];
        float a = 0.f;
        float b = 0.f;
        if (layout->keyWidth == 0.f && std::sscanf(line, "%f %f", &a, &b) == 2) {
            layout->keyWidth = a;
            layout->keyHeight = b;
            continue;
        }
        if (std::sscanf(line, "%15s %f %f", code, &a, &b) == 3 && layout->count < 64) {
            layout->codes[layout->count] = static_cast<unsigned char>(code[0]);
            layout->xs[layout->count] = a;
            layout->ys[layout->count] = b;
            ++layout->count;
        }
    }
    std::fclose(file);
    return layout->count > 0 && layout->keyWidth > 0.f;
}

struct Word {
    std::string intended;
    std::string typed;
    std::vector<float> xs;
    std::vector<float> ys;
};

std::vector<Word> readCorpus(const char* path) {
    std::vector<Word> words;
    std::FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return words;
    }
    char line[4096];
    while (std::fgets(line, sizeof(line), file) != nullptr) {
        if (line[0] == '#' || line[0] == '\n') {
            continue;
        }
        char* const firstTab = std::strchr(line, '\t');
        char* const secondTab = firstTab != nullptr ? std::strchr(firstTab + 1, '\t') : nullptr;
        if (secondTab == nullptr) {
            continue;
        }
        Word word;
        word.intended.assign(line, firstTab);
        word.typed.assign(firstTab + 1, secondTab);
        for (char* point = secondTab + 1; *point != '\0' && *point != '\n';) {
            char* end = nullptr;
            const float x = std::strtof(point, &end);
            if (end == point || *end != ',') {
                break;
            }
            point = end + 1;
            const float y = std::strtof(point, &end);
            if (end == point) {
                break;
            }
            word.xs.push_back(x);
            word.ys.push_back(y);
            point = end;
            while (*point == ' ') {
                ++point;
            }
        }
        if (word.xs.size() == word.intended.size() && word.typed.size() == word.intended.size()) {
            words.push_back(std::move(word));
        }
    }
    std::fclose(file);
    return words;
}

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

bool loadPack(Engine& engine, const char* directory, const std::string& tag) {
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
        return false;
    }
    const int32_t status = engine.loadLanguage(tag.c_str(), fd, 0, info.st_size, 1.0f);
    ::close(fd);
    return status == kBkdOk;
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
                    "[--weights a,b] [--min-taps a,b]\n");
        return 2;
    }
    long trainTaps = 20000;
    size_t testWords = 4000;
    long testFromTaps = 0;
    double halfLifeTaps = 0.0;
    std::vector<float> weights = {1.0f};
    std::vector<float> minimums = {30.0f};
    for (int i = 5; i + 1 < argc; i += 2) {
        if (std::strcmp(argv[i], "--train-taps") == 0) {
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
    const std::vector<Word> words = readCorpus(argv[4]);
    if (words.empty()) {
        std::printf("no words in %s\n", argv[4]);
        return 1;
    }

    Engine engine;
    if (!engine.create() || !loadPack(engine, argv[1], argv[2])) {
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
        const Word& word = words[next];
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

    int32_t neutralCodes[64];
    float neutralTaps[64];
    float neutralMean[64];
    float neutralSpread[64];
    for (int i = 0; i < layout.count; ++i) {
        neutralCodes[i] = layout.codes[i];
        neutralTaps[i] = 1.0e6f;
        neutralMean[i] = 0.0f;
        neutralSpread[i] = TouchModel::kReferenceSpread * TouchModel::kReferenceSpread;
    }

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

    // The settings measured: the model off, the neutral patterns, then each weight at each
    // minimum on the learned ones.
    struct Setting {
        bool enabled;
        bool neutral;
        float weight;
        int minimum;
    };
    std::vector<Setting> settings = {{false, false, 0.0f, 0}, {true, true, 1.0f, 1}};
    for (const float minimum : minimums) {
        for (const float weight : weights) {
            settings.push_back({true, false, weight, static_cast<int>(minimum)});
        }
    }
    bool neutralSet = false;
    std::vector<Tally> tallies(settings.size());

    for (; next < words.size() && tapIndex < testFromTaps; ++next) {
        tapIndex += static_cast<long>(words[next].intended.size());
    }
    int slipped = 0;
    int realWords = 0;
    int tested = 0;
    Candidate out[Engine::kMaxCandidates];
    for (size_t end = next + testWords; next < words.size() && next < end; ++next) {
        const Word& word = words[next];
        ++tested;
        if (word.typed == word.intended || word.typed.size() < 3) {
            continue;
        }
        char spelling[128];
        const int spelled = engine.knownSpelling(word.typed.c_str(), word.typed.size(), spelling,
                                                 sizeof(spelling) - 1);
        if (spelled == static_cast<int>(word.typed.size()) &&
            std::memcmp(spelling, word.typed.c_str(), word.typed.size()) == 0) {
            ++realWords;
            continue;
        }
        ++slipped;
        for (size_t s = 0; s < settings.size(); ++s) {
            if (settings[s].neutral != neutralSet) {
                neutralSet = settings[s].neutral;
                if (neutralSet) {
                    engine.setTouchPatterns(neutralCodes, neutralTaps, neutralMean, neutralMean,
                                            neutralSpread, neutralSpread, neutralMean,
                                            layout.count);
                } else {
                    engine.setTouchPatterns(codes, taps, meanX, meanY, varianceX, varianceY,
                                            covariance, patterns);
                }
            }
            engine.setTouchModel(settings[s].enabled, settings[s].weight, settings[s].minimum);
            const int found = engine.suggest(
                word.typed.c_str(), word.typed.size(), nullptr, 0, nullptr, 0, word.xs.data(),
                word.ys.data(), static_cast<int>(word.xs.size()), out, Engine::kMaxCandidates);
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
            for (int rank = 0; rank < found && rank < 3; ++rank) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(out[rank], &length);
                if (text != nullptr && length == word.intended.size() &&
                    std::memcmp(text, word.intended.c_str(), length) == 0) {
                    tally.first += rank == 0 ? 1 : 0;
                    ++tally.topThree;
                    break;
                }
            }
        }
    }

    std::printf("tested %d words: %d slipped into no word, %d into another word\n", tested,
                slipped, realWords);
    if (slipped == 0) {
        return 0;
    }
    const double all = static_cast<double>(slipped);
    std::printf("%-7s %6s %5s  %7s %7s %7s  %7s %7s\n", "model", "weight", "min", "fixed", "kept",
                "other", "first", "top3");
    for (size_t s = 0; s < settings.size(); ++s) {
        const Tally& tally = tallies[s];
        if (settings[s].neutral) {
            std::printf("%-7s %6s %5s", "neutral", "1.00", "-");
        } else if (settings[s].enabled) {
            std::printf("%-7s %6.2f %5d", "learned", settings[s].weight, settings[s].minimum);
        } else {
            std::printf("%-7s %6s %5s", "off", "-", "-");
        }
        std::printf("  %6.1f%% %6.1f%% %6.1f%%  %6.1f%% %6.1f%%\n", 100.0 * tally.fixed / all,
                    100.0 * tally.kept / all, 100.0 * tally.other / all,
                    100.0 * tally.first / all, 100.0 * tally.topThree / all);
    }
    return 0;
}
