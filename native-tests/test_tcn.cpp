// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <memory>
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

/** Whole file into `out`, or false. */
bool readWholeFile(const char* path, std::vector<uint8_t>* out) {
    std::FILE* const file = std::fopen(path, "rb");
    if (file == nullptr) {
        return false;
    }
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::fseek(file, 0, SEEK_SET);
    if (size <= 0) {
        std::fclose(file);
        return false;
    }
    out->resize(static_cast<size_t>(size));
    const size_t read = std::fread(out->data(), 1, out->size(), file);
    std::fclose(file);
    return read == out->size();
}

/** A `.bkw` byte buffer with the right header and every weight zeroed -- the "inert" weights
 *  Phase 1's exit criterion is written against: not trained, but load-bearing and well-defined. */
std::vector<uint8_t> zeroWeightsFile() {
    std::vector<uint8_t> bytes(TcnWeights::kHeaderBytes + kTcnWeightsFloatCount * sizeof(float), 0);
    TcnWeights::writeHeader(bytes.data());
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
        // Heap, not a local: TcnWeights is ~2.5 MB, and this function already declares several of
        // these across its sections -- summed as one stack frame, at -O0, that overflows even an
        // 8 MB thread stack. Same reasoning as tcn_decoder.cpp's loadWeights().
        auto weights = std::make_unique<TcnWeights>();
        check(weights->loadFromBytes(good.data(), good.size()), "a well-formed .bkw file loads");

        std::vector<uint8_t> badMagic = good;
        badMagic[0] ^= 0xFF;
        check(!weights->loadFromBytes(badMagic.data(), badMagic.size()),
              "a wrong magic is refused");

        std::vector<uint8_t> badVersion = good;
        badVersion[4] ^= 0xFF;
        check(!weights->loadFromBytes(badVersion.data(), badVersion.size()),
              "a wrong version is refused");

        std::vector<uint8_t> truncated(good.begin(), good.end() - 4);
        check(!weights->loadFromBytes(truncated.data(), truncated.size()),
              "a truncated file is refused rather than read short");

        check(TcnWeights::describeMismatch(good.data(), good.size()) == nullptr,
              "a well-formed file names no mismatch");

        // Every descriptor word, one at a time. The payload is read positionally, so a file
        // exported for another shape would otherwise load as a different network.
        for (int field = 0; field < TcnWeights::kDescriptorFields; ++field) {
            std::vector<uint8_t> bent = good;
            const size_t at = (2 + static_cast<size_t>(field)) * sizeof(uint32_t);
            bent[at] = static_cast<uint8_t>(bent[at] + 1);
            const char* const named = TcnWeights::describeMismatch(bent.data(), bent.size());
            check(named != nullptr && !weights->loadFromBytes(bent.data(), bent.size()),
                  "a descriptor word that disagrees is refused, by name");
        }

        std::vector<uint8_t> badCount = good;
        const size_t countAt = (2 + TcnWeights::kDescriptorFields) * sizeof(uint32_t);
        badCount[countAt] = static_cast<uint8_t>(badCount[countAt] + 1);
        check(!weights->loadFromBytes(badCount.data(), badCount.size()),
              "a float count that disagrees with the architecture is refused");
    }

    section("the shipped weights load");
    {
        // tools/swipe_model/export_weights.py writes this file and TcnWeights reads it: two
        // implementations of one format, in two languages, agreeing only because both are
        // written against the same architecture. This is what catches them drifting apart.
        std::vector<uint8_t> shipped;
        if (!readWholeFile(BORDERKEYS_SWIPE_MODEL, &shipped)) {
            check(false, "the shipped model.bkw is readable");
        } else {
            const char* const named = TcnWeights::describeMismatch(shipped.data(), shipped.size());
            if (named != nullptr) {
                std::printf("      shipped model disagrees on: %s\n", named);
            }
            check(named == nullptr, "the shipped model.bkw matches this architecture");
            auto weights = std::make_unique<TcnWeights>();
            check(weights->loadFromBytes(shipped.data(), shipped.size()),
                  "and it loads");
        }
    }

    section("the shipped weights compute what model.py computes");
    {
        // The payload is read positionally, so two same-shaped arrays written in the wrong order
        // load at the same byte length and pass magic, version and every descriptor field.
        // tools/swipe_model/export_weights.py --golden records model.py's output for a fixed
        // input; this runs the C++ encoder on the same input with the same file. They agree only
        // when each array is where both sides expect it.
        std::vector<uint8_t> golden;
        std::vector<uint8_t> shipped;
        if (!readWholeFile(BORDERKEYS_TEST_DATA "/tcn_golden.bin", &golden) ||
            !readWholeFile(BORDERKEYS_SWIPE_MODEL, &shipped)) {
            check(false, "the golden vector and the shipped model.bkw are readable");
        } else {
            uint32_t header[6] = {};
            const size_t features = kTcnTimesteps * kTcnFeatureDim;
            const size_t spectralCount =
                TcnEncoder::kOutputTimesteps * TcnEncoder::kSpectralDim;
            const size_t expected = sizeof(header) +
                sizeof(float) * (features + TcnEncoder::kOutputTimesteps + spectralCount);
            check(golden.size() == expected, "the golden vector is the size its shapes imply");
            std::memcpy(header, golden.data(), sizeof(header));
            check(header[0] == 0x31474B42u && header[1] == 1u,
                  "the golden vector's magic and version are current");
            check(header[2] == kTcnTimesteps && header[3] == kTcnFeatureDim &&
                      header[4] == TcnEncoder::kOutputTimesteps &&
                      header[5] == TcnEncoder::kSpectralDim,
                  "and it was written for this architecture");

            const float* const payload =
                reinterpret_cast<const float*>(golden.data() + sizeof(header));
            auto weights = std::make_unique<TcnWeights>();
            check(weights->loadFromBytes(shipped.data(), shipped.size()),
                  "the shipped weights load for the comparison");

            auto encoder = std::make_unique<TcnEncoder>();
            encoder->setWeights(weights.get());
            std::vector<float> intention(TcnEncoder::kOutputTimesteps);
            std::vector<float> spectral(spectralCount);
            encoder->forward(payload, intention.data(), spectral.data());

            // Both tracks are compared absolutely: the intention head is a sigmoid, so it lives
            // in [0,1], and the spectral track is a DCT over a normalised field. The bound is
            // what float32 accumulated in a different order costs over five dilated blocks.
            const float* const goldenIntention = payload + features;
            const float* const goldenSpectral = goldenIntention + TcnEncoder::kOutputTimesteps;
            float worstIntention = 0.0f;
            float worstSpectral = 0.0f;
            for (int i = 0; i < TcnEncoder::kOutputTimesteps; ++i) {
                worstIntention = std::fmax(worstIntention,
                                           std::fabs(intention[i] - goldenIntention[i]));
            }
            for (size_t i = 0; i < spectralCount; ++i) {
                worstSpectral = std::fmax(worstSpectral,
                                          std::fabs(spectral[i] - goldenSpectral[i]));
            }
            if (worstIntention > 1e-3f || worstSpectral > 1e-3f) {
                std::printf("      worst intention %g, worst spectral %g\n",
                            static_cast<double>(worstIntention),
                            static_cast<double>(worstSpectral));
            }
            check(worstIntention <= 1e-3f, "the intention track matches model.py's");
            check(worstSpectral <= 1e-3f, "the spectral track matches model.py's");

            // The swap the descriptor cannot see, performed on purpose: seReduceWeight is
            // [trunk * seReduced] and seExpandWeight is [seReduced * trunk], so exchanging them
            // changes no length and no descriptor field. A comparison that still passed here
            // would be measuring nothing.
            std::vector<float> swapped(TcnWeights::kTrunk * TcnWeights::kSeReduced);
            std::memcpy(swapped.data(), weights->blocks[0].seReduceWeight, sizeof(swapped[0]) * swapped.size());
            std::memcpy(weights->blocks[0].seReduceWeight, weights->blocks[0].seExpandWeight,
                        sizeof(swapped[0]) * swapped.size());
            std::memcpy(weights->blocks[0].seExpandWeight, swapped.data(),
                        sizeof(swapped[0]) * swapped.size());
            encoder->forward(payload, intention.data(), spectral.data());
            float worstSwapped = 0.0f;
            for (size_t i = 0; i < spectralCount; ++i) {
                worstSwapped = std::fmax(worstSwapped,
                                         std::fabs(spectral[i] - goldenSpectral[i]));
            }
            check(worstSwapped > 1e-3f,
                  "and two same-shaped arrays exchanged make it disagree");
        }
    }

    section("TCN encoder forward pass");
    {
        const std::vector<uint8_t> zeroed = zeroWeightsFile();
        auto weights = std::make_unique<TcnWeights>();
        check(weights->loadFromBytes(zeroed.data(), zeroed.size()), "zeroed weights load");

        TcnEncoder encoder;
        encoder.setWeights(weights.get());
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
        // Heap, not a local -- see the comment above the first TcnWeights in this file.
        auto weights = std::make_unique<TcnWeights>();  // zero-initialised: only the area extent
                                                         // is checked in this section
        decoder.setLayout(geometry, *weights);
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

        // Heap, not a local: TcnDecoder embeds a TcnWeights by value (~2.5 MB), and this section
        // needs two of them alive at once (see foundBeforeWeights below) -- the same stack-frame
        // overflow the comment on tcn_decoder.cpp's loadWeights() already documents.
        auto tcn = std::make_unique<TcnDecoder>(engine);
        tcn->setLayout(engine.geometry());
        const std::vector<uint8_t> zeroed = zeroWeightsFile();
        check(tcn->loadWeights(zeroed.data(), zeroed.size()), "inert weights load into the decoder");

        Random random(2026u);
        std::vector<float> xs;
        std::vector<float> ys;
        std::vector<int64_t> times;
        synthesiseGesture(layout, "test", 0.f, random, xs, ys, times);
        check(xs.size() >= 2, "a real gesture was synthesised");

        Candidate out[8];
        const int found = tcn->decode(xs.data(), ys.data(), times.data(),
                                      static_cast<int>(xs.size()), out, 8);
        check(found >= 0, "decoding with inert weights does not crash");
        for (int i = 0; i < found; ++i) {
            check(std::isfinite(out[i].score), "every returned score is finite");
        }

        auto tcnBeforeWeights = std::make_unique<TcnDecoder>(engine);
        Candidate out2[8];
        const int foundBeforeWeights =
            tcnBeforeWeights->decode(xs.data(), ys.data(), times.data(),
                                     static_cast<int>(xs.size()), out2, 8);
        check(foundBeforeWeights == 0, "decoding before any weights are loaded returns nothing");
    }

    section("tier B is built on demand and freed when switched off");
    {
        Engine engine;
        check(engine.create(), "the engine is created");
        struct stat info {};
        stat(BORDERKEYS_TEST_PACK, &info);
        const int fd = ::open(BORDERKEYS_TEST_PACK, O_RDONLY);
        check(engine.loadLanguage("ro-RO", fd, 0, info.st_size, 1.0f) == kBkdOk, "pack loaded");
        ::close(fd);
        const char* tags[1] = {"ro-RO"};
        const float packWeights[1] = {1.0f};
        engine.setActiveLanguages(tags, packWeights, 1);
        TestLayout layout;
        engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                              layout.keyHeight);

        // Nothing is built until something asks for it: a fresh engine costs none of the two and
        // a half megabytes the decoder holds, whatever the preference ends up saying.
        check(std::strcmp(engine.gestureDecoderName(), "SHARK2") == 0,
              "a new engine decodes with tier A");
        check(!engine.warmSwipeModel(), "warming with no weights loaded does nothing");

        const std::vector<uint8_t> zeroed = zeroWeightsFile();
        check(engine.loadSwipeWeights(zeroed.data(), zeroed.size()),
              "loading weights builds the decoder that holds them");
        check(engine.warmSwipeModel(), "warming runs once the weights and a layout are there");
        engine.setSwipeModelEnabled(true);
        check(std::strcmp(engine.gestureDecoderName(), "SHARK2") != 0,
              "switched on with weights loaded, gestures go to tier B");

        engine.setSwipeModelEnabled(false);
        check(std::strcmp(engine.gestureDecoderName(), "SHARK2") == 0,
              "switched off, gestures go back to tier A");
        check(!engine.warmSwipeModel(),
              "switching off freed the weights, so there is nothing left to warm");

        // And it comes back: the preference turning on again reloads from the asset, which is
        // the whole shape of the trade -- off costs nothing, on pays for itself once.
        check(engine.loadSwipeWeights(zeroed.data(), zeroed.size()),
              "the decoder is rebuilt by the next load");
        engine.setSwipeModelEnabled(true);
        check(std::strcmp(engine.gestureDecoderName(), "SHARK2") != 0, "tier B is back");

        // A refused file leaves nothing behind rather than an empty decoder holding its weights.
        std::vector<uint8_t> truncated = zeroWeightsFile();
        truncated.resize(truncated.size() / 2);
        check(!engine.loadSwipeWeights(truncated.data(), truncated.size()),
              "a truncated weight file is refused");
        check(std::strcmp(engine.gestureDecoderName(), "SHARK2") == 0,
              "a refused load leaves tier A decoding");
    }
}
