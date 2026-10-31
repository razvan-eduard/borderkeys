// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


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

// The binary tools/gesture_replay.py drives.
//
// It prints one `word<TAB>rank<TAB>top` line per gesture, where rank is the zero-based position of
// the expected word among the candidates or -1 for a miss, and top the candidate ranked first. The
// script does the arithmetic and the comparison against the recorded baseline.
//
//   gesture_replay <pack.bkd> <layout> <gestures.csv>

namespace {

using namespace borderkeys;
using borderkeys_eval::Gesture;
using borderkeys_eval::Layout;
using borderkeys_eval::loadGestures;
using borderkeys_eval::loadLayout;

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

}  // namespace

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: gesture_replay <pack.bkd> <layout> <gestures.csv>\n");
        return 2;
    }

    Layout layout;
    if (!loadLayout(argv[2], &layout)) {
        std::fprintf(stderr, "could not read the layout: %s\n", argv[2]);
        return 2;
    }
    std::vector<Gesture> gestures;
    if (!loadGestures(argv[3], &gestures)) {
        std::fprintf(stderr, "could not read the gestures: %s\n", argv[3]);
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

    Candidate out[Engine::kMaxCandidates];
    for (const Gesture& gesture : gestures) {
        int rank = -1;
        std::string top = borderkeys_test::kNoWord;
        if (gesture.xs.size() >= 2) {
            const int found = engine.decodeGesture(
                gesture.xs.data(), gesture.ys.data(), gesture.times.data(),
                static_cast<int>(gesture.xs.size()), nullptr, 0, nullptr, 0, out,
                Engine::kMaxCandidates);
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
    engine.destroy();
    return 0;
}
