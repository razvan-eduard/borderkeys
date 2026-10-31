// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_REPLAY_CORPORA_HPP
#define BORDERKEYS_REPLAY_CORPORA_HPP

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "engine.hpp"

// The files the replay tools read: a keyboard layout (data/*_1080.layout), recorded or synthesised
// swipes (tools/gesture_replay.py's corpus format) and tapped words (tools/make_tap_corpus.py's).

namespace borderkeys_eval {

/** A layout file's keys, each code with its centre, and its key size, in pixels. */
struct Layout {
    int32_t codes[64] = {};
    float xs[64] = {};
    float ys[64] = {};
    int count = 0;
    float keyWidth = 0.f;
    float keyHeight = 0.f;

    /** The index of the key whose code is the byte [letter]; -1 for none. */
    int indexOf(char letter) const {
        for (int i = 0; i < count; ++i) {
            if (codes[i] == static_cast<unsigned char>(letter)) {
                return i;
            }
        }
        return -1;
    }
};

/** The code point [text] starts with, as UTF-8; the first byte when it is not valid UTF-8. */
inline int32_t firstCodePoint(const char* text) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(text);
    const unsigned char lead = bytes[0];
    int extra = 0;
    int32_t value = lead;
    if ((lead & 0xE0u) == 0xC0u) {
        extra = 1;
        value = lead & 0x1Fu;
    } else if ((lead & 0xF0u) == 0xE0u) {
        extra = 2;
        value = lead & 0x0Fu;
    } else if ((lead & 0xF8u) == 0xF0u) {
        extra = 3;
        value = lead & 0x07u;
    }
    for (int i = 1; i <= extra; ++i) {
        if ((bytes[i] & 0xC0u) != 0x80u) {
            return lead;
        }
        value = (value << 6) | (bytes[i] & 0x3Fu);
    }
    return value;
}

/**
 * Reads a layout file: its key size, then each key's code and centre, the code being the code
 * point its label opens with, as the keyboard's key codes are.
 */
inline bool loadLayout(const char* path, Layout* layout) {
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
            layout->codes[layout->count] = firstCodePoint(code);
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

inline bool loadGestures(const char* path, std::vector<Gesture>* gestures) {
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

/** A word of a tap corpus: the word meant, the letters typed, and where each one was tapped. */
struct TappedWord {
    std::string intended;
    std::string typed;
    std::vector<float> xs;
    std::vector<float> ys;
};

inline std::vector<TappedWord> readTapCorpus(const char* path) {
    std::vector<TappedWord> words;
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
        TappedWord word;
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

/** The fewest letters a word typed with a slip is measured at. */
inline constexpr size_t kShortestSlip = 3;

/** Whether [word] was typed other than meant, in at least kShortestSlip letters. */
inline bool typedWithSlip(const TappedWord& word) {
    return word.typed != word.intended && word.typed.size() >= kShortestSlip;
}

/** Whether a dictionary of [engine] spells [typed] exactly as typed. */
inline bool spelledAsTyped(const borderkeys::Engine& engine, const std::string& typed) {
    char spelling[128];
    const int spelled =
        engine.knownSpelling(typed.c_str(), typed.size(), spelling, sizeof(spelling) - 1);
    return spelled == static_cast<int>(typed.size()) &&
           std::memcmp(spelling, typed.c_str(), typed.size()) == 0;
}

}  // namespace borderkeys_eval

#endif  // BORDERKEYS_REPLAY_CORPORA_HPP
