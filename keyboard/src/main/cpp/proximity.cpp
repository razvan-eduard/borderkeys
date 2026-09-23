// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "proximity.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace borderkeys {

const char* utf8Decode(const char* p, const char* end, uint32_t* codePoint) {
    if (p >= end) {
        return nullptr;
    }
    const uint8_t first = static_cast<uint8_t>(*p);
    if (first < 0x80u) {
        *codePoint = first;
        return p + 1;
    }

    int extra;
    uint32_t value;
    uint32_t minimum;
    if ((first & 0xE0u) == 0xC0u) {
        extra = 1;
        value = first & 0x1Fu;
        minimum = 0x80u;
    } else if ((first & 0xF0u) == 0xE0u) {
        extra = 2;
        value = first & 0x0Fu;
        minimum = 0x800u;
    } else if ((first & 0xF8u) == 0xF0u) {
        extra = 3;
        value = first & 0x07u;
        minimum = 0x10000u;
    } else {
        // A continuation byte or an F8..FF lead: not a valid start of anything.
        return nullptr;
    }

    if (end - p < extra + 1) {
        return nullptr;
    }
    for (int i = 1; i <= extra; ++i) {
        const uint8_t byte = static_cast<uint8_t>(p[i]);
        if ((byte & 0xC0u) != 0x80u) {
            return nullptr;
        }
        value = (value << 6) | (byte & 0x3Fu);
    }
    // Overlong forms encode a small code point in more bytes than needed. Two spellings of the
    // same character mean two trie paths for one word, so they are refused, not normalised.
    if (value < minimum) {
        return nullptr;
    }
    if (value > 0x10FFFFu || (value >= 0xD800u && value <= 0xDFFFu)) {
        return nullptr;
    }
    *codePoint = value;
    return p + extra + 1;
}

int utf8Encode(uint32_t codePoint, char* out) {
    if (codePoint < 0x80u) {
        out[0] = static_cast<char>(codePoint);
        return 1;
    }
    if (codePoint < 0x800u) {
        out[0] = static_cast<char>(0xC0u | (codePoint >> 6));
        out[1] = static_cast<char>(0x80u | (codePoint & 0x3Fu));
        return 2;
    }
    if (codePoint < 0x10000u) {
        if (codePoint >= 0xD800u && codePoint <= 0xDFFFu) {
            return 0;
        }
        out[0] = static_cast<char>(0xE0u | (codePoint >> 12));
        out[1] = static_cast<char>(0x80u | ((codePoint >> 6) & 0x3Fu));
        out[2] = static_cast<char>(0x80u | (codePoint & 0x3Fu));
        return 3;
    }
    if (codePoint > 0x10FFFFu) {
        return 0;
    }
    out[0] = static_cast<char>(0xF0u | (codePoint >> 18));
    out[1] = static_cast<char>(0x80u | ((codePoint >> 12) & 0x3Fu));
    out[2] = static_cast<char>(0x80u | ((codePoint >> 6) & 0x3Fu));
    out[3] = static_cast<char>(0x80u | (codePoint & 0x3Fu));
    return 4;
}

// Whether a Latin Extended-A code point is the capital of the pair it belongs to -- see the
// comment inside foldCodePoint on the block's two parities. Mirrors _latin_extended_a_upper()
// in tools/build_dict.py, which the native tests diff this against.
static bool latinExtendedAUpper(uint32_t codePoint) {
    const bool evenCapital = codePoint <= 0x137u || (codePoint >= 0x14Au && codePoint <= 0x177u);
    const bool oddCapital = (codePoint >= 0x139u && codePoint <= 0x148u) ||
                            (codePoint >= 0x179u && codePoint <= 0x17Eu);
    return (evenCapital && (codePoint & 1u) == 0u) || (oddCapital && (codePoint & 1u) == 1u);
}

// Case, and nothing else: the same lowering foldCodePoint does before its diacritic table,
// stopping there. Comparing two spellings of one folded key is a question about the diacritics,
// so it cannot use a fold that removes them -- and it cannot use raw bytes either, because the
// first letter of a field arrives capitalised.
uint32_t lowerCodePoint(uint32_t codePoint) {
    if (codePoint < 128u) {
        return (codePoint >= 'A' && codePoint <= 'Z') ? codePoint + 32u : codePoint;
    }
    if (codePoint >= 0xC0u && codePoint <= 0xDEu && codePoint != 0xD7u) {
        return codePoint + 0x20u;
    }
    if (codePoint >= 0x100u && codePoint <= 0x17Fu && latinExtendedAUpper(codePoint)) {
        return codePoint + 1u;
    }
    return codePoint;
}

/** Whether two UTF-8 spellings are the same but for case. Diacritics still have to match. */
bool sameSpellingIgnoringCase(const char* a, size_t aLength, const char* b, size_t bLength) {
    const char* p = a;
    const char* q = b;
    const char* const aEnd = a + aLength;
    const char* const bEnd = b + bLength;
    while (p < aEnd && q < bEnd) {
        uint32_t left = 0;
        uint32_t right = 0;
        const char* const nextLeft = utf8Decode(p, aEnd, &left);
        const char* const nextRight = utf8Decode(q, bEnd, &right);
        if (nextLeft == nullptr || nextRight == nullptr) {
            return false;
        }
        if (lowerCodePoint(left) != lowerCodePoint(right)) {
            return false;
        }
        p = nextLeft;
        q = nextRight;
    }
    return p == aEnd && q == bEnd;
}

