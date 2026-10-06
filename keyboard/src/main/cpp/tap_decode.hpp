// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TAP_DECODE_HPP
#define BORDERKEYS_TAP_DECODE_HPP

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

#include "arena.hpp"
#include "candidate.hpp"
#include "marks.hpp"
#include "packed_trie.hpp"

namespace borderkeys {

// The tap decoder's building blocks (engine_decode.cpp).

constexpr float kDecodeNegativeInfinity = -std::numeric_limits<float>::infinity();

// The most taps the decoder reads.
constexpr int kMaxDecodeTaps = 48;

// The typed word as the decoder reads it: its folded letters, where each was tapped, NaN for
// nowhere, the log-likelihood of each tap's likeliest key, and each tap's log-likelihood as a
// tap aimed at no key, against that likeliest key.
struct DecoderTaps {
    const uint32_t* folded = nullptr;
    int count = 0;
    float xs[kMaxDecodeTaps] = {};
    float ys[kMaxDecodeTaps] = {};
    float likeliest[kMaxDecodeTaps] = {};
    float stray[kMaxDecodeTaps] = {};

    bool placed(int tap) const { return !std::isnan(xs[tap]); }

    // Whether `tap` typed a mark, which is read as that mark alone.
    bool typedMark(int tap) const { return isMark(folded[tap]); }
};

// One hypothesis: a trie node, its score in nats, and how many letters in a row it reached
// with no tap.
struct DecoderState {
    int32_t node;
    float score;
    int8_t missed;
};

// The trie nodes one word's spelling passes through, its root first and its terminal last.
struct WordPath {
    int32_t nodes[kMaxDecodeTaps + 1] = {};
    int count = 0;

    bool contains(int32_t node) const {
        for (int i = 0; i < count; ++i) {
            if (nodes[i] == node) {
                return true;
            }
        }
        return false;
    }

    int32_t terminal() const { return nodes[count - 1]; }
};

// The hypotheses one tap holds, in an arena buffer larger than the beam's width; a full buffer
// is pruned before it takes another, so no hypothesis a prune would keep is ever lost. A level
// held to a word's path takes no hypothesis off it.
class DecoderLevel {
public:
    bool allocate(Arena& arena, int capacity, int width) {
        states_ = arena.allocateArray<DecoderState>(static_cast<size_t>(capacity));
        capacity_ = states_ == nullptr ? 0 : capacity;
        width_ = width;
        count_ = 0;
        return states_ != nullptr && capacity > width;
    }

    void clear() { count_ = 0; }

    void holdTo(const WordPath* path) { path_ = path; }

    void push(const DecoderState& state) {
        if (path_ != nullptr && !path_->contains(state.node)) {
            return;
        }
        if (count_ == capacity_) {
            prune();
        }
        states_[count_++] = state;
    }

    // Appends every hypothesis `other` holds.
    void absorb(const DecoderLevel& other) {
        for (int i = 0; i < other.count_; ++i) {
            push(other.states_[i]);
        }
    }

    // Keeps every hypothesis scoring at least as well as the beam's width-th best, at most one
    // short of the capacity, dropping first any that another at the same node dominates: one
    // scoring at least as well with no more letters missed in a row.
    void prune() {
        std::sort(states_, states_ + count_, [](const DecoderState& a, const DecoderState& b) {
            return a.node < b.node || (a.node == b.node && a.score > b.score);
        });
        int kept = 0;
        int8_t fewestMissed = 0;
        for (int i = 0; i < count_; ++i) {
            const DecoderState& state = states_[i];
            const bool newNode = kept == 0 || states_[kept - 1].node != state.node;
            if (newNode || state.missed < fewestMissed) {
                states_[kept++] = state;
                fewestMissed = state.missed;
            }
        }
        if (kept > width_) {
            std::nth_element(states_, states_ + width_ - 1, states_ + kept,
                             [](const DecoderState& a, const DecoderState& b) {
                                 return a.score > b.score;
                             });
            const float cut = states_[width_ - 1].score;
            const DecoderState* const end =
                std::partition(states_, states_ + kept,
                               [cut](const DecoderState& state) { return state.score >= cut; });
            kept = std::min(static_cast<int>(end - states_), capacity_ - 1);
        }
        count_ = kept;
    }

