// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_CANDIDATE_HPP
#define BORDERKEYS_CANDIDATE_HPP

#include <cstdint>

namespace borderkeys {

// A scored suggestion.
//
// Plain data, in its own header, because it crosses between the prediction engine, the gesture
// decoder and the JNI bridge -- and none of those may allocate or destruct anything on the path
// where it travels. Separating it is also what keeps engine.hpp and the decoder headers from
// having to include each other.
struct Candidate {
    // Index into the engine's pack table, or kUserPack for a word from the personal dictionary.
    int32_t packIndex;
    // Word index inside that pack, or entry index inside the user model.
    int32_t wordIndex;
    float score;

    static constexpr int32_t kUserPack = -1;

    /**
     * Two words offered as one suggestion, composed by the engine.
     *
     * Neither pack nor personal dictionary holds "vreau să" as an entry, so a phrase cannot be
     * named by a word index. `wordIndex` is instead a slot in the engine's phrase buffer, which
     * lives as long as one request -- long enough for the caller to read the text out, which is
     * all a candidate's text is ever used for.
     */
    static constexpr int32_t kPhrasePack = -2;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_CANDIDATE_HPP
