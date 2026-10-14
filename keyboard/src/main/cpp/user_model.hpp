// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_USER_MODEL_HPP
#define BORDERKEYS_USER_MODEL_HPP

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <limits>
#include <string>
#include <unordered_map>
#include <vector>

#include "proximity.hpp"

namespace borderkeys {

// What this device has learned about the person using it: each word with how often it was
// committed and how often chosen on purpose, and the pairs and triples the words form. In RAM,
// rebuilt from the Room tables at every start, never written by this class. A node-per-character
// trie with a sorted child list per node, keyed by folded code points, each node holding one
// entry per spelling that folds to it, case aside; the pairs and triples in hash maps keyed by
// their entries, with each context's links and their total.
class UserModel {
public:
    struct Completion {
        uint32_t entryIndex;
        uint32_t count;
    };

    /** A word this person has been seen to write after another one, and how often. */
    struct Successor {
        uint32_t entryIndex;
        uint32_t count;
    };

    /**
     * A link after one context, as the budget weighs it: its last word's entry, its count, and how
     * many times its context had been seen when it last was.
     */
    struct ContextLink {
        int32_t nextIndex;
        uint32_t count;
        uint64_t contextSeen;
    };

    /** A context's total, its uses no longer held included, and how many times it has been seen. */
    struct ContextCount {
        uint32_t total;
        uint64_t seen;
    };

    /**
     * How many pairs a load puts in one language's store; learning adds past it until the budget
     * prunes. Matches PhraseBudget.LANGUAGE_CAP.
     */
    static constexpr int kMaxBigrams = 10000;

    /** [kMaxBigrams], for the three-word sequences. */
    static constexpr int kMaxTrigrams = 10000;

    UserModel();

    void clear();

    // Replaces everything with the given words, at service start. `deliberateCapitals` and
    // `asserted` are parallel counts, or null for zeros.
    void bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                  int count, const int32_t* deliberateCapitals = nullptr,
                  const int32_t* asserted = nullptr);

    /**
     * Records that the user committed this word, adding it if new; returns its entry index.
     * [deliberateCapital] counts a first letter the user capitalised, with shift or under a caps
     * lock they set; [asserted] counts a word chosen on purpose, picked from the strip or put back
     * after a correction. Neither count goes down.
     */
    int32_t learn(const char* word, size_t length, bool deliberateCapital = false,
                  bool asserted = false);

    /** The entry index for `word`'s spelling, case aside, or -1. */
    int32_t entryIndexFor(const char* word, size_t length) const;

    /**
     * The entry for `word`, created with a count of zero when there is none: a word that pairs
     * can name as their context without ever being counted or offered as a word itself.
     */
    int32_t reserve(const char* word, size_t length);

    /**
     * The link store of `language`, a primary language subtag, created when new; -1 for an empty
     * language.
     */
    int32_t storeFor(const char* language, size_t length);

    /** The link store of `language`, or -1 when it has none. */
    int32_t findStore(const char* language, size_t length) const;

    /**
     * Records in `store` that `next` followed `previous`, both indices from [learn] or
     * [entryIndexFor]: the store's pair clock moves on one, the context's total and observations
     * one, the pair's count one, and the pair is stamped with the context's observations and the
     * clock.
     */
    bool learnBigram(int32_t store, int32_t previousIndex, int32_t nextIndex);

    /** How many times `next` has followed `previous` in `store`. Zero when the pair is unknown. */
    uint32_t bigramCount(int32_t store, int32_t previousIndex, int32_t nextIndex) const;

    /**
     * How often `previous` has been followed by anything in `store`, pairs no longer held
     * included.
     */
    uint32_t successorTotal(int32_t store, int32_t previousIndex) const;

    /**
     * The words seen after `previous` in `store`, most frequent first and, among equals, the one
     * recorded first after it, up to `maxOut`.
     */
    int successors(int32_t store, int32_t previousIndex, Successor* out, int maxOut) const;