    // The score a new hypothesis needs to outlast the next prune of this pruned level: the worst
    // one held when it holds the beam's width, else none.
    float keepFloor() const {
        if (count_ < width_) {
            return kDecodeNegativeInfinity;
        }
        float worst = states_[0].score;
        for (int i = 1; i < count_; ++i) {
            worst = std::min(worst, states_[i].score);
        }
        return worst;
    }

    int count() const { return count_; }
    const DecoderState& operator[](int index) const { return states_[index]; }

private:
    DecoderState* states_ = nullptr;
    int capacity_ = 0;
    int width_ = 0;
    int count_ = 0;
    const WordPath* path_ = nullptr;
};

// The levels of three taps in turn (the current one, the next, and the one after it, which
// receives swapped pairs), and two more that gather the letters reached with no tap.
class DecoderBeam {
public:
    bool allocate(Arena& arena, int capacity, int width) {
        bool allocated = true;
        for (DecoderLevel& level : levels_) {
            allocated = level.allocate(arena, capacity, width) && allocated;
        }
        for (DecoderLevel& level : rounds_) {
            allocated = level.allocate(arena, capacity, width) && allocated;
        }
        return allocated;
    }

    DecoderLevel& at(int tap) { return levels_[tap % 3]; }

    // The buffer for round `round` of letters reached with no tap.
    DecoderLevel& round(int round) { return rounds_[round % 2]; }

    // Holds every level to `path`; null frees them.
    void holdTo(const WordPath* path) {
        for (DecoderLevel& level : levels_) {
            level.holdTo(path);
        }
        for (DecoderLevel& level : rounds_) {
            level.holdTo(path);
        }
    }

private:
    DecoderLevel levels_[3];
    DecoderLevel rounds_[2];
};

// How well each tap fits each symbol's key against the tap's likeliest key, in nats, and per tap
// the symbols within a window of the likeliest, likeliest first. Symbol 0 and marks fit no tap.
class TapFitTable {
public:
    bool allocate(Arena& arena, int taps, int symbols) {
        symbols_ = symbols;
        fit_ = arena.allocateArray<float>(static_cast<size_t>(taps) * symbols);
        choices_ = arena.allocateArray<int>(static_cast<size_t>(taps) * symbols);
        choiceCount_ = arena.allocateArray<int>(static_cast<size_t>(taps));
        mark_ = arena.allocateArray<bool>(static_cast<size_t>(symbols));
        if (fit_ == nullptr || choices_ == nullptr || choiceCount_ == nullptr || mark_ == nullptr) {
            return false;
        }
        std::fill(choiceCount_, choiceCount_ + taps, 0);
        return true;
    }

    // Records which of `trie`'s symbols are marks.
    void classify(const PackedTrie& trie) {
        mark_[0] = false;
        for (int symbol = 1; symbol < symbols_; ++symbol) {
            mark_[symbol] = isMark(trie.alphabetCodePointAt(symbol - 1));
        }
    }

    // Records `fit` for `symbol` at `tap`, listing the symbol when within `window` of the
    // likeliest.
    void set(int tap, int symbol, float fit, float window) {
        row(tap)[symbol] = fit;
        if (fit < -window) {
            return;
        }
        float* const fits = row(tap);
        int* const list = choices_ + static_cast<size_t>(tap) * symbols_;
        int at = choiceCount_[tap]++;
        while (at > 0 && fits[list[at - 1]] < fit) {
            list[at] = list[at - 1];
            --at;
        }
        list[at] = symbol;
    }

    float at(int tap, int symbol) const { return fit_[static_cast<size_t>(tap) * symbols_ + symbol]; }
    const int* choices(int tap) const { return choices_ + static_cast<size_t>(tap) * symbols_; }
    int choiceCount(int tap) const { return choiceCount_[tap]; }

    // How many of `tap`'s choices to read when reading at most `most`.
    int topChoices(int tap, int most) const { return std::min(most, choiceCount_[tap]); }

