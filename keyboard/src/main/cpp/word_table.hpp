// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_WORD_TABLE_HPP
#define BORDERKEYS_WORD_TABLE_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "proximity.hpp"

namespace borderkeys {

// The words this person has written, as Kotlin hands them over: one entry per word, case aside,
// with the spelling to serve and the counts Kotlin merged, each entry under the id it was given at
// the last load or put. Never counted or merged here. A node-per-character trie with a sorted child
// list per node, keyed by folded code points, each node holding one entry per spelling that folds
// to it, case aside.
class WordTable {
public:
    struct Completion {
        uint32_t entryIndex;
        uint32_t count;
    };

    /** What a row hands for each word, in this order: one int per field. */
    enum Field : int {
        kCountField = 0,
        kDeliberateCapitalsField,
        kAssertedField,
        kAddedField,
        kFields,
    };

    /** The id a put names a word by when the table holds no entry for it yet. */
    static constexpr int32_t kNewWord = -1;

    WordTable();

    void clear();

    /**
     * Replaces every entry with the rows: row `i` spelled `spellings[i]`, its fields the
     * [kFields] ints at `fields[i * kFields]`, a negative one held as zero. Each row held takes
     * the next id from zero, written to `outIds[i]`; a row that cannot be held, or a second row
     * for a word already loaded, gets [kNewWord]. Returns how many rows are held.
     */
    int load(const char* const* spellings, const size_t* lengths, const int32_t* fields, int count,
             int32_t* outIds);

    /**
     * Sets each row's entry to what it hands: row `i` names entry `ids[i]`, or [kNewWord] for a
     * word to add, which takes the next id, a word the table holds already refusing the row; its
     * fields replace the entry's, and its spelling, when given, replaces the one served, a
     * spelling of another word refusing the row. Writes each row's id to `outIds[i]`, [kNewWord]
     * for a row refused, and returns how many rows are held. `spellings` and `lengths` may be
     * null for none.
     */
    int put(const int32_t* ids, const char* const* spellings, const size_t* lengths,
            const int32_t* fields, int count, int32_t* outIds);

    /** The entry index for `word`'s spelling, case aside, or -1. */
    int32_t entryIndexFor(const char* word, size_t length) const;

    size_t size() const { return entries_.size(); }

    const char* entryText(uint32_t entryIndex, uint32_t* lengthOut) const;

    uint32_t entryCount(uint32_t entryIndex) const;

    /** How many times this entry was committed with a deliberate capital. */
    uint32_t deliberateCapitals(uint32_t entryIndex) const;

    /** How many times this entry was chosen on purpose. */
    uint32_t asserted(uint32_t entryIndex) const;

    /** How many times this entry was added by hand. */
    uint32_t added(uint32_t entryIndex) const;

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
        uint32_t added = 0;
    };

    int32_t childOf(int32_t node, uint32_t folded) const;
    int32_t childOfOrCreate(int32_t node, uint32_t folded);
    int32_t findNode(const uint32_t* folded, int count) const;
    /** The entry at `node` whose key is the `length` bytes at `key`, or -1. */
    int32_t entryWithKey(int32_t node, const char* key, size_t length) const;
    /**
     * The entry for `word`, created with no counts when missing; -1 for a word that cannot be
     * held. `created` gets whether it was created.
     */
    int32_t entryFor(const char* word, size_t length, bool* created);
    /** Sets the entry at `index` to the [kFields] ints at `fields`, each held at zero or more. */
    void setFields(int32_t index, const int32_t* fields);
    void collect(int32_t node, Completion* out, int maxOut, int* written) const;

    /** The longest word held, in bytes and in code points. */
    static constexpr uint32_t kMaxWordBytes = 256u;
    static constexpr int kMaxWordCodePoints = 64;

    /** The most bytes a held word takes once lowered: every code point at its widest. */
    static constexpr int kMaxKeyBytes = kMaxWordCodePoints * kMaxUtf8Bytes;

    /**
     * [word] in lower case, its marks kept, into [out], which holds kMaxKeyBytes; its length in
     * bytes, or zero for no word or one too long.
     */
    static int wordKey(const char* word, size_t length, char* out);

    std::vector<Node> nodes_;
    std::vector<Entry> entries_;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_WORD_TABLE_HPP
