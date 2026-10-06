// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "letter_model.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

#include "packed_trie.hpp"

namespace borderkeys {

namespace {

// Bits per symbol in a key, and the symbol that marks a context's own key.
constexpr int kSymbolBits = 12;
constexpr uint64_t kSymbolMask = (1u << kSymbolBits) - 1;
constexpr int kNoSymbol = static_cast<int>(kSymbolMask);
constexpr int kLengthShift = 60;

// The key of `next` after the `length` symbols that end just before `history`.
uint64_t keyOf(const int* history, int length, int next) {
    uint64_t key = static_cast<uint64_t>(length) << kLengthShift;
    uint64_t context = 0;
    for (int i = length; i >= 1; --i) {
        context = (context << kSymbolBits) | (static_cast<uint64_t>(history[-i]) & kSymbolMask);
    }
    return key | (context << kSymbolBits) | (static_cast<uint64_t>(next) & kSymbolMask);
}

// A key's context alone: its follower replaced by kNoSymbol.
uint64_t contextOf(uint64_t key) { return key | kSymbolMask; }

// Counts by key, in one open-addressed table that doubles when half full.
class KeyCounts {
public:
    KeyCounts() : keys_(1u << 16, kEmpty), counts_(1u << 16, 0) {}

    void add(uint64_t key) {
        if (used_ * 2 >= keys_.size()) {
            grow();
        }
        size_t slot = slotOf(key);
        if (keys_[slot] == kEmpty) {
            keys_[slot] = key;
            ++used_;
        }
        ++counts_[slot];
    }

    // The keys and their counts, sorted by key.
    std::vector<std::pair<uint64_t, uint32_t>> sorted() const {
        std::vector<std::pair<uint64_t, uint32_t>> entries;
        entries.reserve(used_);
        for (size_t i = 0; i < keys_.size(); ++i) {
            if (keys_[i] != kEmpty) {
                entries.emplace_back(keys_[i], counts_[i]);
            }
        }
        std::sort(entries.begin(), entries.end());
        return entries;
    }

private:
    static constexpr uint64_t kEmpty = ~0ull;