uint32_t foldCodePoint(uint32_t codePoint) {
    if (codePoint < 128u) {
        if (codePoint >= 'A' && codePoint <= 'Z') {
            return codePoint + 32u;
        }
        return codePoint;
    }

    // Latin-1 Supplement: uppercase C0..DE (excluding D7, the multiplication sign) maps to the
    // lowercase E0..FE range, so folding case first halves the table below.
    if (codePoint >= 0xC0u && codePoint <= 0xDEu && codePoint != 0xD7u) {
        codePoint += 0x20u;
    }
    switch (codePoint) {
        case 0xE0u: case 0xE1u: case 0xE2u: case 0xE3u: case 0xE4u: case 0xE5u:
            return 'a';
        case 0xE7u:
            return 'c';
        case 0xE8u: case 0xE9u: case 0xEAu: case 0xEBu:
            return 'e';
        case 0xECu: case 0xEDu: case 0xEEu: case 0xEFu:
            return 'i';
        case 0xF1u:
            return 'n';
        case 0xF2u: case 0xF3u: case 0xF4u: case 0xF5u: case 0xF6u: case 0xF8u:
            return 'o';
        case 0xF9u: case 0xFAu: case 0xFBu: case 0xFCu:
            return 'u';
        case 0xFDu: case 0xFFu:
            return 'y';
        default:
            break;
    }

    // Latin Extended-A. The block pairs each capital with its lowercase, but not on one parity
    // throughout: in 0x100..0x137 and 0x14A..0x177 the capital is the even code point, in
    // 0x139..0x148 and 0x179..0x17E it is the odd one, 0x138 (kra) and 0x149 ('n) have no
    // capital at all, and 0x178 is the capital of Latin-1's 0xFF. One bitwise test across the
    // whole block folded "ł" to "Ń" and left "Ź" unfolded, so the case fold is per range.
    if (codePoint >= 0x100u && codePoint <= 0x17Fu) {
        uint32_t lower = codePoint;
        if (latinExtendedAUpper(codePoint)) {
            lower += 1u;
        }
        switch (lower) {
            // Romanian: a-breve, a-circumflex (shared with the Latin-1 block above),
            // i-circumflex, s-comma / s-cedilla, t-comma / t-cedilla. Both encodings of the
            // comma-below letters are folded: they render identically and users type whichever
            // their previous keyboard produced.
            case 0x101u: case 0x103u: case 0x105u:
                return 'a';
            case 0x107u: case 0x109u: case 0x10Bu: case 0x10Du:
                return 'c';
            case 0x10Fu: case 0x111u:
                return 'd';
            case 0x113u: case 0x115u: case 0x117u: case 0x119u: case 0x11Bu:
                return 'e';
            case 0x11Du: case 0x11Fu: case 0x121u: case 0x123u:
                return 'g';
            case 0x125u: case 0x127u:
                return 'h';
            case 0x129u: case 0x12Bu: case 0x12Du: case 0x12Fu: case 0x131u:
                return 'i';
            case 0x135u:
                return 'j';
            case 0x137u: case 0x138u:
                return 'k';
            case 0x13Au: case 0x13Cu: case 0x13Eu: case 0x140u: case 0x142u:
                return 'l';
            case 0x144u: case 0x146u: case 0x148u: case 0x149u: case 0x14Bu:
                return 'n';
            case 0x14Du: case 0x14Fu: case 0x151u:
                return 'o';
            case 0x155u: case 0x157u: case 0x159u:
                return 'r';
            case 0x15Bu: case 0x15Du: case 0x15Fu: case 0x161u:
                return 's';
            case 0x163u: case 0x165u: case 0x167u:
                return 't';
            case 0x169u: case 0x16Bu: case 0x16Du: case 0x16Fu: case 0x171u: case 0x173u:
                return 'u';
            case 0x175u:
                return 'w';
            case 0x177u: case 0x178u:
                return 'y';
            case 0x17Au: case 0x17Cu: case 0x17Eu:
                return 'z';
            default:
                return lower;
        }
    }

    // Latin Extended Additional: the comma-below forms Romanian actually standardised on.
    switch (codePoint) {
        case 0x218u: case 0x219u:
            return 's';
        case 0x21Au: case 0x21Bu:
            return 't';
        default:
            break;
    }

    return codePoint;
}

int foldUtf8(const char* text, size_t length, uint32_t* out, int maxOut) {
    const char* p = text;
    const char* const end = text + length;
    int written = 0;
    while (p < end) {
        if (written >= maxOut) {
            return -1;
        }
        uint32_t codePoint = 0;
        const char* const next = utf8Decode(p, end, &codePoint);
        if (next == nullptr) {
            return -1;
        }
        out[written] = foldCodePoint(codePoint);
        ++written;
        p = next;
    }
    return written;
}

