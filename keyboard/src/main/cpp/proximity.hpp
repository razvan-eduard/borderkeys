// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PROXIMITY_HPP
#define BORDERKEYS_PROXIMITY_HPP

#include <cstddef>
#include <cstdint>

// Text folding and key-geometry costs, with the UTF-8 decoding folding needs.

namespace borderkeys {

// Decodes one code point. Returns the position after it, or null for a malformed, truncated or
// overlong sequence, a surrogate, or anything above U+10FFFF.
const char* utf8Decode(const char* p, const char* end, uint32_t* codePoint);

// Writes up to four bytes. Returns the count, or 0 if the code point is not encodable.
int utf8Encode(uint32_t codePoint, char* out);

// Lowercases and strips the diacritic, for ASCII, Latin-1 Supplement, Latin Extended-A, the
// Romanian comma-below letters, Greek, Cyrillic, Armenian, Georgian, Hebrew and Arabic; any other
// code point is returned unchanged. A mark a folded word does not carry, such as a Hebrew vowel
// point, folds to kDroppedCodePoint, which foldUtf8 leaves out.
constexpr uint32_t kDroppedCodePoint = 0u;
uint32_t foldCodePoint(uint32_t codePoint);

/** Lowercases without touching diacritics -- foldCodePoint's case half on its own. */
uint32_t lowerCodePoint(uint32_t codePoint);

/** Whether two UTF-8 spellings differ only by case. */
bool sameSpellingIgnoringCase(const char* a, size_t aLength, const char* b, size_t bLength);

// Folds a UTF-8 string into code points. Returns the number written, or -1 if the input is
// malformed or longer than `maxOut`. Writes nothing on failure.
int foldUtf8(const char* text, size_t length, uint32_t* out, int maxOut);

// foldUtf8, also writing into `sourceOut` the index of the input code point each folded one
// came from; a code point that folds to nothing leaves no entry.
int foldUtf8(const char* text, size_t length, uint32_t* out, int maxOut, int* sourceOut);

// The keys on screen and their centres, pushed from Kotlin whenever the view is measured.
class KeyGeometry {
public:
    static constexpr int kMaxKeys = 64;
    // The most long-press letters one key lends a swipe.
    static constexpr int kMaxAliases = 4;
    // The most keys in one key's neighbour ring.
    static constexpr int kMaxNeighbours = 8;

    void clear();
    bool isSet() const { return count_ > 0; }

    // `codes` are the folded code points of the key labels; keys past kMaxKeys and repeated codes
    // are dropped.
    bool set(const int32_t* codes, const float* centersX, const float* centersY, int count,
             float keyWidth, float keyHeight);

    // Cost, in key widths, of the finger landing on `typed` when `intended` was meant: zero for
    // the same key, kUnknownKeyCost for a key not on the layout.
    float substitutionCost(uint32_t typedFolded, uint32_t intendedFolded) const;

    /** The centre of a key in the view's pixels; false when the character is not on the layout. */
    bool centreOf(uint32_t folded, float* x, float* y) const;

    // The letters a key's long press reaches, `codes[i]` on the key of `baseCodes[i]`, folded
    // here. One that is a key itself, one whose base is not a key, and past kMaxAliases on one
    // key are dropped. [set] clears them.
    void setAliases(const int32_t* codes, const int32_t* baseCodes, int count);

    int aliasCount(int slot) const {
        return (slot >= 0 && slot < count_) ? aliasCount_[slot] : 0;
    }
    uint32_t aliasAt(int slot, int index) const {
        return (index >= 0 && index < aliasCount(slot)) ? aliases_[slot][index] : 0u;
    }

    /** [centreOf], or for a long-press letter the centre of the key that holds it. */
    bool centreOfLetter(uint32_t folded, float* x, float* y) const;

    int keyCount() const { return count_; }
    float keyWidth() const { return keyWidth_; }
    float keyHeight() const { return keyHeight_; }
    uint32_t codeAt(int slot) const {
        return (slot >= 0 && slot < count_) ? codes_[slot] : 0u;
    }

    /** The key nearest to a point by centre, as a slot index; -1 when no geometry is set. */
    int nearestSlot(float x, float y) const;

    // The ring around a typed key, cheapest first, the key itself first at cost 0. Returns the
    // count.
    int neighbours(uint32_t typedFolded, const uint32_t** codesOut, const float** costsOut) const;

    static constexpr float kUnknownKeyCost = 1.6f;
    // Keys further apart than this, in key widths, are not in each other's ring.
    static constexpr float kNeighbourRadius = 1.45f;
    // The least substitutionCost() charges for two different keys; engine.cpp's static_assert
    // relies on it.
    static constexpr float kMinSubstitutionCost = 0.2f;

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

    // The slot of each ASCII character, for a single-read lookup.
    int8_t asciiIndex_[128] = {};

    int aliasCount_[kMaxKeys] = {};
    uint32_t aliases_[kMaxKeys][kMaxAliases] = {};
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PROXIMITY_HPP