    size_t slotOf(uint64_t key) const {
        const size_t mask = keys_.size() - 1;
        size_t slot = static_cast<size_t>((key * 0x9E3779B97F4A7C15ull) >> 20) & mask;
        while (keys_[slot] != kEmpty && keys_[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    void grow() {
        std::vector<uint64_t> oldKeys(keys_.size() * 2, kEmpty);
        std::vector<uint32_t> oldCounts(counts_.size() * 2, 0);
        oldKeys.swap(keys_);
        oldCounts.swap(counts_);
        for (size_t i = 0; i < oldKeys.size(); ++i) {
            if (oldKeys[i] != kEmpty) {
                const size_t slot = slotOf(oldKeys[i]);
                keys_[slot] = oldKeys[i];
                counts_[slot] = oldCounts[i];
            }
        }
    }

    std::vector<uint64_t> keys_;
    std::vector<uint32_t> counts_;
    size_t used_ = 0;
};

// The longest run of symbols the model reads, its padding included.
constexpr int kMaxPadded = 64;

// Writes `symbols` into `out` as the model reads them, kOrder - 1 spaces before and one after,
// the longest kept within kMaxPadded; returns how many it wrote.
int pad(const int* symbols, int count, int* out) {
    const int kept = std::min(count, kMaxPadded - LetterModel::kOrder);
    int written = 0;
    for (int i = 0; i < LetterModel::kOrder - 1; ++i) {
        out[written++] = 0;
    }
    for (int i = 0; i < kept; ++i) {
        out[written++] = symbols[i];
    }
    out[written++] = 0;
    return written;
}

// Adds each symbol of one spelling, its end included, after each length of context.
void countSpelling(const std::vector<int>& path, KeyCounts* pairs, std::vector<double>* share) {
    int symbols[kMaxPadded];
    const int count = pad(path.data(), static_cast<int>(path.size()), symbols);
    for (int at = LetterModel::kOrder - 1; at < count; ++at) {
        (*share)[symbols[at]] += 1.0;
        for (int length = 1; length < LetterModel::kOrder; ++length) {
            pairs->add(keyOf(symbols + at, length, symbols[at]));
        }
    }
}

// Visits every spelling `trie` offers, depth first, handing `visit` its symbols.
template <typename Visit>
void forEachSpelling(const PackedTrie& trie, Visit visit) {
    struct Frame {
        int32_t node;
        int nextSymbol;
    };
    const int symbols = trie.alphabetSize();
    std::vector<Frame> stack{Frame{trie.root(), 1}};
    std::vector<int> path;
    while (!stack.empty()) {
        Frame& frame = stack.back();
        if (frame.nextSymbol > symbols) {
            stack.pop_back();
            if (!path.empty()) {
                path.pop_back();
            }
            continue;
        }
        const int symbol = frame.nextSymbol++;
        const int32_t child = trie.walk(frame.node, symbol);
        if (child < 0) {
            continue;
        }
        path.push_back(symbol);
        if (trie.terminalWordIndex(child) >= 0) {
            visit(path);
        }
        stack.push_back(Frame{child, 1});
    }
}

}  // namespace

void LetterModel::build(const PackedTrie& trie) {
    clear();
    const int size = trie.alphabetSize() + 2;
    if (size >= kNoSymbol) {
        return;
    }
    KeyCounts pairs;
    std::vector<double> share(size, 1.0);
    forEachSpelling(trie, [&](const std::vector<int>& path) {
        countSpelling(path, &pairs, &share);
    });

    double total = 0.0;
    for (const double value : share) {
        total += value;
    }
    for (double& value : share) {
        value /= total;
    }

    const std::vector<std::pair<uint64_t, uint32_t>> entries = pairs.sorted();
    pairKeys_.reserve(entries.size());
    pairCounts_.reserve(entries.size());
    for (const auto& entry : entries) {
        const uint64_t context = contextOf(entry.first);
        if (contextKeys_.empty() || contextKeys_.back() != context) {
            contextKeys_.push_back(context);
            contextTotals_.push_back(0);
            contextDistinct_.push_back(0);
        }
        contextTotals_.back() += entry.second;
        contextDistinct_.back() += 1;
        pairKeys_.push_back(entry.first);
        pairCounts_.push_back(entry.second);
    }
    share_ = std::move(share);
    size_ = size;
}

void LetterModel::clear() {
    size_ = 0;
    share_.clear();
    pairKeys_.clear();
    pairCounts_.clear();
    contextKeys_.clear();
    contextTotals_.clear();
    contextDistinct_.clear();
}

size_t LetterModel::bytes() const {
    return share_.size() * sizeof(double) + pairKeys_.size() * sizeof(uint64_t) +
           pairCounts_.size() * sizeof(uint32_t) + contextKeys_.size() * sizeof(uint64_t) +
           (contextTotals_.size() + contextDistinct_.size()) * sizeof(uint32_t);
}

bool LetterModel::context(const int* history, int length, int symbol, uint32_t* count,
                          uint32_t* total, uint32_t* distinct) const {
    const uint64_t key = keyOf(history, length, symbol);
    const auto contextAt =
        std::lower_bound(contextKeys_.begin(), contextKeys_.end(), contextOf(key));
    if (contextAt == contextKeys_.end() || *contextAt != contextOf(key)) {
        return false;
    }
    const size_t index = static_cast<size_t>(contextAt - contextKeys_.begin());
    *total = contextTotals_[index];
    *distinct = contextDistinct_[index];
    const auto pairAt = std::lower_bound(pairKeys_.begin(), pairKeys_.end(), key);
    *count = (pairAt != pairKeys_.end() && *pairAt == key)
                 ? pairCounts_[static_cast<size_t>(pairAt - pairKeys_.begin())]
                 : 0;
    return true;
}

float LetterModel::spellingLogProb(const PackedTrie& trie, const uint32_t* folded,
                                   int count) const {
    const int unknown = size_ - 1;
    const int kept = std::min(count, kMaxPadded - kOrder);
    int symbols[kMaxPadded];
    for (int i = 0; i < kept; ++i) {
        const int symbol = trie.symbolFor(folded[i]);
        symbols[i] = (symbol > 0 && symbol < unknown) ? symbol : unknown;
    }
    int path[kMaxPadded];
    const int length = pad(symbols, kept, path);
    double logProb = 0.0;
    for (int at = kOrder - 1; at < length; ++at) {
        double probability = share_[path[at]];
        for (int before = 1; before < kOrder; ++before) {
            uint32_t seen = 0;
            uint32_t total = 0;
            uint32_t distinct = 0;
            if (!context(path + at, before, path[at], &seen, &total, &distinct)) {
                break;
            }
            probability = (seen + distinct * probability) / (total + distinct);
        }
        logProb += std::log(probability);
    }
    return static_cast<float>(logProb);
}

}  // namespace borderkeys
