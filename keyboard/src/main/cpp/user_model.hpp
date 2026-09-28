// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_USER_MODEL_HPP
#define BORDERKEYS_USER_MODEL_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace borderkeys {

// What this device has learned about the person using it: each word with how often it was
// committed and how often chosen on purpose, and the pairs and triples the words form. In RAM,
// rebuilt from the Room tables at every start, never written by this class. A node-per-character
// trie with a sorted child list per node.
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

    /** How many pairs are remembered; past it the least used is dropped. */
    static constexpr int kMaxBigrams = 4096;

    /** How many three-word sequences are remembered; past it the least used is dropped. */
    static constexpr int kMaxTrigrams = 2048;

    UserModel();

    void clear();

    // Replaces everything with the given words, at service start. `deliberateCapitals` and
    // `asserted` are parallel counts, or null for zeros.
    void bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                  int count, const int32_t* deliberateCapitals = nullptr,
                  const int32_t* asserted = nullptr);

    /**
     * Records that the user committed this word, adding it if new; returns its entry index.
     * [deliberateCapital] counts a first letter the user capitalised with shift; [asserted] counts
     * a word chosen on purpose, picked from the strip or put back after a correction. Neither
     * count goes down.
     */
    int32_t learn(const char* word, size_t length, bool deliberateCapital = false,
                  bool asserted = false);

    /** Records that `next` followed `previous`; both indices from [learn] or [entryIndexFor]. */
    void learnBigram(int32_t previousIndex, int32_t nextIndex);

    /** The entry index for an exact (folded) word, or -1. */
    int32_t entryIndexFor(const char* word, size_t length) const;

    /**
     * The entry for `word`, created with a count of zero when there is none: a word that pairs
     * can name as their context without ever being counted or offered as a word itself.
     */
    int32_t reserve(const char* word, size_t length);

    /** How many times `next` has followed `previous`. Zero when the pair is unknown. */
    uint32_t bigramCount(int32_t previousIndex, int32_t nextIndex) const;

    /** How often `previous` has been followed by anything at all. */
    uint32_t successorTotal(int32_t previousIndex) const;

    /** The words seen after `previous`, most frequent first, up to `maxOut`. */
    int successors(int32_t previousIndex, Successor* out, int maxOut) const;

    /** Records that `next` followed `previous1`, which followed `previous2`. */
    void learnTrigram(int32_t previous2Index, int32_t previous1Index, int32_t nextIndex);

    /** How often this exact three-word sequence has been written. */
    uint32_t trigramCount(int32_t previous2Index, int32_t previous1Index,
                          int32_t nextIndex) const;

    /** How often those two words have been followed by anything at all. */
    uint32_t trigramTotal(int32_t previous2Index, int32_t previous1Index) const;

    /** The words seen after that pair, up to `maxOut`. */
    int trigramSuccessors(int32_t previous2Index, int32_t previous1Index, Successor* out,
                          int maxOut) const;

    int trigramCount() const { return static_cast<int>(trigrams_.size()); }
    void trigramAt(int index, int32_t* previous2Index, int32_t* previous1Index,
                   int32_t* nextIndex, uint32_t* count) const;

    void bulkLoadTrigrams(const char* const* previous2, const size_t* previous2Lengths,
                          const char* const* previous1, const size_t* previous1Lengths,
                          const char* const* next, const size_t* nextLengths,
                          const int32_t* counts, int count);

    /** Every remembered pair, for persisting them. */
    int bigramCount() const { return static_cast<int>(bigrams_.size()); }
    void bigramAt(int index, int32_t* previousIndex, int32_t* nextIndex, uint32_t* count) const;

    /** Replaces the remembered pairs. Used once at start, from the database. */
    void bulkLoadBigrams(const char* const* previous, const size_t* previousLengths,
                         const char* const* next, const size_t* nextLengths,
                         const int32_t* counts, int count);

    uint32_t countFor(const char* word, size_t length) const;
    uint32_t totalCount() const { return totalCount_; }
    size_t size() const { return entries_.size(); }

    const char* entryText(uint32_t entryIndex, uint32_t* lengthOut) const;
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
        int32_t entryIndex = -1;
    };
    struct Entry {
        std::string text;
        uint32_t count = 0;
        uint32_t deliberateCapitals = 0;
        uint32_t asserted = 0;
    };

    int32_t childOf(int32_t node, uint32_t folded) const;
    int32_t childOfOrCreate(int32_t node, uint32_t folded);
    int32_t findNode(const uint32_t* folded, int count) const;
    /** The entry for `word`, created empty when missing; -1 for a word that cannot be held. */
    int32_t entryFor(const char* word, size_t length);
    void collect(int32_t node, Completion* out, int maxOut, int* written) const;

    struct Bigram {
        int32_t previousIndex;
        int32_t nextIndex;
        uint32_t count;
    };

    struct Trigram {
        int32_t previous2Index;
        int32_t previous1Index;
        int32_t nextIndex;
        uint32_t count;
    };

    void dropLeastUsedBigram();
    void dropLeastUsedTrigram();

    std::vector<Node> nodes_;
    std::vector<Entry> entries_;
    // The pairs, in a flat vector scanned linearly.
    std::vector<Bigram> bigrams_;
    std::vector<Trigram> trigrams_;
    uint32_t totalCount_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_USER_MODEL_HPP