    bool markSymbol(int symbol) const { return mark_[symbol]; }
    int symbols() const { return symbols_; }

private:
    float* row(int tap) { return fit_ + static_cast<size_t>(tap) * symbols_; }

    float* fit_ = nullptr;
    int* choices_ = nullptr;
    int* choiceCount_ = nullptr;
    bool* mark_ = nullptr;
    int symbols_ = 0;
};

// Puts `offered` into `out` second, or first when `out` holds nothing, unless `sameSpelling`
// finds its spelling among the `written`; returns how many `out` holds, at most `maxOut`. The
// word placed takes the score of the one before it, or 0 in first place.
template <typename SameSpelling>
int placeOffered(Candidate* out, int written, int maxOut, const Candidate& offered,
                 SameSpelling sameSpelling) {
    if (maxOut <= 0) {
        return written;
    }
    for (int i = 0; i < written; ++i) {
        if (sameSpelling(out[i], offered)) {
            return written;
        }
    }
    const int at = std::min(written, 1);
    const int kept = std::min(written, maxOut - 1);
    for (int i = kept; i > at; --i) {
        out[i] = out[i - 1];
    }
    out[at] = offered;
    out[at].score = (at > 0) ? out[at - 1].score : 0.0f;
    return kept + 1;
}

// log(e^a + e^b), exact when either is kDecodeNegativeInfinity.
inline float logAddExp(float a, float b) {
    if (a == kDecodeNegativeInfinity) {
        return b;
    }
    if (b == kDecodeNegativeInfinity) {
        return a;
    }
    return std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
}

// The words the taps may spell, each with its log-probability summed over every path that
// reaches it; past kMaxWords words, the further ones are kept as one sum. A word may count
// against the others without being one the decoder may pick.
class DecodedWords {
public:
    static constexpr int kMaxWords = 512;

    // Adds one path's total for `word`; `eligible` whether the decoder may pick it.
    void offer(const Candidate& word, float pathTotal, bool eligible = true) {
        const int index = indexOf(word);
        if (index >= 0) {
            totals_[index] = logAddExp(totals_[index], pathTotal);
            eligible_[index] = eligible_[index] || eligible;
            return;
        }
        if (count_ < kMaxWords) {
            words_[count_] = word;
            totals_[count_] = pathTotal;
            eligible_[count_++] = eligible;
        } else {
            spilled_ = logAddExp(spilled_, pathTotal);
        }
    }

    // Where `word` is held, or -1.
    int indexOf(const Candidate& word) const {
        for (int i = 0; i < count_; ++i) {
            if (words_[i].packIndex == word.packIndex && words_[i].wordIndex == word.wordIndex) {
                return i;
            }
        }
        return -1;
    }

    // Whether any word the decoder may pick was reached.
    bool found() const { return bestIndex() >= 0; }

    const Candidate& best() const { return words_[bestIndex()]; }
    float bestTotal() const { return totals_[bestIndex()]; }
    float othersTotal() const { return totalBesides(bestIndex()); }

    float totalAt(int index) const { return totals_[index]; }

    // The highest total of any word but the one at `index`.
    float strongestBesides(int index) const {
        float strongest = spilled_;
        for (int i = 0; i < count_; ++i) {
            if (i != index) {
                strongest = std::max(strongest, totals_[i]);
            }
        }
        return strongest;
    }

    // The log of the summed probability of every word but the one at `index`.
    float totalBesides(int index) const {
        float others = spilled_;
        for (int i = 0; i < count_; ++i) {
            if (i != index) {
                others = logAddExp(others, totals_[i]);
            }
        }
        return others;
    }

private:
    // The likeliest word the decoder may pick, or -1.
    int bestIndex() const {
        int best = -1;
        for (int i = 0; i < count_; ++i) {
            if (eligible_[i] && (best < 0 || totals_[i] > totals_[best])) {
                best = i;
            }
        }
        return best;
    }

    Candidate words_[kMaxWords];
    float totals_[kMaxWords] = {};
    bool eligible_[kMaxWords] = {};
    int count_ = 0;
    float spilled_ = kDecodeNegativeInfinity;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TAP_DECODE_HPP
