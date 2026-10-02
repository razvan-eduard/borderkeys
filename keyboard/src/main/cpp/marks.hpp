// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_MARKS_HPP
#define BORDERKEYS_MARKS_HPP

#include <cstdint>

namespace borderkeys {

// The non-letters composed into a word, the marks; the same set as
// BorderKeysService.isWordCharacter.
constexpr uint32_t kApostrophe = 0x27u;
constexpr uint32_t kHyphen = 0x2Du;
constexpr uint32_t kMarkCodePoints[] = {kApostrophe, kHyphen};

inline bool isMark(uint32_t folded) {
    return folded == kApostrophe || folded == kHyphen;
}

// What a swipe decoder charges, in nats, for the one mark a word may hold between two keys the
// finger crossed. The build may set it for a sweep.
#ifdef BORDERKEYS_SWIPE_MARK_COST
constexpr float kSwipeMarkCost = static_cast<float>(BORDERKEYS_SWIPE_MARK_COST);
#else
constexpr float kSwipeMarkCost = 4.0f;
#endif

}  // namespace borderkeys

#endif  // BORDERKEYS_MARKS_HPP