void KeyGeometry::clear() {
    count_ = 0;
    keyWidth_ = 0.0f;
    keyHeight_ = 0.0f;
    std::memset(asciiIndex_, -1, sizeof(asciiIndex_));
    std::memset(neighbourCount_, 0, sizeof(neighbourCount_));
}

int KeyGeometry::indexOf(uint32_t folded) const {
    if (folded < 128u) {
        return asciiIndex_[folded];
    }
    for (int i = 0; i < count_; ++i) {
        if (codes_[i] == folded) {
            return i;
        }
    }
    return -1;
}

bool KeyGeometry::set(const int32_t* codes, const float* centersX, const float* centersY,
                      int count, float keyWidth, float keyHeight) {
    clear();
    if (codes == nullptr || centersX == nullptr || centersY == nullptr || count <= 0) {
        return false;
    }
    // A zero or negative key size would make every normalised distance infinite or negative.
    if (!(keyWidth > 0.0f) || !(keyHeight > 0.0f)) {
        return false;
    }
    keyWidth_ = keyWidth;
    keyHeight_ = keyHeight;

    for (int i = 0; i < count && count_ < kMaxKeys; ++i) {
        if (codes[i] <= 0) {
            continue;  // modifiers, shift, delete: not characters, not confusable with one
        }
        const uint32_t folded = foldCodePoint(static_cast<uint32_t>(codes[i]));
        if (indexOf(folded) >= 0) {
            continue;  // a character that appears twice keeps its first, primary position
        }
        codes_[count_] = folded;
        centersX_[count_] = centersX[i];
        centersY_[count_] = centersY[i];
        if (folded < 128u) {
            asciiIndex_[folded] = static_cast<int8_t>(count_);
        }
        ++count_;
    }
    if (count_ == 0) {
        return false;
    }
    buildNeighbours();
    return true;
}

void KeyGeometry::buildNeighbours() {
    for (int i = 0; i < count_; ++i) {
        // Slot zero is always the key itself at zero cost, so the search can iterate one list
        // per input character and get the exact match for free.
        neighbourCode_[i][0] = codes_[i];
        neighbourCost_[i][0] = 0.0f;
        int filled = 1;

        for (int j = 0; j < count_; ++j) {
            if (j == i) {
                continue;
            }
            const float dx = (centersX_[i] - centersX_[j]) / keyWidth_;
            const float dy = (centersY_[i] - centersY_[j]) / keyHeight_;
            const float cost = std::sqrt(dx * dx + dy * dy);
            if (cost > kNeighbourRadius) {
                continue;
            }
            // Insertion sort into a list of at most eight: cheaper than sorting sixty-odd
            // candidates and discarding all but eight of them.
            int position = filled;
            if (filled == kMaxNeighbours) {
                if (cost >= neighbourCost_[i][kMaxNeighbours - 1]) {
                    continue;
                }
                position = kMaxNeighbours - 1;
            } else {
                ++filled;
            }
            while (position > 1 && neighbourCost_[i][position - 1] > cost) {
                neighbourCode_[i][position] = neighbourCode_[i][position - 1];
                neighbourCost_[i][position] = neighbourCost_[i][position - 1];
                --position;
            }
            neighbourCode_[i][position] = codes_[j];
            neighbourCost_[i][position] = cost;
        }
        neighbourCount_[i] = filled;
    }
}

float KeyGeometry::substitutionCost(uint32_t typedFolded, uint32_t intendedFolded) const {
    if (typedFolded == intendedFolded) {
        return 0.0f;
    }
    const int a = indexOf(typedFolded);
    const int b = indexOf(intendedFolded);
    if (a < 0 || b < 0) {
        return kUnknownKeyCost;
    }
    const float dx = (centersX_[a] - centersX_[b]) / keyWidth_;
    const float dy = (centersY_[a] - centersY_[b]) / keyHeight_;
    return std::max(std::sqrt(dx * dx + dy * dy), kMinSubstitutionCost);
}

bool KeyGeometry::centreOf(uint32_t folded, float* x, float* y) const {
    const int index = indexOf(folded);
    if (index < 0) {
        return false;
    }
    *x = centersX_[index];
    *y = centersY_[index];
    return true;
}

int KeyGeometry::nearestSlot(float x, float y) const {
    int best = -1;
    float bestDistance = 3.0e38f;
    for (int i = 0; i < count_; ++i) {
        const float dx = x - centersX_[i];
        const float dy = y - centersY_[i];
        const float distance = dx * dx + dy * dy;  // squared: the ordering is the same
        if (distance < bestDistance) {
            bestDistance = distance;
            best = i;
        }
    }
    return best;
}

int KeyGeometry::neighbours(uint32_t typedFolded, const uint32_t** codesOut,
                            const float** costsOut) const {
    const int index = indexOf(typedFolded);
    if (index < 0) {
        return 0;
    }
    *codesOut = neighbourCode_[index];
    *costsOut = neighbourCost_[index];
    return neighbourCount_[index];
}

}  // namespace borderkeys
