// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_NGRAM_MODEL_HPP
#define BORDERKEYS_NGRAM_MODEL_HPP

#include <cstdint>

#include "bkd_format.hpp"

namespace borderkeys {

// Bigram and trigram tables, read in place from the mapping.
//
// Open addressing with linear probing, capacity a power of two, load factor kept below 0.7 by
// the builder. Chaining would mean a pointer dereference per probe into memory the prefetcher
// cannot predict; linear probing walks forward through one cache line most of the time. This is
// looked up several times per candidate and there can be hundreds of candidates per keystroke,
// so the difference is the difference between fitting the 8 ms budget and not.
//
// Word ids are stored offset by one, so that a zero key means "empty slot" without needing a
// separate occupancy bitmap. Word index 0 is a perfectly good word; id 0 is not a word.
//
// Values are quantised natural log-probabilities in one byte: q = round(-logProb * scale),
// saturating at 255. One byte holds about 25 nats at the scale the builder uses, which is far
// more range than a probability that matters for ranking. Storing floats would quadruple the
// table and buy nothing a user could notice.
class NgramModel {
public:
    // Positive: log-probabilities are always <= 0, so any positive value is unambiguously
    // "no entry" without a second return channel.
    static constexpr float kNoEntry = 1.0f;

    bool bind(const uint8_t* base, uint64_t mappedBytes, const BkdHeader& header);

    bool hasBigrams() const { return bigramCapacity_ != 0; }
    bool hasTrigrams() const { return trigramCapacity_ != 0; }

    // `w1`, `w2`, `w3` are word indices as returned by PackedTrie. Returns kNoEntry when the
    // n-gram was not seen.
    float bigram(uint32_t previous, uint32_t current) const;
    float trigram(uint32_t twoBack, uint32_t oneBack, uint32_t current) const;

private:
    // splitmix64's finaliser. Word indices are dense small integers, so the low bits of any
    // cheap combination of them are heavily correlated; masking those directly would cluster
    // every probe sequence into the same few slots.
    static uint64_t mix(uint64_t value) {
        value += 0x9E3779B97F4A7C15ull;
        value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9ull;
        value = (value ^ (value >> 27)) * 0x94D049BB133111EBull;
        return value ^ (value >> 31);
    }

    float dequantise(uint8_t quantised) const {
        return -static_cast<float>(quantised) / logProbScale_;
    }

    const uint64_t* bigramKeys_ = nullptr;
    const uint8_t* bigramValues_ = nullptr;
    uint32_t bigramCapacity_ = 0;
    uint32_t bigramMask_ = 0;

    const uint32_t* trigramKeys_ = nullptr;  // three consecutive u32 per slot
    const uint8_t* trigramValues_ = nullptr;
    uint32_t trigramCapacity_ = 0;
    uint32_t trigramMask_ = 0;

    float logProbScale_ = 1.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_NGRAM_MODEL_HPP
