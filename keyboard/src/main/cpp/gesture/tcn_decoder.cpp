// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_decoder.hpp"

#include "../topk.hpp"

namespace borderkeys {

void TcnDecoder::setLayout(const KeyGeometry& geometry) {
    ctcDecoder_.setLayout(geometry);
}

bool TcnDecoder::loadWeights(const uint8_t* data, size_t length) {
    TcnWeights candidate;
    if (!candidate.loadFromBytes(data, length)) {
        return false;
    }
    weights_ = candidate;
    encoder_.setWeights(&weights_);
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
    buildTcnFeatures(resampledX_, resampledY_, kTcnTimesteps, features_);
    encoder_.forward(features_, intention_, spectral_);

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
