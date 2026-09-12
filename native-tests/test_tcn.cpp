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
#include "gesture/tcn_ctc_decoder.hpp"
#include "gesture/tcn_decoder.hpp"
#include "gesture/tcn_encoder.hpp"
#include "gesture/tcn_features.hpp"
#include "gesture/tcn_weights.hpp"
#include "proximity.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using namespace borderkeys_test;

namespace {

/** A `.bkw` byte buffer with the right header and every weight zeroed -- the "inert" weights
 *  Phase 1's exit criterion is written against: not trained, but load-bearing and well-defined. */
std::vector<uint8_t> zeroWeightsFile() {
    std::vector<uint8_t> bytes(TcnWeights::kHeaderBytes + kTcnWeightsFloatCount * sizeof(float), 0);
    uint32_t magic = TcnWeights::kMagic;
    uint32_t version = TcnWeights::kVersion;
    std::memcpy(bytes.data(), &magic, sizeof(magic));
    std::memcpy(bytes.data() + sizeof(magic), &version, sizeof(version));
    return bytes;
}

}  // namespace

void runTcnTests() {
    section("time-uniform resampling");
    {
        // A straight horizontal line covered at a CONSTANT speed over 1000ms: every one of the
        // 64 output points should land at an evenly spaced time, and therefore an evenly spaced
        // position too, regardless of how unevenly the raw samples were captured.
        const float xs[] = {0.f, 10.f, 90.f, 100.f};
        const float ys[] = {0.f, 0.f, 0.f, 0.f};
        const int64_t ts[] = {0, 100, 900, 1000};
        float outX[kTcnTimesteps];
        float outY[kTcnTimesteps];
        check(resampleUniformTime(xs, ys, ts, 4, 100.f, 100.f, outX, outY),
              "a real trace resamples");
        checkNear(outX[0], 0.f, 0.01f, "the first point is the start, normalised");
        checkNear(outX[kTcnTimesteps - 1], 1.f, 0.01f, "the last point is the end, normalised");
        float maxError = 0.f;
        for (int i = 0; i < kTcnTimesteps; ++i) {
            const float expected = static_cast<float>(i) / static_cast<float>(kTcnTimesteps - 1);
            maxError = std::fmax(maxError, std::fabs(outX[i] - expected));
        }
        check(maxError < 0.02f, "points are evenly spaced in TIME, not in arc length");

        const int64_t zeroDuration[] = {5, 5};
        check(!resampleUniformTime(xs, ys, zeroDuration, 2, 100.f, 100.f, outX, outY),
              "a trace with zero duration is refused rather than resampled");
        check(!resampleUniformTime(xs, ys, ts, 1, 100.f, 100.f, outX, outY),
              "a single point is refused rather than resampled");
    }

    section("TCN feature vector");
    {
        // A straight line at constant speed: velocity should be constant and non-zero,
        // acceleration should be ~zero, and curvature should be ~zero (no turning).
        float xs[kTcnTimesteps];
        float ys[kTcnTimesteps];
        for (int i = 0; i < kTcnTimesteps; ++i) {
            xs[i] = static_cast<float>(i) / static_cast<float>(kTcnTimesteps - 1);
            ys[i] = 0.3f;
        }
        float features[kTcnTimesteps * kTcnFeatureDim];
        buildTcnFeatures(xs, ys, kTcnTimesteps, features);
        const int mid = kTcnTimesteps / 2;
        const float* const f = features + mid * kTcnFeatureDim;
        check(f[2] > 0.01f, "velocity along the line of travel is non-zero");
        checkNear(f[3], 0.f, 1e-4f, "no velocity perpendicular to a straight line");
        checkNear(f[4], 0.f, 1e-3f, "no acceleration at constant speed");
        checkNear(f[7], 0.f, 1e-3f, "no curvature on a straight line");

        // A stationary point: every derivative is exactly zero, and nothing divides by zero.
        for (int i = 0; i < kTcnTimesteps; ++i) {
            xs[i] = 0.5f;
            ys[i] = 0.5f;
        }
        buildTcnFeatures(xs, ys, kTcnTimesteps, features);
        bool allFinite = true;
        bool allZeroMotion = true;
        for (int i = 0; i < kTcnTimesteps; ++i) {
            const float* const g = features + i * kTcnFeatureDim;
            for (int d = 2; d < kTcnFeatureDim; ++d) {
                if (!std::isfinite(g[d])) allFinite = false;
                if (std::fabs(g[d]) > 1e-6f) allZeroMotion = false;
            }
        }
        check(allFinite, "a stationary point produces finite features, not NaN from atan2(0,0)");
        check(allZeroMotion, "a stationary point has zero velocity, acceleration and curvature");
    }

    section("TCN weight file format");
    {
        const std::vector<uint8_t> good = zeroWeightsFile();
        TcnWeights weights;
        check(weights.loadFromBytes(good.data(), good.size()), "a well-formed .bkw file loads");

        std::vector<uint8_t> badMagic = good;
        badMagic[0] ^= 0xFF;
        check(!weights.loadFromBytes(badMagic.data(), badMagic.size()),
              "a wrong magic is refused");

        std::vector<uint8_t> badVersion = good;
        badVersion[4] ^= 0xFF;
        check(!weights.loadFromBytes(badVersion.data(), badVersion.size()),
              "a wrong version is refused");

        std::vector<uint8_t> truncated(good.begin(), good.end() - 4);
        check(!weights.loadFromBytes(truncated.data(), truncated.size()),
              "a truncated file is refused rather than read short");
    }

    section("TCN encoder forward pass");
    {
        const std::vector<uint8_t> zeroed = zeroWeightsFile();
        TcnWeights weights;
        check(weights.loadFromBytes(zeroed.data(), zeroed.size()), "zeroed weights load");

        TcnEncoder encoder;
        encoder.setWeights(&weights);
        float features[kTcnTimesteps * kTcnFeatureDim] = {};
        float intention[TcnEncoder::kOutputTimesteps];
        float spectral[TcnEncoder::kOutputTimesteps * TcnEncoder::kSpectralDim];
        encoder.forward(features, intention, spectral);

        bool allFinite = true;
        for (float v : intention) {
            if (!std::isfinite(v)) allFinite = false;
        }
        for (float v : spectral) {
            if (!std::isfinite(v)) allFinite = false;
        }
        check(allFinite, "an all-zero-weight forward pass produces finite output, not NaN");

        // Every bias, weight, running-stat and gate is zero, so every path through the block
        // (depthwise conv, the two 1x1s, GRN, SE) computes zero, and the intention head's sigmoid
        // of zero is exactly one half.
        bool intentionIsHalf = true;
        for (float v : intention) {
            if (std::fabs(v - 0.5f) > 1e-4f) intentionIsHalf = false;
        }
        check(intentionIsHalf, "zero weights produce sigmoid(0) = 0.5 intention, exactly");
    }

    section("layout-agnostic spatial head");
    {
        TestLayout layout;
        KeyGeometry geometry;
        check(geometry.set(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                           layout.keyHeight),
              "the geometry is set");
        TcnCtcDecoder decoder;
        decoder.setLayout(geometry);
        check(decoder.areaWidth() > 0.f && decoder.areaHeight() > 0.f,
              "the key-area extent is derived from the geometry, not left at zero");
    }

    section("decoding end to end with inert (zeroed) weights");
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

        TcnDecoder tcn(engine);
        tcn.setLayout(engine.geometry());
        const std::vector<uint8_t> zeroed = zeroWeightsFile();
        check(tcn.loadWeights(zeroed.data(), zeroed.size()), "inert weights load into the decoder");

        Random random(2026u);
        std::vector<float> xs;
        std::vector<float> ys;
        std::vector<int64_t> times;
        synthesiseGesture(layout, "test", 0.f, random, xs, ys, times);
        check(xs.size() >= 2, "a real gesture was synthesised");

        Candidate out[8];
        const int found = tcn.decode(xs.data(), ys.data(), times.data(),
                                     static_cast<int>(xs.size()), out, 8);
        check(found >= 0, "decoding with inert weights does not crash");
        for (int i = 0; i < found; ++i) {
            check(std::isfinite(out[i].score), "every returned score is finite");
        }

        Candidate out2[8];
        const int foundBeforeWeights =
            TcnDecoder(engine).decode(xs.data(), ys.data(), times.data(),
                                      static_cast<int>(xs.size()), out2, 8);
        check(foundBeforeWeights == 0, "decoding before any weights are loaded returns nothing");
    }
}
