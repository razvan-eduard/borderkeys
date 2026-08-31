// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "engine.hpp"

// The binary tools/gesture_replay.py drives.
//
// It prints one `word<TAB>rank` line per gesture, where rank is the zero-based position of the
// expected word among the candidates or -1 for a miss. The script owns the arithmetic and the
// comparison against the recorded baseline; this owns nothing but the decoding, so that the
// measurement and the thing being measured stay separable.
//
//   gesture_replay <pack.bkd> <layout> <gestures.csv>

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
        if (gesture.xs.size() >= 2) {
            const int found = engine.decodeGesture(
                gesture.xs.data(), gesture.ys.data(), gesture.times.data(),
                static_cast<int>(gesture.xs.size()), nullptr, 0, nullptr, 0, out,
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
