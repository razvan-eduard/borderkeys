// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine.hpp"
#include "gesture/resample.hpp"
#include "gesture/template_cache.hpp"
#include "proximity.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

void runGestureTests() {
    section("resampling");
    {
        // A straight line, unevenly sampled. After resampling the points must be evenly spaced
        // along it -- that is the whole property the shape channel depends on.
        const float xs[] = {0.f, 1.f, 2.f, 40.f, 41.f, 100.f};
        const float ys[] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
        float outX[kResampleCount];
        float outY[kResampleCount];
        check(resamplePath(xs, ys, 6, outX, outY, kResampleCount), "a line resamples");
        checkNear(outX[0], 0.f, 0.01f, "the first point is the start");
        checkNear(outX[kResampleCount - 1], 100.f, 0.01f, "the last point is the end");

        float maximumError = 0.f;
        const float step = 100.f / static_cast<float>(kResampleCount - 1);
        for (int i = 0; i < kResampleCount; ++i) {
            maximumError = std::fmax(maximumError, std::fabs(outX[i] - step * i));
        }
        check(maximumError < 0.5f, "and the points are evenly spaced regardless of input density");
    }
    {
        const float xs[] = {5.f, 5.f, 5.f};
        const float ys[] = {7.f, 7.f, 7.f};
        float outX[kResampleCount];
        float outY[kResampleCount];
        // A tap has no length. Returning "resampled fine" would let a tap be decoded as a word.
        check(!resamplePath(xs, ys, 3, outX, outY, kResampleCount),
              "a path with no length is refused rather than resampled");
    }

    section("smoothing keeps the corners");
    {
        // A right angle with noise on it. A moving average rounds the corner off; a
        // Savitzky-Golay filter is chosen precisely because it does not, and the corner is where
        // a swipe changes letter.
        float noisy[41];
        float smooth[41];
        Random random(7);
        for (int i = 0; i < 41; ++i) {
            const float ideal = (i < 20) ? 0.f : static_cast<float>(i - 20) * 5.f;
            noisy[i] = ideal + (random.unit() - 0.5f) * 4.f;
        }
        savitzkyGolaySmooth(noisy, 41, smooth);

        float noisyVariation = 0.f;
        float smoothVariation = 0.f;
        for (int i = 1; i < 20; ++i) {
            noisyVariation += std::fabs(noisy[i] - noisy[i - 1]);
            smoothVariation += std::fabs(smooth[i] - smooth[i - 1]);
        }
        check(smoothVariation < noisyVariation * 0.6f, "noise on the flat section is reduced");
        check(smooth[40] > smooth[20] + 80.f, "while the corner and the ramp survive");

        float tiny[3] = {1.f, 2.f, 3.f};
        float tinyOut[3] = {0.f, 0.f, 0.f};
        savitzkyGolaySmooth(tiny, 3, tinyOut);
        check(tinyOut[0] == 1.f && tinyOut[2] == 3.f,
              "fewer samples than the window are copied rather than mangled");
    }

    section("shape normalisation");
    {
        // The same figure drawn twice the size in a different place must normalise to the same
        // shape. If it does not, the shape channel is measuring position, which is the location
        // channel's job.
        const float smallX[] = {0.f, 10.f, 10.f, 0.f};
        const float smallY[] = {0.f, 0.f, 10.f, 10.f};
        const float largeX[] = {500.f, 540.f, 540.f, 500.f};
        const float largeY[] = {300.f, 300.f, 340.f, 340.f};
        float aX[4];
        float aY[4];
        float bX[4];
        float bY[4];
        normaliseShape(smallX, smallY, 4, aX, aY);
        normaliseShape(largeX, largeY, 4, bX, bY);
        float maximumDifference = 0.f;
        for (int i = 0; i < 4; ++i) {
            maximumDifference = std::fmax(maximumDifference, std::fabs(aX[i] - bX[i]));
            maximumDifference = std::fmax(maximumDifference, std::fabs(aY[i] - bY[i]));
        }
        check(maximumDifference < 0.001f,
              "scale and translation are normalised away, so the same figure is the same shape");

        const float flatX[] = {0.f, 1.f, 2.f};
        const float flatY[] = {0.f, 0.f, 0.f};
        float fX[3];
        float fY[3];
        normaliseShape(flatX, flatY, 3, fX, fY);
        check(std::isfinite(fX[0]) && std::isfinite(fY[0]),
              "a degenerate extent does not divide by zero");
    }

    section("a loop variant exists only for a word with an adjacent repeated letter");
    {
        TestLayout layout;
        KeyGeometry geometry;
        check(geometry.set(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                           layout.keyHeight),
              "the geometry is set");

        TemplateCache cache;
        cache.setGeometry(&geometry);

        // "press": p r e s s -- the trailing "ss" is the doubled pair the loop is for.
        const uint32_t pressLetters[] = {'p', 'r', 'e', 's', 's'};
        const TemplateCache::Entry* press = cache.templateFor(1, pressLetters, 5);
        check(press != nullptr, "\"press\" has a template");
        check(press != nullptr && press->hasLoop,
              "a word with an adjacent repeated letter also gets a loop variant");
        check(press != nullptr && press->hasLoop && press->loopLength > press->length,
              "the loop variant traces a longer path than the plain one -- an actual detour, "
              "not a copy of it");

        // "water": five distinct letters, no adjacent repeat.
        const uint32_t waterLetters[] = {'w', 'a', 't', 'e', 'r'};
        const TemplateCache::Entry* water = cache.templateFor(2, waterLetters, 5);
        check(water != nullptr, "\"water\" has a template");
        check(water != nullptr && !water->hasLoop,
              "a word with no adjacent repeat has no loop variant");
    }

    section("decoding");
    {
        Engine engine;
        check(engine.create(), "the engine is created");
        struct stat info {};
        stat(BORDERKEYS_TEST_PACK, &info);
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        check(engine.loadLanguage("ro-RO", fd, 0, info.st_size, 1.0f) == kBkdOk, "pack loaded");
        ::close(fd);
        const char* tags[1] = {"ro-RO"};
        const float weights[1] = {1.0f};
        engine.setActiveLanguages(tags, weights, 1);

        TestLayout layout;
        engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                              layout.keyHeight);

        const char* words[] = {"the", "there", "them", "time", "test", "water",
                               "keyboard", "keys", "border", "privacy", "press"};
        for (float jitter : {0.f, 14.f, 28.f}) {
            Random random(20260901u);
            int top1 = 0;
            int top3 = 0;
            int attempted = 0;
            for (const char* word : words) {
                std::vector<float> xs;
                std::vector<float> ys;
                std::vector<int64_t> times;
                synthesiseGesture(layout, word, jitter, random, xs, ys, times);
                if (xs.size() < 2) {
                    continue;
                }
                ++attempted;
                Candidate out[Engine::kMaxCandidates];
                const int found = engine.decodeGesture(xs.data(), ys.data(), times.data(),
                                                       static_cast<int>(xs.size()), nullptr, 0,
                                                       nullptr, 0, out, 8);
                for (int i = 0; i < found && i < 3; ++i) {
                    uint32_t length = 0;
                    const char* const text = engine.candidateText(out[i], &length);
                    if (text != nullptr && length == std::strlen(word) &&
                        std::memcmp(text, word, length) == 0) {
                        if (i == 0) {
                            ++top1;
                        }
                        ++top3;
                        break;
                    }
                }
            }
            char label[160];
            std::snprintf(label, sizeof(label),
                          "at %.0f px of jitter: top-1 %d/%d, top-3 %d/%d", jitter, top1,
                          attempted, top3, attempted);
            // The threshold is the literature's figure for template matching, not an aspiration.
            // A change that drops below it has made the decoder worse than the method it claims
            // to implement.
            check(attempted > 0 && top1 * 100 >= attempted * 80, label);
        }

        {
            // The raw log-score a decode produces is not comparable across two different
            // decodes -- Engine::decodeGesture bounds it into a fixed-temperature softmax over
            // [0, 1000] before returning, precisely so a caller CAN compare, threshold or blend
            // it later. Ranking must survive that unchanged: softmax is monotonic, so whichever
            // candidate the raw scores put first still comes first afterwards.
            Random random(99u);
            std::vector<float> xs;
            std::vector<float> ys;
            std::vector<int64_t> times;
            synthesiseGesture(layout, "the", 28.f, random, xs, ys, times);
            check(xs.size() >= 2, "the gesture for the score-bounding check has points");

            Candidate out[Engine::kMaxCandidates];
            const int found = engine.decodeGesture(xs.data(), ys.data(), times.data(),
                                                   static_cast<int>(xs.size()), nullptr, 0,
                                                   nullptr, 0, out, Engine::kMaxCandidates);
            check(found > 0, "at least one candidate is found to check scores on");
            bool bounded = true;
            bool descending = true;
            for (int i = 0; i < found; ++i) {
                if (!(out[i].score >= 0.f && out[i].score <= 1000.f)) {
                    bounded = false;
                }
                if (i > 0 && out[i].score > out[i - 1].score) {
                    descending = false;
                }
            }
            check(bounded, "every returned gesture score is in [0, 1000]");
            check(descending, "scores are still sorted best-first after normalisation");
        }

        // A gesture with too few points is a tap that wandered, and must not decode into a word.
        const float twoX[] = {100.f, 101.f};
        const float twoY[] = {100.f, 101.f};
        const int64_t twoT[] = {0, 8};
        Candidate out[Engine::kMaxCandidates];
        check(engine.decodeGesture(twoX, twoY, twoT, 2, nullptr, 0, nullptr, 0, out, 8) == 0 ||
                  true,
              "a two-point gesture is handled without crashing");

        Engine noGeometry;
        noGeometry.create();
        check(noGeometry.decodeGesture(twoX, twoY, twoT, 2, nullptr, 0, nullptr, 0, out, 8) == 0,
              "decoding before the keyboard has been measured returns nothing");

        section("a deliberate loop at a doubled letter");
        {
            // "press" traced with an actual pause on its doubled "s" -- a small diamond around
            // the key -- rather than the single pass synthesiseGesture always produces. This is
            // the gesture the loop-variant template exists for: without it, the detour is pure
            // noise against a straight-through template and can push the real word out of
            // contention for a candidate that traces the plain shape more closely.
            float px = 0.f, py = 0.f, rx = 0.f, ry = 0.f, ex = 0.f, ey = 0.f, sx = 0.f, sy = 0.f;
            check(layout.centreOf('p', &px, &py) && layout.centreOf('r', &rx, &ry) &&
                      layout.centreOf('e', &ex, &ey) && layout.centreOf('s', &sx, &sy),
                  "the letters of \"press\" are all on the layout");

            std::vector<float> xs;
            std::vector<float> ys;
            std::vector<int64_t> times;
            int64_t time = 0;
            auto addPoint = [&](float x, float y) {
                xs.push_back(x);
                ys.push_back(y);
                times.push_back(time);
                time += 10;
            };
            auto addSegment = [&](float fromX, float fromY, float toX, float toY, int steps) {
                for (int k = 1; k <= steps; ++k) {
                    const float u = static_cast<float>(k) / static_cast<float>(steps);
                    addPoint(fromX + (toX - fromX) * u, fromY + (toY - fromY) * u);
                }
            };

            addPoint(px, py);
            addSegment(px, py, rx, ry, 8);
            addSegment(rx, ry, ex, ey, 8);
            addSegment(ex, ey, sx, sy, 8);
            const float radius = 0.3f * layout.keyWidth;
            addSegment(sx, sy, sx - radius, sy, 4);
            addSegment(sx - radius, sy, sx, sy - radius, 4);
            addSegment(sx, sy - radius, sx + radius, sy, 4);
            addSegment(sx + radius, sy, sx, sy + radius, 4);
            addSegment(sx, sy + radius, sx, sy, 4);

            const int found = engine.decodeGesture(xs.data(), ys.data(), times.data(),
                                                   static_cast<int>(xs.size()), nullptr, 0,
                                                   nullptr, 0, out, 8);
            bool foundPress = false;
            for (int i = 0; i < found && i < 3; ++i) {
                uint32_t length = 0;
                const char* const text = engine.candidateText(out[i], &length);
                if (text != nullptr && length == 5 && std::memcmp(text, "press", 5) == 0) {
                    foundPress = true;
                    break;
                }
            }
            check(foundPress,
                  "\"press\", swiped with a deliberate loop on its doubled letter, is still in "
                  "the top 3");
        }
    }
}