    /**
     * Records in `store` that `next` followed `previous1`, which followed `previous2`, as
     * [learnBigram] records a pair, on the triple clock.
     */
    bool learnTrigram(int32_t store, int32_t previous2Index, int32_t previous1Index,
                      int32_t nextIndex);

    /** How often this exact three-word sequence has been written in `store`. */
    uint32_t trigramCount(int32_t store, int32_t previous2Index, int32_t previous1Index,
                          int32_t nextIndex) const;

    /**
     * How often those two words have been followed by anything in `store`, triples no longer
     * held included.
     */
    uint32_t trigramTotal(int32_t store, int32_t previous2Index, int32_t previous1Index) const;

    /** The words seen after that pair in `store`, in [successors]' order, up to `maxOut`. */
    int trigramSuccessors(int32_t store, int32_t previous2Index, int32_t previous1Index,
                          Successor* out, int maxOut) const;

    /**
     * The pairs after `previous` in `store`, in the order recorded, into `out` up to `maxOut`, and
     * the context's total and observations into `context`, zeros for a context not held. Returns
     * how many pairs follow it.
     */
    int pairsAfter(int32_t store, int32_t previousIndex, ContextLink* out, int maxOut,
                   ContextCount* context) const;

    /** [pairsAfter], for the triples after `previous2` and `previous1`. */
    int triplesAfter(int32_t store, int32_t previous2Index, int32_t previous1Index,
                     ContextLink* out, int maxOut, ContextCount* context) const;

    /**
     * How many pairs, and how many triples, `store` holds. [pairAt] and [tripleAt] read the one at
     * `index`: its words' entries, its count, when its language last saw it, and its context's
     * total; an out left null is skipped.
     */
    int pairsHeld(int32_t store) const;
    int triplesHeld(int32_t store) const;
    void pairAt(int32_t store, int index, int32_t* previousIndex, int32_t* nextIndex,
                uint32_t* count, uint64_t* languageSeen = nullptr,
                uint32_t* contextTotal = nullptr) const;
    void tripleAt(int32_t store, int index, int32_t* previous2Index, int32_t* previous1Index,
                  int32_t* nextIndex, uint32_t* count, uint64_t* languageSeen = nullptr,
                  uint32_t* contextTotal = nullptr) const;

    /**
     * Replaces every store's pairs. Used at start, from the database: each row goes to the store
     * of its language; rows that name the same two entries in one store add up, keeping their
     * later stamps, and rows past kMaxBigrams in a store are left out. `contextSeen` and
     * `languageSeen` are parallel stamps, or null for zeros.
     */
    void bulkLoadBigrams(const char* const* languages, const size_t* languageLengths,
                         const char* const* previous, const size_t* previousLengths,
                         const char* const* next, const size_t* nextLengths,
                         const int32_t* counts, int count, const uint64_t* contextSeen = nullptr,
                         const uint64_t* languageSeen = nullptr);

    /** [bulkLoadBigrams], for the three-word sequences and kMaxTrigrams. */
    void bulkLoadTrigrams(const char* const* languages, const size_t* languageLengths,
                          const char* const* previous2, const size_t* previous2Lengths,
                          const char* const* previous1, const size_t* previous1Lengths,
                          const char* const* next, const size_t* nextLengths,
                          const int32_t* counts, int count, const uint64_t* contextSeen = nullptr,
                          const uint64_t* languageSeen = nullptr);

    /**
     * Sets how many times each `previous` has been seen in its language's store, held at no less
     * than the counts of the pairs it holds, and, from `seen` when given, how many times it has
     * been observed. Called after [bulkLoadBigrams].
     */
    void loadPairTotals(const char* const* languages, const size_t* languageLengths,
                        const char* const* previous, const size_t* previousLengths,
                        const uint32_t* totals, int count, const uint64_t* seen = nullptr);

    /** [loadPairTotals], for the two-word contexts of the triples. */
    void loadTripleTotals(const char* const* languages, const size_t* languageLengths,
                          const char* const* previous2, const size_t* previous2Lengths,
                          const char* const* previous1, const size_t* previous1Lengths,
                          const uint32_t* totals, int count, const uint64_t* seen = nullptr);

