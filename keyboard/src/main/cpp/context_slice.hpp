// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_CONTEXT_SLICE_HPP
#define BORDERKEYS_CONTEXT_SLICE_HPP

#include <cstddef>
#include <cstdint>
#include <vector>

namespace borderkeys {

// The personal links of one context, as Kotlin hands them over before the requests that read
// them: the pairs after the word before and the triples after the two words before, each naming
// its last word by its id in the word table, in the order the engine offers them; each context's
// total and observations; and, for the first pairs, the pair that follows each one's word. The
// requests read it and never write it.
class ContextSlice {
public:
    /** What the header holds, one int each, in this order. */
    enum HeaderField : int {
        kLanguageField = 0,
        kContext1Field = 1,
        kContext2Field = 2,
        kPairTotalField = 3,
        kTripleTotalField = 4,
        kPairsField = 5,
        kTriplesField = 6,
        kHopsField = 7,
        kHeaderFields = 8,
    };

    /**
     * What each link holds, one int each, in this order: its last word's id, its count and how
     * many times it was chosen on purpose. The pairs come first, then the triples.
     */
    enum LinkField : int {
        kLinkWordField = 0,
        kLinkCountField = 1,
        kLinkAssertedField = 2,
        kLinkFields = 3,
    };

    /**
     * What each hop holds, one int each, in this order: the id of the word after the pair's word
     * (kNone for none), that link's count and choices, and the total of the context the pair's
     * word starts.
     */
    enum HopField : int {
        kHopWordField = 0,
        kHopCountField = 1,
        kHopAssertedField = 2,
        kHopMiddleTotalField = 3,
        kHopFields = 4,
    };

    /**
     * What the stamps hold, one long each: the observations of the pairs' context, then of the
     * triples', then each link's context's observations at its last use, in the links' order.
     */
    enum SeenField : int {
        kPairSeenField = 0,
        kTripleSeenField = 1,
        kContextSeenFields = 2,
    };

    /** The most hops a slice holds: one for each of the first pairs a phrase may start with. */
    static constexpr int kMaxHops = 3;

    /** The id naming no language, no context word or no word. */
    static constexpr int32_t kNone = -1;

    /** The context a request names: its language and its two words' ids, kNone for none. */
    struct Key {
        int32_t language = kNone;
        int32_t context1 = kNone;
        int32_t context2 = kNone;
    };

    /**
     * A link: its last word's id, its count, its choices, and its context's observations at its
     * last use.
     */
    struct Link {
        int32_t word;
        uint32_t count;
        uint32_t asserted;
        uint64_t seen;
    };

    /**
     * The link after a pair's word: its word's id (kNone for none), its count and choices, and the
     * total of the context the pair's word starts.
     */
    struct Hop {
        int32_t word;
        uint32_t count;
        uint32_t asserted;
        uint32_t middleTotal;
    };

    /**
     * Replaces the slice with the arrays handed: `header` holds kHeaderFields ints, `links`
     * kLinkFields per pair and triple, `hops` kHopFields per hop and `seen` kContextSeenFields
     * longs plus one per link, a negative count or total held at zero. Returns false, leaving the
     * slice empty, when the lengths disagree with the header or it names more than kMaxHops hops.
     */
    bool replace(const int32_t* header, size_t headerInts, const int32_t* links, size_t linkInts,
                 const int32_t* hops, size_t hopInts, const int64_t* seen, size_t seenLongs);

    /** Empties the slice: it names no context and holds no link. */
    void clear();

    /**
     * Whether the slice is the one of `key`'s context; a key naming no language, or no word before,
     * matches none.
     */
    bool matches(const Key& key) const;

    const Key& key() const { return key_; }
    uint32_t pairTotal() const { return pairTotal_; }
    uint32_t tripleTotal() const { return tripleTotal_; }
    uint64_t pairSeen() const { return pairSeen_; }
    uint64_t tripleSeen() const { return tripleSeen_; }

    int pairCount() const { return pairCount_; }
    int tripleCount() const { return static_cast<int>(links_.size()) - pairCount_; }
    int hopCount() const { return static_cast<int>(hops_.size()); }

    /** The pair, triple or hop at `index`, in the order handed. */
    const Link& pair(int index) const { return links_[static_cast<size_t>(index)]; }
    const Link& triple(int index) const { return links_[static_cast<size_t>(pairCount_ + index)]; }
    const Hop& hop(int index) const { return hops_[static_cast<size_t>(index)]; }

    /** The count of the pair to `word`, zero for none; `asserted`, when given, gets its choices. */
    uint32_t pairCountOf(int32_t word, uint32_t* asserted = nullptr) const;

    /** [pairCountOf], for the triples. */
    uint32_t tripleCountOf(int32_t word, uint32_t* asserted = nullptr) const;

private:
    /** The count of the link to `word` among `count` links from `first`. */
    uint32_t countAmong(size_t first, size_t count, int32_t word, uint32_t* asserted) const;

    Key key_;
    uint32_t pairTotal_ = 0;
    uint32_t tripleTotal_ = 0;
    uint64_t pairSeen_ = 0;
    uint64_t tripleSeen_ = 0;
    int pairCount_ = 0;
    // The pairs, then the triples.
    std::vector<Link> links_;
    std::vector<Hop> hops_;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_CONTEXT_SLICE_HPP
