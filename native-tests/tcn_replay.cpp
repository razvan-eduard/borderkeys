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
#include "gesture/tcn_decoder.hpp"

// tools/tcn_replay.py's sibling to gesture_replay.cpp: same layout/corpus formats and the same
// `word<TAB>rank` output contract, so the two scripts' comparison-against-baseline logic does
// not need to be written twice. The two binaries differ only in which GestureDecoder they drive
// -- this one constructs a TcnDecoder directly rather than going through Engine::create(), which
// still only ever builds Shark2Decoder (see engine.cpp; that routing decision is Phase 3's,
// deferred until real accuracy numbers exist -- this binary is how they get produced).
//
//   tcn_replay <pack.bkd> <weights.bkw> <layout> <gestures.csv>
//
// A pack is still required: TcnCtcDecoder's beam search is lexicon-constrained the same way
// Shark2Decoder's is (see tcn_ctc_decoder.hpp), so it needs a trie to walk, not just weights.

namespace {

using namespace borderkeys;

struct Layout {
    int32_t codes[64] = {};
    float xs[64] = {};
    float ys[64] = {};
    int count = 0;
    float keyWidth = 0.f;
    float keyHeight = 0.f;
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

struct Gesture {
    std::string id;
    std::string word;
    std::vector<float> xs;
    std::vector<float> ys;
    std::vector<int64_t> times;
};

bool loadGestures(const char* path, std::vector<Gesture>* gestures) {
    std::FILE* const file = std::fopen(path, "r");
    if (file == nullptr) {
        return false;
    }
    char line[512];
    while (std::fgets(line, sizeof(line), file) != nullptr) {
        if (line[0] == '#' || std::strncmp(line, "id,", 3) == 0) {
            continue;
        }
        char id[64];
        char word[128];
        float x = 0.f;
        float y = 0.f;
        long long t = 0;
        if (std::sscanf(line, "%63[^,],%127[^,],%f,%f,%lld", id, word, &x, &y, &t) != 5) {
            continue;
        }
        if (gestures->empty() || gestures->back().id != id) {
            gestures->push_back(Gesture{id, word, {}, {}, {}});
        }
        gestures->back().xs.push_back(x);
        gestures->back().ys.push_back(y);
        gestures->back().times.push_back(t);
    }
    std::fclose(file);
    return !gestures->empty();
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
    TcnDecoder decoder(engine);
    decoder.setLayout(engine.geometry());
    if (!decoder.loadWeights(weightBytes.data(), weightBytes.size())) {
        std::fprintf(stderr, "the weights file was refused: %s\n", argv[2]);
        return 2;
    }

    Candidate out[Engine::kMaxCandidates];
    for (const Gesture& gesture : gestures) {
        int rank = -1;
        if (gesture.xs.size() >= 2) {
            const int found = decoder.decode(gesture.xs.data(), gesture.ys.data(),
                                             gesture.times.data(),
                                             static_cast<int>(gesture.xs.size()), out,
                                             Engine::kMaxCandidates);
            for (int i = 0; i < found; ++i) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(out[i], &length);
                if (text != nullptr && length == gesture.word.size() &&
                    std::memcmp(text, gesture.word.data(), length) == 0) {
                    rank = i;
                    break;
                }
            }
        }
        std::printf("%s\t%d\n", gesture.word.c_str(), rank);
    }
    engine.destroy();
    return 0;
}