    /**
     * Sets each row's language's pair clock and triple clock, the store made when new; every
     * other store's clocks go back to zero.
     */
    void loadClocks(const char* const* languages, const size_t* languageLengths,
                    const uint64_t* pairClocks, const uint64_t* tripleClocks, int count);

    /** Drops one pair from `store`; its context's total keeps the pair's uses. */
    void dropBigram(int32_t store, int32_t previousIndex, int32_t nextIndex);

    /** Drops one triple from `store`; its context's total keeps the triple's uses. */
    void dropTrigram(int32_t store, int32_t previous2Index, int32_t previous1Index,
                     int32_t nextIndex);

    /**
     * Drops each row's pair from its language's store, as [dropBigram]; a row naming a store or a
     * word not held drops nothing.
     */
    void dropBigrams(const char* const* languages, const size_t* languageLengths,
                     const char* const* previous, const size_t* previousLengths,
                     const char* const* next, const size_t* nextLengths, int count);

    /** [dropBigrams], for the triples. */
    void dropTrigrams(const char* const* languages, const size_t* languageLengths,
                      const char* const* previous2, const size_t* previous2Lengths,
                      const char* const* previous1, const size_t* previous1Lengths,
                      const char* const* next, const size_t* nextLengths, int count);

    uint32_t countFor(const char* word, size_t length) const;
    uint32_t totalCount() const { return totalCount_; }
    size_t size() const { return entries_.size(); }

    const char* entryText(uint32_t entryIndex, uint32_t* lengthOut) const;

    /** The entry's spelling in lower case, its marks kept: the word as a link's words are keyed. */
    const char* entryKey(uint32_t entryIndex, uint32_t* lengthOut) const;

    uint32_t entryCount(uint32_t entryIndex) const;

    /** How many times this entry was committed with a deliberate capital; see [learn]. */
    uint32_t deliberateCapitals(uint32_t entryIndex) const;

    /** How many times this entry was chosen on purpose; see [learn]. */
    uint32_t asserted(uint32_t entryIndex) const;

    // Words starting with exactly an already folded prefix, in no particular order, up to
    // `maxOut`.
    int completions(const uint32_t* foldedPrefix, int prefixLength, Completion* out,
                    int maxOut) const;

private:
    struct Node {
        // (folded code point, child node index), kept sorted by code point.
        std::vector<std::pair<uint32_t, int32_t>> children;
        // The entries whose spellings fold to this node, one per spelling, case aside.
        std::vector<int32_t> entries;
    };
    struct Entry {
        std::string text;
        // The spelling in lower case, its marks kept.
        std::string key;
        uint32_t count = 0;
        uint32_t deliberateCapitals = 0;
        uint32_t asserted = 0;
    };

    int32_t childOf(int32_t node, uint32_t folded) const;
    int32_t childOfOrCreate(int32_t node, uint32_t folded);
    int32_t findNode(const uint32_t* folded, int count) const;
    /** The entry at `node` whose key is the `length` bytes at `key`, or -1. */
    int32_t entryWithKey(int32_t node, const char* key, size_t length) const;
    /** The entry for `word`, created empty when missing; -1 for a word that cannot be held. */
    int32_t entryFor(const char* word, size_t length);
    void collect(int32_t node, Completion* out, int maxOut, int* written) const;

    struct TrigramKey {
        int32_t previous2Index;
        int32_t previous1Index;
        int32_t nextIndex;

        bool operator==(const TrigramKey& other) const {
            return previous2Index == other.previous2Index &&
                   previous1Index == other.previous1Index && nextIndex == other.nextIndex;
        }
    };

    struct TrigramKeyHash {
        size_t operator()(const TrigramKey& key) const;
    };

    // A pair: its key is its two entries, its context's key the entry before; its stamps are how
    // many times its context had been seen, and its store's pair clock, when it was last recorded.
    struct Bigram {
        int32_t previousIndex;
        int32_t nextIndex;
        uint32_t count;
        uint64_t contextSeen = 0;
        uint64_t languageSeen = 0;

