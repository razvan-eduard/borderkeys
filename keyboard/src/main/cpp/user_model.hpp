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

    UserModel();

    void clear();

    // Replaces everything with the given words. Used once at service start, from the Room
    // table, so that native and database agree before the first keystroke.
    void bulkLoad(const char* const* words, const size_t* lengths, const int32_t* counts,
                  int count);

    // Records that the user chose this word. Adds it if it is new.
    void learn(const char* word, size_t length);

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

    std::vector<Node> nodes_;
    std::vector<Entry> entries_;
    uint32_t totalCount_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_USER_MODEL_HPP
