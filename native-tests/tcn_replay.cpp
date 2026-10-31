// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "replay_corpora.hpp"
#include "test_support.hpp"

// The binary tools/tcn_replay.py drives: gesture_replay.cpp's layout and corpus formats and its
// `word<TAB>rank<TAB>top` output, each gesture decoded as the keyboard decodes a swipe with
// nothing before it: Engine::decodeGesture with the weights loaded and the neural decoder on, the
// context resolved to a sentence start, and a gesture the neural decoder finds nothing for sent to
// the geometric one. Prints to stderr the mean milliseconds per decode and how many gestures the
// geometric decoder answered.
//
//   tcn_replay <pack.bkd> <weights.bkw> <layout> <gestures.csv>
//
// A pack is required: TcnCtcDecoder's beam search is lexicon-constrained (tcn_ctc_decoder.hpp).

namespace {

using namespace borderkeys;
using borderkeys_eval::Gesture;
using borderkeys_eval::Layout;
using borderkeys_eval::loadGestures;
using borderkeys_eval::loadLayout;

// The script the replay's one model is loaded and selected under: the Latin layouts'.
constexpr int kReplayScript = Engine::kLatinSwipeScript;

/** Whether `text` is `word` once apostrophes and hyphens are set aside in both. */
bool sameWordMarksAside(const char* text, uint32_t length, const std::string& word) {
    size_t at = 0;
    for (uint32_t i = 0; i < length; ++i) {
        if (text[i] == '\'' || text[i] == '-') {
            continue;
        }
        while (at < word.size() && (word[at] == '\'' || word[at] == '-')) {
            ++at;
        }
        if (at >= word.size() || word[at] != text[i]) {
            return false;
        }
        ++at;
    }
    while (at < word.size() && (word[at] == '\'' || word[at] == '-')) {
        ++at;
    }
    return at == word.size();
}

bool readWholeFile(const char* path, std::vector<uint8_t>* out) {
    struct stat info {};
    if (stat(path, &info) != 0) {
        return false;
    }
    out->resize(static_cast<size_t>(info.st_size));
    const int fd = ::open(path, O_RDONLY);
    if (fd < 0) {
        return false;
    }
    const ssize_t got = ::read(fd, out->data(), out->size());
    ::close(fd);
    return got == static_cast<ssize_t>(out->size());
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: tcn_replay <pack.bkd> <weights.bkw> <layout> <gestures.csv>\n");
        return 2;
    }

    Layout layout;
    if (!loadLayout(argv[3], &layout)) {
        std::fprintf(stderr, "could not read the layout: %s\n", argv[3]);
        return 2;
    }
    std::vector<Gesture> gestures;
    if (!loadGestures(argv[4], &gestures)) {
        std::fprintf(stderr, "could not read the gestures: %s\n", argv[4]);
        return 2;
    }

    struct stat info {};
    if (stat(argv[1], &info) != 0) {
        std::fprintf(stderr, "could not stat the pack: %s\n", argv[1]);
        return 2;
    }
    Engine engine;
    if (!engine.create()) {
        std::fprintf(stderr, "could not create the engine\n");
        return 2;
    }
    const int fd = ::open(argv[1], O_RDONLY);
    if (fd < 0) {
        std::fprintf(stderr, "could not open the pack\n");
        return 2;
    }
    const int32_t status = engine.loadLanguage("replay", fd, 0, info.st_size, 1.0f);
    ::close(fd);
    if (status != kBkdOk) {
        std::fprintf(stderr, "the pack was refused: status %d\n", status);
        return 2;
    }
    const char* tags[1] = {"replay"};
    const float weights[1] = {1.0f};
    engine.setActiveLanguages(tags, weights, 1);
    engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                          layout.keyHeight);

    std::vector<uint8_t> weightBytes;
    if (!readWholeFile(argv[2], &weightBytes)) {
        std::fprintf(stderr, "could not read the weights: %s\n", argv[2]);
        return 2;
    }
    if (!engine.loadSwipeWeights(kReplayScript, weightBytes.data(), weightBytes.size())) {
        std::fprintf(stderr, "the weights file was refused: %s\n", argv[2]);
        return 2;
    }
    engine.selectSwipeScript(kReplayScript);
    engine.setSwipeModelEnabled(true);

    Candidate out[Engine::kMaxCandidates];
    std::chrono::steady_clock::duration decoding{};
    int decoded = 0;
    int geometric = 0;
    for (const Gesture& gesture : gestures) {
        int rank = -1;
        std::string top = borderkeys_test::kNoWord;
        if (gesture.xs.size() >= 2) {
            const auto started = std::chrono::steady_clock::now();
            const int found = engine.decodeGesture(
                gesture.xs.data(), gesture.ys.data(), gesture.times.data(),
                static_cast<int>(gesture.xs.size()), nullptr, 0, nullptr, 0, out,
                Engine::kMaxCandidates);
            decoding += std::chrono::steady_clock::now() - started;
            ++decoded;
            geometric += engine.lastDecodeUsedNeural() ? 0 : 1;
            for (int i = 0; i < found; ++i) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(out[i], &length);
                if (text != nullptr && sameWordMarksAside(text, length, gesture.word)) {
                    rank = i;
                    break;
                }
            }
            top = borderkeys_test::topWord(engine, out, found);
        }
        std::printf("%s\t%d\t%s\n", gesture.word.c_str(), rank, top.c_str());
    }
    if (decoded > 0) {
        const double millis = std::chrono::duration<double, std::milli>(decoding).count();
        std::fprintf(stderr, "decoded %d: %.2f ms per gesture; %d answered by the geometric decoder\n",
                     decoded, millis / decoded, geometric);
    }
    engine.destroy();
    return 0;
}
