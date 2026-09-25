// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_decoder.hpp"

#include <chrono>
#include <memory>

#include "../topk.hpp"

namespace borderkeys {

void TcnDecoder::setLayout(const KeyGeometry& geometry) {
    lastGeometry_ = &geometry;
    ctcDecoder_.setLayout(geometry, weights_);
}

bool TcnDecoder::loadWeights(const uint8_t* data, size_t length) {
    // Heap, not a local: TcnWeights is ~2.4 MB (629,601 floats), comfortably inside a desktop
    // thread's default several-MB stack -- which is why this went unnoticed through every
    // previous caller (native-tests/tcn_replay.cpp's own main thread) -- but this decoder's real
    // caller is Android's dedicated prediction HandlerThread, sized far smaller, and a 2.4 MB
    // local blew it outright: confirmed 2026-09-13 via a device tombstone reading "stack pointer
    // is not in a rw map; likely due to stack overflow" the moment this ran for the first time
    // on-device, not on a host build.
    auto candidate = std::make_unique<TcnWeights>();
    if (!candidate->loadFromBytes(data, length)) {
        return false;
    }
    weights_ = *candidate;
    encoder_.setWeights(&weights_);
    // setLayout's key-embedding pass needs real weights to be worth anything, and setLayout
    // ordinarily runs first -- layout is known at keyboard-measure time, long before this
    // asynchronous read off disk finishes. Redoing it now, against the geometry already on file,
    // is what corrects a basis this decoder may already have built from stale (uninitialised)
    // weights, rather than leaving it wrong until the next unrelated layout change.
    if (lastGeometry_ != nullptr) {
        ctcDecoder_.setLayout(*lastGeometry_, weights_);
    }
    return true;
}

int TcnDecoder::decode(const float* xs, const float* ys, const int64_t* ts, int count,
                       Candidate* out, int maxOut) {
    if (!encoder_.hasWeights() || out == nullptr || maxOut <= 0 || count < 2) {
        return 0;
    }
    if (!resampleUniformTime(xs, ys, ts, count, ctcDecoder_.areaWidth(), ctcDecoder_.areaHeight(),
                             resampledX_, resampledY_)) {
        return 0;
    }
    const auto encoderStarted = std::chrono::steady_clock::now();
    buildTcnFeatures(resampledX_, resampledY_, kTcnTimesteps, features_);
    encoder_.forward(features_, intention_, spectral_);
    const auto searchStarted = std::chrono::steady_clock::now();
    lastEncoderMicros_ = std::chrono::duration_cast<std::chrono::microseconds>(
                             searchStarted - encoderStarted).count();

    TopK<Candidate> heap;
    Candidate heapStorage[16];
    heap.reset(heapStorage, static_cast<int>(sizeof(heapStorage) / sizeof(heapStorage[0])));

    for (int packIndex = 0; packIndex < scorer_.packCount(); ++packIndex) {
        const PackedTrie* const trie = scorer_.activeTrie(packIndex);
        if (trie == nullptr) {
            continue;
        }
        const int found = ctcDecoder_.decode(intention_, spectral_, *trie, packIndex, scorer_,
                                             perPackScratch_, kMaxPerPackCandidates);
        for (int i = 0; i < found; ++i) {
            heap.offer(perPackScratch_[i]);
        }
    }

    lastSearchMicros_ = std::chrono::duration_cast<std::chrono::microseconds>(
                            std::chrono::steady_clock::now() - searchStarted).count();

    Candidate drained[16];
    const int drainedCount =
        heap.drainSorted(drained, static_cast<int>(sizeof(drained) / sizeof(drained[0])));
    const int written = (drainedCount < maxOut) ? drainedCount : maxOut;
    for (int i = 0; i < written; ++i) {
        out[i] = drained[i];
    }
    return written;
}

}  // namespace borderkeys
