// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The tap decoder: a beam search over a pack's trie that reads each tap as a key by the touch
// model's likelihood, with the language model on the words it reaches. It accepts its best word
// only when that beats the typed letters read as an unknown word and every other word together
// by kEvidenceRatio, and holds back an entry of autocorrect's list that another reading beats by
// more than that.

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#include "engine.hpp"
#include "tap_decode.hpp"

namespace borderkeys {

namespace {

// The log-probabilities of the typing channel, in nats, as tools/estimate_typing_channel.py
// measures them on phone typing.

// That the word meant is one no pack holds, per word.
constexpr float kUnknownWordLogProb = -4.77f;

// A tap that belongs to no letter of the word, per tap.
constexpr float kExtraTapLogProb = -5.67f;

// A letter of the word with no tap, per letter.
constexpr float kMissedLetterLogProb = -5.00f;

// A mark of the word with no tap, per mark.
constexpr float kMarkLogProb = -0.67f;

// Two taps in the wrong order, per adjacent pair.
constexpr float kSwapLogProb = -7.07f;

// A letter typed as one given key further than a neighbour, per such key.
constexpr float kFarKeyLogProb = -8.91f;

// The log of how much worse a wrong correction is than a missed one: how much likelier, in
// nats, the decoder's word must be than every other reading together before it is applied, and
// how much likelier another single reading must be than an entry of autocorrect's list before
// the entry is held back.
constexpr float kEvidenceRatio = 4.6051702f;  // ln 100

// log 2π: what the touch model's log-density leaves out of a normal density in key units.
constexpr float kLogTwoPi = 1.8378771f;

// How far below a tap's likeliest key another key may be read, in nats.
constexpr float kTapWindow = 12.0f;

// Hypotheses kept after each tap.
constexpr int kBeamWidth = 128;

// Hypotheses one level holds before it prunes to kBeamWidth.
constexpr int kLevelCapacity = 1024;

// Letters with no tap in a row, the last tap's included.
constexpr int kMaxMissedRun = 2;

// The keys a swapped pair is read as, from each tap's likeliest.
constexpr int kSwapChoices = 3;

// The shortest typed word the decoder reads.
constexpr int kMinDecodeLetters = 4;

static_assert(Engine::kMaxComposing <= kMaxDecodeTaps, "DecoderTaps holds every composed letter");

// Fills `table` for `taps` over `trie`'s symbols, a tap's fit for a key from `fitOf`, and at
// least kFarKeyLogProb past the typed key's own fit for any key; a typed mark, or a letter typed
// with no position, fits its own symbol alone.
template <typename FitOf>
void fillTapFit(const PackedTrie& trie, const DecoderTaps& taps, FitOf fitOf, TapFitTable* table) {
    table->classify(trie);
    for (int tap = 0; tap < taps.count; ++tap) {
        table->set(tap, 0, kDecodeNegativeInfinity, kTapWindow);
        const float typedFit =
            taps.placed(tap)
                ? fitOf(taps.folded[tap], taps.xs[tap], taps.ys[tap]) - taps.likeliest[tap]
                : kDecodeNegativeInfinity;
        for (int symbol = 1; symbol < table->symbols(); ++symbol) {
            const uint32_t code = trie.alphabetCodePointAt(symbol - 1);
            const float own = (code == taps.folded[tap]) ? 0.0f : kDecodeNegativeInfinity;
            float fit = kDecodeNegativeInfinity;
            if (taps.typedMark(tap)) {
                fit = own;
            } else if (!table->markSymbol(symbol)) {
                fit = taps.placed(tap)
                          ? std::max(fitOf(code, taps.xs[tap], taps.ys[tap]) - taps.likeliest[tap],
                                     typedFit + kFarKeyLogProb)
                          : own;
            }
            table->set(tap, symbol, fit, kTapWindow);
        }
    }
}

// Pushes into `children` each letter or mark one past a hypothesis of `parents` with no tap,
// leaving out any scoring below `floor`.
void pushMissedLetters(const PackedTrie& trie, const TapFitTable& table,
                       const DecoderLevel& parents, float floor, DecoderLevel* children) {
    for (int k = 0; k < parents.count(); ++k) {
        const DecoderState& state = parents[k];
        if (state.missed >= kMaxMissedRun) {
            continue;
        }
        for (int symbol = 1; symbol < table.symbols(); ++symbol) {
            const float score =
                state.score + (table.markSymbol(symbol) ? kMarkLogProb : kMissedLetterLogProb);
            if (score < floor) {
                continue;
            }
            const int32_t child = trie.walk(state.node, symbol);
            if (child >= 0) {
                children->push(DecoderState{child, score, static_cast<int8_t>(state.missed + 1)});
            }
        }
    }
}

// Extends `level` by the letters and marks reached with no tap, kMaxMissedRun in a row at most,
// each round gathered in the beam's round buffers before it joins the level; pruned.
void addMissedLetters(const PackedTrie& trie, const TapFitTable& table, DecoderLevel* level,
                      DecoderBeam* beam) {
    const DecoderLevel* parents = level;
    for (int round = 0; round < kMaxMissedRun; ++round) {
        level->prune();
        DecoderLevel& children = beam->round(round);
        children.clear();
        pushMissedLetters(trie, table, *parents, level->keepFloor(), &children);
        children.prune();
        level->absorb(children);
        parents = &children;
    }
    level->prune();
}

// Reads tap `tap` from `state` as each key within its window, into `next`.
void pushTapAsKey(const PackedTrie& trie, const TapFitTable& table, int tap,
                  const DecoderState& state, DecoderLevel* next) {
    const int* const choices = table.choices(tap);
    for (int c = 0; c < table.choiceCount(tap); ++c) {
        const int32_t child = trie.walk(state.node, choices[c]);
        if (child >= 0) {
            next->push(DecoderState{child, state.score + table.at(tap, choices[c]), 0});
        }
    }
}

// Reads taps `tap` and `tap + 1` from `state` in the wrong order, into `afterNext`.
void pushSwappedPair(const PackedTrie& trie, const TapFitTable& table, int tap,
                     const DecoderState& state, DecoderLevel* afterNext) {
    const int* const firstChoices = table.choices(tap + 1);
    const int* const secondChoices = table.choices(tap);
    for (int a = 0; a < table.topChoices(tap + 1, kSwapChoices); ++a) {
        const int32_t first = trie.walk(state.node, firstChoices[a]);
        if (first < 0) {
            continue;
        }
        for (int b = 0; b < table.topChoices(tap, kSwapChoices); ++b) {
            const int32_t second = trie.walk(first, secondChoices[b]);
            if (second >= 0) {
                const float fit =
                    table.at(tap + 1, firstChoices[a]) + table.at(tap, secondChoices[b]);
                afterNext->push(DecoderState{second, state.score + fit + kSwapLogProb, 0});
            }
        }
    }
}

// Moves every hypothesis of tap `tap` past it: read as a key, as a tap of no letter, or swapped
// with the next tap; a typed mark only as itself.
void advance(const PackedTrie& trie, const TapFitTable& table, const DecoderTaps& taps, int tap,
             DecoderBeam* beam) {
    const DecoderLevel& current = beam->at(tap);
    DecoderLevel& next = beam->at(tap + 1);
    DecoderLevel& afterNext = beam->at(tap + 2);
    const bool mayStray = !taps.typedMark(tap);
    const bool maySwap = mayStray && tap + 1 < taps.count && !taps.typedMark(tap + 1);
    for (int k = 0; k < current.count(); ++k) {
        const DecoderState& state = current[k];
        pushTapAsKey(trie, table, tap, state, &next);
        if (mayStray) {
            next.push(DecoderState{state.node, state.score + kExtraTapLogProb + taps.stray[tap], 0});
        }
        if (maySwap) {
            pushSwappedPair(trie, table, tap, state, &afterNext);
        }
    }
}

}  // namespace

float Engine::tapFit(uint32_t code, float x, float y) const {
    return touchModel_.tapLogLikelihood(geometry_, code, x, y);
}

float Engine::likeliestTapFit(float x, float y) const {
    float best = kDecodeNegativeInfinity;
    for (int slot = 0; slot < geometry_.keyCount(); ++slot) {
        best = std::max(best, tapFit(geometry_.codeAt(slot), x, y));
    }
    return best;
}

bool Engine::tapPoint(int index, uint32_t typed, float* x, float* y) const {
    if (queryTapped_ && !std::isnan(queryTapX_[index]) && !std::isnan(queryTapY_[index])) {
        *x = queryTapX_[index];
        *y = queryTapY_[index];
        return true;
    }
    return geometry_.centreOfLetter(typed, x, y);
}

float Engine::readTaps(DecoderTaps* taps) const {
    // A tap aimed at no key lands anywhere on the keyboard, about one key unit per key.
    const float anyKey = -std::log(static_cast<float>(std::max(geometry_.keyCount(), 1)));
    float fit = 0.0f;
    for (int i = 0; i < taps->count; ++i) {
        taps->likeliest[i] = kDecodeNegativeInfinity;
        taps->stray[i] = anyKey;
        if (!tapPoint(i, taps->folded[i], &taps->xs[i], &taps->ys[i])) {
            taps->xs[i] = std::numeric_limits<float>::quiet_NaN();
            taps->ys[i] = taps->xs[i];
            continue;
        }
        taps->likeliest[i] = likeliestTapFit(taps->xs[i], taps->ys[i]);
        taps->stray[i] = anyKey + kLogTwoPi - taps->likeliest[i];
        fit += tapFit(taps->folded[i], taps->xs[i], taps->ys[i]) - taps->likeliest[i];
    }
    return fit;
}

bool Engine::decodes(int packIndex, int restrictTo) const {
    const LanguagePack& pack = packs_[packIndex];
    return pack.isOpen() && pack.active && (restrictTo < 0 || packIndex == restrictTo);
}

float Engine::unknownWordLogProb(const DecoderTaps& taps) const {
    double chance = 0.0;
    for (int packIndex = 0; packIndex < kMaxPacks; ++packIndex) {
        const LanguagePack& pack = packs_[packIndex];
        if (decodes(packIndex, -1) && pack.letters().isBuilt()) {
            chance += normalisedWeight_[packIndex] *
                      std::exp(static_cast<double>(
                          pack.letters().spellingLogProb(pack.trie(), taps.folded, taps.count)));
        }
    }
    return chance > 0.0 ? kUnknownWordLogProb + static_cast<float>(std::log(chance))
                        : kDecodeNegativeInfinity;
}

void Engine::forgetDecoded() {
    hasDecoded_ = false;
    decodedAccepted_ = false;
    decodedOffered_ = false;
    decodedMargin_ = 0.0f;
    std::fill(settledConfident_, settledConfident_ + 1 + kMaxCorrections, true);
}

void Engine::decodeTaps(const uint32_t* folded, int foldedLength, int restrictTo) {
    forgetDecoded();
    if (typedKnown_ || foldedLength < kMinDecodeLetters || foldedLength > kMaxComposing ||
        isMark(folded[0]) || isMark(folded[foldedLength - 1]) || !geometry_.isSet()) {
        return;
    }
    DecoderTaps taps;
    taps.folded = folded;
    taps.count = foldedLength;
    const float literal = readTaps(&taps) + unknownWordLogProb(taps);

    DecodedWords words;
    for (int packIndex = 0; packIndex < kMaxPacks; ++packIndex) {
        if (decodes(packIndex, restrictTo)) {
            decodePack(packIndex, taps, &words);
        }
    }
    weighListedCorrections(taps, literal, &words);
    if (!words.found()) {
        return;
    }
    decoded_ = words.best();
    decoded_.score = words.bestTotal();
    hasDecoded_ = true;
    // Against the typed letters and every other word the taps reach.
    decodedMargin_ = words.bestTotal() - logAddExp(literal, words.othersTotal());
    decodedAccepted_ = decodedMargin_ >= kEvidenceRatio;
    decodedOffered_ = !decodedAccepted_ && words.bestTotal() > literal;
}

template <typename Finish>
void Engine::runBeam(int packIndex, const DecoderTaps& taps, const WordPath* path, Finish finish) {
    const PackedTrie& trie = packs_[packIndex].trie();
    const size_t mark = arena_.used();
    TapFitTable table;
    DecoderBeam beam;
    if (table.allocate(arena_, taps.count, trie.alphabetSize() + 1) &&
        beam.allocate(arena_, kLevelCapacity, kBeamWidth)) {
        beam.holdTo(path);
        fillTapFit(trie, taps,
                   [this](uint32_t code, float x, float y) { return tapFit(code, x, y); }, &table);
        beam.at(0).push(DecoderState{trie.root(), 0.0f, 0});
        for (int tap = 0;; ++tap) {
            beam.at(tap + 2).clear();
            DecoderLevel& current = beam.at(tap);
            addMissedLetters(trie, table, &current, &beam);
            if (tap == taps.count) {
                break;
            }
            advance(trie, table, taps, tap, &beam);
        }
        finish(beam.at(taps.count));
    }
    arena_.rewind(mark);
}

void Engine::decodePack(int packIndex, const DecoderTaps& taps, DecodedWords* words) {
    runBeam(packIndex, taps, nullptr, [&](const DecoderLevel& finals) {
        offerDecodedWords(packIndex, taps, finals, words);
    });
}

bool Engine::wordPathOf(int packIndex, uint32_t wordIndex, WordPath* path) const {
    const PackedTrie& trie = packs_[packIndex].trie();
    uint32_t length = 0;
    const char* const text = trie.wordText(wordIndex, &length);
    uint32_t folded[kMaxDecodeTaps];
    const int count = text != nullptr ? foldUtf8(text, length, folded, kMaxDecodeTaps) : -1;
    if (count <= 0) {
        return false;
    }
    path->count = 0;
    path->nodes[path->count++] = trie.root();
    for (int i = 0; i < count; ++i) {
        const int symbol = trie.symbolFor(folded[i]);
        const int32_t child = symbol > 0 ? trie.walk(path->terminal(), symbol) : -1;
        if (child < 0) {
            return false;
        }
        path->nodes[path->count++] = child;
    }
    return true;
}

float Engine::listedTotal(const Candidate& word, const DecoderTaps& taps) {
    WordPath path;
    if (word.packIndex < 0 || word.packIndex >= kMaxPacks || !packs_[word.packIndex].isOpen() ||
        !wordPathOf(word.packIndex, static_cast<uint32_t>(word.wordIndex), &path)) {
        return kDecodeNegativeInfinity;
    }
    float reach = kDecodeNegativeInfinity;
    runBeam(word.packIndex, taps, &path, [&](const DecoderLevel& finals) {
        for (int k = 0; k < finals.count(); ++k) {
            if (finals[k].node == path.terminal()) {
                reach = logAddExp(reach, finals[k].score);
            }
        }
    });
    return reach == kDecodeNegativeInfinity
               ? reach
               : reach + packWeightLog(word.packIndex) +
                     contextLogProb(word.packIndex, static_cast<uint32_t>(word.wordIndex));
}

bool Engine::respells(const Candidate& word, const DecoderTaps& taps) const {
    uint32_t length = 0;
    const char* const text = candidateText(word, &length);
    uint32_t folded[kMaxDecodeTaps];
    const int count = text != nullptr ? foldUtf8(text, length, folded, kMaxDecodeTaps) : -1;
    return count == taps.count &&
           std::memcmp(folded, taps.folded, sizeof(uint32_t) * taps.count) == 0;
}

void Engine::weighListedCorrections(const DecoderTaps& taps, float literal,
                                    DecodedWords* words) {
    int held[1 + kMaxCorrections];
    for (int i = 0; i < settledCount_; ++i) {
        held[i] = -1;
        if (respells(settled_[i], taps)) {
            continue;
        }
        if (words->indexOf(settled_[i]) < 0) {
            words->offer(settled_[i], listedTotal(settled_[i], taps), false);
        }
        held[i] = words->indexOf(settled_[i]);
    }
    for (int i = 0; i < settledCount_; ++i) {
        if (held[i] >= 0) {
            const float strongestRival = std::max(literal, words->strongestBesides(held[i]));
            settledConfident_[i] = strongestRival - words->totalAt(held[i]) <= kEvidenceRatio;
        }
    }
}

bool Engine::decodable(int packIndex, uint32_t wordIndex, const DecoderTaps& taps) const {
    const LanguagePack& pack = packs_[packIndex];
    uint32_t length = 0;
    const char* const text = pack.trie().wordText(wordIndex, &length);
    if (text == nullptr || length == 0 || isBlocked(text, length)) {
        return false;
    }
    uint32_t wordFolded[kMaxComposing];
    const int wordLength = foldUtf8(text, length, wordFolded, kMaxComposing);
    if (wordLength == taps.count &&
        std::memcmp(wordFolded, taps.folded, sizeof(uint32_t) * taps.count) == 0) {
        return false;
    }
    return !candidateIsProperNoun(Candidate{packIndex, static_cast<int32_t>(wordIndex), 0.0f}) &&
           plausibleCorrectionTarget(pack, wordIndex);
}

void Engine::offerDecodedWords(int packIndex, const DecoderTaps& taps,
                               const DecoderLevel& finals, DecodedWords* words) const {
    const PackedTrie& trie = packs_[packIndex].trie();
    const float weightLog = packWeightLog(packIndex);
    for (int k = 0; k < finals.count(); ++k) {
        const int32_t firstIndex = trie.terminalWordIndex(finals[k].node);
        if (firstIndex < 0) {
            continue;
        }
        const uint32_t spellings = trie.spellingsFrom(static_cast<uint32_t>(firstIndex));
        for (uint32_t offset = 0; offset < spellings; ++offset) {
            const uint32_t wordIndex = static_cast<uint32_t>(firstIndex) + offset;
            if (decodable(packIndex, wordIndex, taps)) {
                words->offer(Candidate{packIndex, static_cast<int32_t>(wordIndex), 0.0f},
                             finals[k].score + weightLog + contextLogProb(packIndex, wordIndex));
            }
        }
    }
}

bool Engine::decoderBest(Candidate* out, float* margin) const {
    if (!hasDecoded_) {
        return false;
    }
    if (out != nullptr) {
        *out = decoded_;
    }
    if (margin != nullptr) {
        *margin = decodedMargin_;
    }
    return true;
}

}  // namespace borderkeys
