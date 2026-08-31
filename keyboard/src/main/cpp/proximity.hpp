// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PROXIMITY_HPP
#define BORDERKEYS_PROXIMITY_HPP

#include <cstddef>
#include <cstdint>

// Text normalisation and finger-geometry costs: everything the matcher needs to decide that
// "masina" was meant to be "masina" with the diacritics on, or that "s" was a slip for "a"
// because those two keys are eleven pixels apart on this particular layout.
//
// UTF-8 decoding lives here rather than in its own file because its only caller is the
// normaliser: nothing else in the engine ever sees bytes, only folded code points.

namespace borderkeys {

// Decodes one code point. Returns the position after it, or null when the sequence is malformed
// or truncated. Rejects overlong forms, surrogates and anything above U+10FFFF -- a decoder
// that accepts overlong encodings lets two different byte strings fold to the same word, which
// is a way to smuggle a blocked word past a filter that compares bytes.
const char* utf8Decode(const char* p, const char* end, uint32_t* codePoint);

// Writes up to four bytes. Returns the count, or 0 if the code point is not encodable.
int utf8Encode(uint32_t codePoint, char* out);

// Lowercases and strips the diacritic, so that the trie can be indexed once and reached from
// either spelling. The pack stores the display form separately, so "masina" finds the entry
// whose text is "mașina" and the suggestion strip shows the correct one.
//
// Covers ASCII, Latin-1 Supplement and the parts of Latin Extended-A that matter for Romanian
// (ă â î ș ț, in both the comma-below and the historical cedilla encodings, which look
// identical to a user and are different code points). Anything else is returned unchanged:
// folding a script we do not understand would merge words that are genuinely distinct.
uint32_t foldCodePoint(uint32_t codePoint);

// Folds a UTF-8 string into code points. Returns the number written, or -1 if the input is
// malformed or longer than `maxOut`. Writes nothing on failure.
int foldUtf8(const char* text, size_t length, uint32_t* out, int maxOut);

// The physical layout of the keys currently on screen, pushed down from Kotlin whenever the
// view is measured. The engine corrects finger slips without knowing anything about how the
// keyboard is drawn; this class is the entire extent of what it knows about pixels.
class KeyGeometry {
public:
    static constexpr int kMaxKeys = 64;
    // Eight is the number of keys touching a key on a staggered QWERTY grid. A candidate
    // further away than its immediate ring is not a slip, it is a different word.
    static constexpr int kMaxNeighbours = 8;

    void clear();
    bool isSet() const { return count_ > 0; }

    // `codes` are the folded code points of the key labels. Extra keys beyond kMaxKeys and
    // duplicate codes are dropped rather than rejected: a layout with a modifier row is normal,
    // and the engine only cares about the letters.
    bool set(const int32_t* codes, const float* centersX, const float* centersY, int count,
             float keyWidth, float keyHeight);

    // Cost, in key widths, of the finger having landed on `typed` when `intended` was meant.
    // Zero for the same key. Unknown keys get kUnknownKeyCost, which is high enough that a
    // candidate differing by a character not on the keyboard is only reached when nothing
    // better exists.
    float substitutionCost(uint32_t typedFolded, uint32_t intendedFolded) const;

    // The ring around a typed key, cheapest first and including the key itself at cost 0.
    // Returns the count. The search expands only these instead of the whole alphabet, which is
    // what keeps a fuzzy walk from costing forty array probes per node per input position.
    int neighbours(uint32_t typedFolded, const uint32_t** codesOut, const float** costsOut) const;

    static constexpr float kUnknownKeyCost = 1.6f;
    // Beyond this many key widths apart, two keys are not confusable and the pair is left out
    // of the neighbour ring entirely.
    static constexpr float kNeighbourRadius = 1.45f;

private:
    int indexOf(uint32_t folded) const;
    void buildNeighbours();

    int count_ = 0;
    uint32_t codes_[kMaxKeys] = {};
    float centersX_[kMaxKeys] = {};
    float centersY_[kMaxKeys] = {};
    float keyWidth_ = 0.0f;
    float keyHeight_ = 0.0f;

    int neighbourCount_[kMaxKeys] = {};
    uint32_t neighbourCode_[kMaxKeys][kMaxNeighbours] = {};
    float neighbourCost_[kMaxKeys][kMaxNeighbours] = {};

    // ASCII letters are every key on both shipped layouts, so the common lookup is a single
    // array read rather than a scan of up to 64 entries per character per trie node.
    int8_t asciiIndex_[128] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PROXIMITY_HPP
