// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_USER_MODEL_HPP
#define BORDERKEYS_USER_MODEL_HPP

#include <cstdint>
#include <string>
#include <vector>

namespace borderkeys {

// What this device has learned about the person using it. Mutable, in RAM, never leaves the
// process except through an explicit snapshot into the app's private storage.
//
// This is the whole of "personalisation" in BorderKeys. There is no model being fine-tuned and
// no gradient anywhere: a count goes up when the user picks a word that was not the top
// suggestion, and that is the entire learning rule. It is also the reason the keyboard does not
// get worse over time the way a model trained on its own output does -- a count cannot learn a
// typo unless the user deliberately chose the typo.
//
// A mutable double array would have to be rebuilt on nearly every insertion, so the structure
// here is an ordinary node-per-character trie with a sorted child list per node. Insertion is
// off the hot path by construction (it is debounced in Kotlin and flushed on onFinishInput);
// prefix lookup is on the hot path and is a binary search per character over a list that is
// almost always one or two entries long.
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
     * How many pairs are remembered.
     *
     * A cap rather than unbounded growth, because this is the structure that grows fastest with
     * use and it is held in RAM on the prediction thread. Four thousand pairs is far more than
     * the handful of phrases a person repeats, and when it is full the least used pair is
     * dropped -- so a phrase typed once years ago does not hold a slot against one typed daily.
     */
    static constexpr int kMaxBigrams = 4096;

    /**
     * How many three-word sequences are remembered.
     *
     * Half the pairs, because a triple is both rarer and narrower: it fires only when the last
     * two words match, so a table the same size would hold mostly entries that never come up.
     */
    static constexpr int kMaxTrigrams = 2048;

    UserModel();

    void clear();

    // Replaces everything with the given words. Used once at service start, from the Room
    // table, so that native and database agree before the first keystroke.
    void bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                  int count);

    // Records that the user chose this word. Adds it if it is new. Returns its entry index.
    int32_t learn(const char* word, size_t length);

    /**
     * Records that `next` followed `previous`.
     *
     * Separate from [learn] because the two are learned at different moments and one can fail
     * without the other: the word is always worth remembering, the pair only when both halves
     * are already known. Both indices come from [learn] or [entryIndexFor].
     */
    void learnBigram(int32_t previousIndex, int32_t nextIndex);

    /** The entry index for an exact (folded) word, or -1. */
    int32_t entryIndexFor(const char* word, size_t length) const;

    /** How many times `next` has followed `previous`. Zero when the pair is unknown. */
    uint32_t bigramCount(int32_t previousIndex, int32_t nextIndex) const;

    /** How often `previous` has been followed by anything at all. */
    uint32_t successorTotal(int32_t previousIndex) const;

    /** The words seen after `previous`, most frequent first, up to `maxOut`. */
    int successors(int32_t previousIndex, Successor* out, int maxOut) const;

    /**
     * Records that `next` followed `previous1`, which followed `previous2`.
     *
     * Learned alongside the pair rather than instead of it: a triple that has been seen once
     * says less than a pair seen ten times, and the scorer needs both to choose between them.
     */
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

    // Words starting with an already folded prefix, in no particular order, up to `maxOut`.
    // Exact prefix only: a typo in a user word is still corrected, but through the language
    // pack, because that is where the geometry-aware walk lives. Duplicating the fuzzy search
    // here would double the code that has to stay inside the latency budget for a table that
    // holds thousands of words rather than hundreds of thousands.
    int completions(const uint32_t* foldedPrefix, int prefixLength, Completion* out,
                    int maxOut) const;

    // Binary snapshot into the app's private storage. Written on a debounce, never per
    // keystroke. Returns true only if the file was fully written and renamed into place.
    bool snapshot(const char* path) const;
    bool restore(const char* path);

private:
    struct Node {
        // (folded code point, child node index), kept sorted by code point.
        std::vector<std::pair<uint32_t, int32_t>> children;
        int32_t entryIndex = -1;
    };
    struct Entry {
        std::string text;
        uint32_t count = 0;
    };

    int32_t childOf(int32_t node, uint32_t folded) const;
    int32_t childOfOrCreate(int32_t node, uint32_t folded);
    int32_t findNode(const uint32_t* folded, int count) const;
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
    // A flat vector rather than a hash. It is capped at kMaxBigrams, every access is off the UI
    // thread, and a linear scan of four thousand 12-byte records is a few microseconds of
    // sequential memory -- against a hash table that would need its own rehashing, its own
    // serialisation, and a second structure to enumerate one word's successors, which is the
    // access this exists for.
    std::vector<Bigram> bigrams_;
    std::vector<Trigram> trigrams_;
    uint32_t totalCount_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_USER_MODEL_HPP