        static constexpr size_t kWords = 2;
        using Key = uint64_t;
        using KeyHash = std::hash<uint64_t>;
        using ContextKey = int32_t;

        static Key keyOf(int32_t previous, int32_t next) { return pairKey(previous, next); }
        static ContextKey contextKeyOf(int32_t previous) { return previous; }
        Key key() const { return keyOf(previousIndex, nextIndex); }
        ContextKey contextKey() const { return contextKeyOf(previousIndex); }
        int32_t lastContextIndex() const { return previousIndex; }
        std::array<int32_t, kWords> indices() const { return {previousIndex, nextIndex}; }
    };

    // A triple: its key is its three entries, its context's key the two entries before; its stamps
    // as a pair's, on the triple clock.
    struct Trigram {
        int32_t previous2Index;
        int32_t previous1Index;
        int32_t nextIndex;
        uint32_t count;
        uint64_t contextSeen = 0;
        uint64_t languageSeen = 0;

        static constexpr size_t kWords = 3;
        using Key = TrigramKey;
        using KeyHash = TrigramKeyHash;
        using ContextKey = uint64_t;

        static Key keyOf(int32_t previous2, int32_t previous1, int32_t next) {
            return TrigramKey{previous2, previous1, next};
        }
        static ContextKey contextKeyOf(int32_t previous2, int32_t previous1) {
            return pairKey(previous2, previous1);
        }
        Key key() const { return keyOf(previous2Index, previous1Index, nextIndex); }
        ContextKey contextKey() const { return contextKeyOf(previous2Index, previous1Index); }
        int32_t lastContextIndex() const { return previous1Index; }
        std::array<int32_t, kWords> indices() const {
            return {previous2Index, previous1Index, nextIndex};
        }
    };

    // The links that follow one context: their slots, how often the context was followed, and how
    // many times it has been seen.
    struct Successors {
        // The context's links, in the order they were recorded.
        std::vector<uint32_t> slots;
        uint32_t total = 0;
        uint64_t seen = 0;
    };

    // One kind of link in one language: every link by slot, each link's slot by its key, and each
    // context's links.
    template <typename Link>
    struct LinkTable {
        std::vector<Link> links;
        std::unordered_map<typename Link::Key, uint32_t, typename Link::KeyHash> slots;
        std::unordered_map<typename Link::ContextKey, Successors> contexts;
    };

    // One language's pairs and triples, and how many of each it has recorded.
    struct LinkStore {
        std::string language;
        LinkTable<Bigram> pairs;
        LinkTable<Trigram> triples;
        uint64_t pairClock = 0;
        uint64_t tripleClock = 0;
    };

    /** The longest word held, in bytes and in code points. */
    static constexpr uint32_t kMaxWordBytes = 256u;
    static constexpr int kMaxWordCodePoints = 64;

    /** The most bytes a held word takes once lowered: every code point at its widest. */
    static constexpr int kMaxKeyBytes = kMaxWordCodePoints * kMaxUtf8Bytes;

    /** The bits of an entry index in a pair key, the width of its half. */
    static constexpr int kKeyHalfBits = std::numeric_limits<uint32_t>::digits;

    /** The odd 64-bit integer nearest 2^64 divided by the golden ratio. */
    static constexpr uint64_t kGoldenRatioMultiplier = 0x9E3779B97F4A7C15ull;

    /** Two entry indices as one key, the first in the high half. */
    static uint64_t pairKey(int32_t first, int32_t second);

    /**
     * [word] in lower case, its marks kept, into [out], which holds kMaxKeyBytes; its length in
     * bytes, or zero for no word or one too long.
     */
    static int wordKey(const char* word, size_t length, char* out);

    const LinkStore* storeAt(int32_t store) const;
    LinkStore* storeAt(int32_t store);

    /** Whether every one of `indices` names an entry. */
    template <typename... Indices>
    static bool named(Indices... indices) {
        return ((indices >= 0) && ...);
    }

    /**
     * Whether every entry `link` names is one of the first `entries`, and its last two differ: a
     * word following itself is not learned.
     */
    template <typename Link>
    static bool linkable(const Link& link, int32_t entries);

