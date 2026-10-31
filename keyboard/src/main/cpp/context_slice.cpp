// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "context_slice.hpp"

namespace borderkeys {
namespace {

/** [value] as a count: zero when negative. */
uint32_t countOf(int32_t value) { return value < 0 ? 0u : static_cast<uint32_t>(value); }

/** [value] as an observation stamp: zero when negative. */
uint64_t stampOf(int64_t value) { return value < 0 ? 0u : static_cast<uint64_t>(value); }

}  // namespace

bool ContextSlice::replace(const int32_t* header, size_t headerInts, const int32_t* links,
                           size_t linkInts, const int32_t* hops, size_t hopInts,
                           const int64_t* seen, size_t seenLongs) {
    clear();
    if (header == nullptr || headerInts != static_cast<size_t>(kHeaderFields)) {
        return false;
    }
    const int32_t pairs = header[kPairsField];
    const int32_t triples = header[kTriplesField];
    const int32_t hopsHanded = header[kHopsField];
    if (pairs < 0 || triples < 0 || hopsHanded < 0 || hopsHanded > kMaxHops) {
        return false;
    }
    const size_t linksHanded = static_cast<size_t>(pairs) + static_cast<size_t>(triples);
    if (linkInts != linksHanded * kLinkFields ||
        hopInts != static_cast<size_t>(hopsHanded) * kHopFields ||
        seenLongs != linksHanded + kContextSeenFields || (linksHanded > 0 && links == nullptr) ||
        (hopsHanded > 0 && hops == nullptr) || seen == nullptr) {
        return false;
    }
    key_ = Key{header[kLanguageField], header[kContext1Field], header[kContext2Field]};
    pairTotal_ = countOf(header[kPairTotalField]);
    tripleTotal_ = countOf(header[kTripleTotalField]);
    pairSeen_ = stampOf(seen[kPairSeenField]);
    tripleSeen_ = stampOf(seen[kTripleSeenField]);
    pairCount_ = pairs;
    links_.reserve(linksHanded);
    for (size_t i = 0; i < linksHanded; ++i) {
        const int32_t* const link = links + i * kLinkFields;
        links_.push_back(Link{link[kLinkWordField], countOf(link[kLinkCountField]),
                              countOf(link[kLinkAssertedField]),
                              stampOf(seen[kContextSeenFields + i])});
    }
    hops_.reserve(static_cast<size_t>(hopsHanded));
    for (int32_t i = 0; i < hopsHanded; ++i) {
        const int32_t* const hop = hops + static_cast<size_t>(i) * kHopFields;
        hops_.push_back(Hop{hop[kHopWordField], countOf(hop[kHopCountField]),
                            countOf(hop[kHopAssertedField]), countOf(hop[kHopMiddleTotalField])});
    }
    return true;
}

void ContextSlice::clear() {
    key_ = Key{};
    pairTotal_ = 0;
    tripleTotal_ = 0;
    pairSeen_ = 0;
    tripleSeen_ = 0;
    pairCount_ = 0;
    links_.clear();
    hops_.clear();
}

bool ContextSlice::matches(const Key& key) const {
    return key.language != kNone && key.context1 != kNone && key.language == key_.language &&
           key.context1 == key_.context1 && key.context2 == key_.context2;
}

uint32_t ContextSlice::pairCountOf(int32_t word, uint32_t* asserted) const {
    return countAmong(0, static_cast<size_t>(pairCount_), word, asserted);
}

uint32_t ContextSlice::tripleCountOf(int32_t word, uint32_t* asserted) const {
    const auto pairs = static_cast<size_t>(pairCount_);
    return countAmong(pairs, links_.size() - pairs, word, asserted);
}

uint32_t ContextSlice::countAmong(size_t first, size_t count, int32_t word,
                                  uint32_t* asserted) const {
    for (size_t i = first; i < first + count; ++i) {
        if (links_[i].word == word) {
            if (asserted != nullptr) {
                *asserted = links_[i].asserted;
            }
            return links_[i].count;
        }
    }
    if (asserted != nullptr) {
        *asserted = 0u;
    }
    return 0u;
}

}  // namespace borderkeys
