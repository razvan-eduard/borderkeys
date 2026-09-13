// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_decoder.hpp"

#include <memory>

#include "../topk.hpp"

namespace borderkeys {
namespace {

// TEMPORARY, TRACKED SHIM -- see HANDOFF.md's Thread 4, "Validated through the real C++ path"
// and its predecessor "CORRECTION" section (2026-09-13) for the full story. checkpoint.pt / the
// currently-shipped model.bkw were trained under a train.py bug that divided x/y -- already a
// [0,1] canvas fraction -- by canvas_width/canvas_height a second time, so every training example
// this checkpoint ever saw was squashed into a sliver near the coordinate origin, not the [0,1]^2
// square resampleUniformTime documents and every other decoder in this codebase actually uses.
// Reproducing that exact scale here, on top of the correct single normalisation
// resampleUniformTime already does, is what measured 80.0%/85.0% top-1/top-3 on real held-out
// human gestures through this exact decode path (tools/tcn_replay.py, 2026-09-13) -- feeding this
// checkpoint the correct [0,1] scale instead decodes to noise, because it never saw that scale.
//
// DELETE kShimCanvasWidth/Height and the loop that uses them, together, the moment a checkpoint
// trained under the fixed train.py/eval_layouts.py lands and is validated the same way. Do not
// carry this forward "just in case" -- it is a property of this one checkpoint, not of the model
// architecture or of real gestures in general.
constexpr float kShimCanvasWidth = 406.19049072265625f;
constexpr float kShimCanvasHeight = 170.36904907226562f;

}  // namespace

void TcnDecoder::setLayout(const KeyGeometry& geometry) {
    ctcDecoder_.setLayout(geometry);
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
    // See the anonymous namespace above: reproduces this checkpoint's training-time scale bug on
    // purpose. Temporary.
    for (int i = 0; i < kTcnTimesteps; ++i) {
        resampledX_[i] /= kShimCanvasWidth;
        resampledY_[i] /= kShimCanvasHeight;
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
