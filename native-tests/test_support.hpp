// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEST_SUPPORT_HPP
#define BORDERKEYS_TEST_SUPPORT_HPP

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

// The whole test framework. A counter, a macro and a name.
//
// Everything a larger framework adds -- fixtures, parameterisation, mocking, discovery -- is
// weight this does not need: these tests take a pack and an engine and assert about numbers.

namespace borderkeys_test {

inline int failures = 0;
inline int checks = 0;
inline const char* currentSection = "";

inline void section(const char* name) {
    currentSection = name;
    std::printf("\n== %s ==\n", name);
}

inline void check(bool condition, const char* what) {
    ++checks;
    if (condition) {
        std::printf("  ok   %s\n", what);
    } else {
        ++failures;
        std::printf("  FAIL %s\n", what);
    }
}

inline void checkNear(float actual, float expected, float tolerance, const char* what) {
    check(std::fabs(actual - expected) <= tolerance, what);
}

/** Reads a whole file. Returns an empty string when it is missing. */
inline std::string readFile(const char* path) {
    std::string out;
    std::FILE* const file = std::fopen(path, "rb");
    if (file == nullptr) {
        return out;
    }
    char buffer[8192];
    size_t read;
    while ((read = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
        out.append(buffer, read);
    }
    std::fclose(file);
    return out;
}

inline bool writeFile(const char* path, const std::string& contents) {
    std::FILE* const file = std::fopen(path, "wb");
    if (file == nullptr) {
        return false;
    }
    const size_t written = std::fwrite(contents.data(), 1, contents.size(), file);
    std::fclose(file);
    return written == contents.size();
}

/**
 * A phone QWERTY in pixels: 1080 wide, 108 by 160 keys, the usual row stagger.
 *
 * The same arrangement native-tests/data/qwerty_1080.layout describes, so a gesture recorded
 * against that file and a gesture synthesised here are in the same coordinate space.
 */
struct TestLayout {
    int32_t codes[64] = {};
    float xs[64] = {};
    float ys[64] = {};
    int count = 0;
    float keyWidth = 108.f;
    float keyHeight = 160.f;

    TestLayout() {
        const char* rows[3] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        const float indent[3] = {0.f, 0.5f, 1.5f};
        for (int r = 0; r < 3; ++r) {
            for (const char* p = rows[r]; *p != '\0'; ++p) {
                codes[count] = *p;
                xs[count] = (indent[r] + static_cast<float>(p - rows[r]) + 0.5f) * keyWidth;
                ys[count] = (static_cast<float>(r) + 0.5f) * keyHeight;
                ++count;
            }
        }
    }

    bool centreOf(char character, float* x, float* y) const {
        for (int i = 0; i < count; ++i) {
            if (codes[i] == character) {
                *x = xs[i];
                *y = ys[i];
                return true;
            }
        }
        return false;
    }
};

/** A small deterministic generator. std::rand differs between libraries; this does not. */
class Random {
public:
    explicit Random(uint32_t seed) : state_(seed) {}
    uint32_t next() {
        state_ = state_ * 1664525u + 1013904223u;
        return state_;
    }
    float unit() { return static_cast<float>((next() >> 8) & 0xFFFFu) / 65535.f; }
    int below(int bound) { return bound <= 0 ? 0 : static_cast<int>(next() % static_cast<uint32_t>(bound)); }

private:
    uint32_t state_;
};

/**
 * Synthesises a swipe over a word: interpolate through the key centres, jitter, sample
 * unevenly.
 *
 * Not a substitute for a recording -- a real finger overshoots corners and slows before a turn
 * in ways this does not reproduce. It is what makes the decoder testable at all without a
 * device, and the corpus format is the same either way, so recordings drop straight in.
 */
inline void synthesiseGesture(const TestLayout& layout, const char* word, float jitter,
                              Random& random, std::vector<float>& xs, std::vector<float>& ys,
                              std::vector<int64_t>& times) {
    xs.clear();
    ys.clear();
    times.clear();
    std::vector<float> anchorX;
    std::vector<float> anchorY;
    for (const char* p = word; *p != '\0'; ++p) {
        float x = 0.f;
        float y = 0.f;
        if (!layout.centreOf(*p, &x, &y)) {
            return;
        }
        if (!anchorX.empty() && anchorX.back() == x && anchorY.back() == y) {
            continue;  // a doubled letter is one pass over the key
        }
        anchorX.push_back(x);
        anchorY.push_back(y);
    }
    if (anchorX.size() < 2) {
        return;
    }
    int64_t time = 0;
    for (size_t s = 0; s + 1 < anchorX.size(); ++s) {
        const float dx = anchorX[s + 1] - anchorX[s];
        const float dy = anchorY[s + 1] - anchorY[s];
        const int steps = 8 + static_cast<int>(std::sqrt(dx * dx + dy * dy) / 18.f);
        for (int k = 0; k < steps; ++k) {
            const float u = static_cast<float>(k) / static_cast<float>(steps);
            xs.push_back(anchorX[s] + dx * u + (random.unit() - 0.5f) * 2.f * jitter);
            ys.push_back(anchorY[s] + dy * u + (random.unit() - 0.5f) * 2.f * jitter);
            time += 8 + static_cast<int64_t>(random.unit() * 6.f);
            times.push_back(time);
        }
    }
    xs.push_back(anchorX.back());
    ys.push_back(anchorY.back());
    times.push_back(time + 10);
}

}  // namespace borderkeys_test

#endif  // BORDERKEYS_TEST_SUPPORT_HPP