    /**
     * Adds `link`'s count to the link and its context's total, creating the link when `table`
     * holds fewer than `most`; the link keeps the later of its stamps and `link`'s. Returns false,
     * changing nothing, for a new link with no room.
     */
    template <typename Link>
    static bool add(LinkTable<Link>& table, const Link& link, int most);

    /** Takes the link at `slot` out; the last link takes its slot. */
    template <typename Link>
    static void removeAt(LinkTable<Link>& table, uint32_t slot);

    /** Takes out the link with `key`, when held. */
    template <typename Link>
    static void drop(LinkTable<Link>& table, const typename Link::Key& key);

    /** The count of the link with `key`; zero when none is held. */
    template <typename Link>
    static uint32_t countOf(const LinkTable<Link>& table, const typename Link::Key& key);

    /** The total of the context with `key`; zero when none is held. */
    template <typename Link>
    static uint32_t totalOf(const LinkTable<Link>& table, const typename Link::ContextKey& key);

    /** The context's links, in [successors]' order, up to `maxOut`. */
    template <typename Link>
    static int successorsOf(const LinkTable<Link>& table, const typename Link::ContextKey& key,
                            Successor* out, int maxOut);

    /** The context's links, in the order recorded, as [pairsAfter] gives them. */
    template <typename Link>
    static int linksAfter(const LinkTable<Link>& table, const typename Link::ContextKey& key,
                          ContextLink* out, int maxOut, ContextCount* context);

    /** The link at `index`, or null. */
    template <typename Link>
    static const Link* linkAt(const LinkTable<Link>& table, int index);

    /**
     * Records one use of `link`, of a count of one, in the `kind` of `store`, on its `clock`, as
     * [learnBigram] records a pair.
     */
    template <typename Link>
    bool learnLink(int32_t store, LinkTable<Link> LinkStore::*kind, uint64_t LinkStore::*clock,
                   Link link);

    /**
     * Replaces the `kind` of every store with the rows: `linkAt(i, count)` gives row `i`'s link,
     * each row going to its language's store, stamped from `contextSeen` and `languageSeen` when
     * given; `wordsGiven` is false when a word column is null.
     */
    template <typename Link, typename LinkAt>
    void loadLinks(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                   const size_t* languageLengths, const int32_t* counts, int count, bool wordsGiven,
                   int most, const uint64_t* contextSeen, const uint64_t* languageSeen,
                   LinkAt linkAt);

    /**
     * Raises each row's context total in the `kind` of its language's store to the row's, never
     * lowering it, and its observations to `seen`'s when given: `contextAt(i, key)` gives row
     * `i`'s context key, false for a word not held; `wordsGiven` is false when a word column is
     * null.
     */
    template <typename Link, typename ContextAt>
    void loadTotals(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                    const size_t* languageLengths, const uint32_t* totals, const uint64_t* seen,
                    int count, bool wordsGiven, ContextAt contextAt);

    /**
     * Drops from the `kind` of each row's language store the link `keyAt(i)` names for row `i`;
     * `wordsGiven` is false when a word column is null.
     */
    template <typename Link, typename KeyAt>
    void dropLinks(LinkTable<Link> LinkStore::*kind, const char* const* languages,
                   const size_t* languageLengths, int count, bool wordsGiven, KeyAt keyAt);

    std::vector<Node> nodes_;
    std::vector<Entry> entries_;
    std::vector<LinkStore> stores_;
    uint32_t totalCount_ = 0;
};

/** The language of a tag that names none, as LanguageTags.UNKNOWN. */
constexpr const char* kUnknownLanguage = "und";

/** What ends a tag's primary language subtag, as LanguageTags reads a tag. */
constexpr char kSubtagSeparator = '-';
constexpr char kLegacySubtagSeparator = '_';

/**
 * A tag's primary language subtag in lower case, kUnknownLanguage for none: as a store names its
 * language.
 */
std::string primaryLanguageOf(const char* tag, size_t length);

}  // namespace borderkeys

#endif  // BORDERKEYS_USER_MODEL_HPP
