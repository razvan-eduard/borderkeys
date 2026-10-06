// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_LETTER_MODEL_HPP
#define BORDERKEYS_LETTER_MODEL_HPP

#include <cstdint>
#include <vector>

namespace borderkeys {

class PackedTrie;

// How likely a run of folded code points is as a spelling of a pack's language: each symbol's
// chance after the kOrder - 1 before it, counted once over every folded spelling the pack offers,
// each order interpolated with the one below by Witten-Bell smoothing, down to each symbol's own
// share. Symbol 0 stands for the space before a spelling and after it; a code point outside the
// pack's alphabet counts as one symbol no spelling holds.
class LetterModel {
public:
    static constexpr int kOrder = 5;

    void build(const PackedTrie& trie);
    void clear();
    bool isBuilt() const { return size_ > 0; }

    // The log-probability of `folded[0..count)` as a whole spelling, its end included.
    float spellingLogProb(const PackedTrie& trie, const uint32_t* folded, int count) const;

    // The bytes the counts take.
    size_t bytes() const;

private:
    // How often `symbol` followed the `length` symbols ending at `history`'s last, and that
    // context's total and distinct followers; false when the context was never seen.
    bool context(const int* history, int length, int symbol, uint32_t* count, uint32_t* total,
                 uint32_t* distinct) const;

    int size_ = 0;
    std::vector<double> share_;
    std::vector<uint64_t> pairKeys_;
    std::vector<uint32_t> pairCounts_;
    std::vector<uint64_t> contextKeys_;
    std::vector<uint32_t> contextTotals_;
    std::vector<uint32_t> contextDistinct_;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_LETTER_MODEL_HPP
