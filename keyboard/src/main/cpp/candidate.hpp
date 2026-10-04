// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_CANDIDATE_HPP
#define BORDERKEYS_CANDIDATE_HPP

#include <cstdint>

namespace borderkeys {

// A scored suggestion, plain data shared by the engine, the gesture decoders and the JNI bridge.
struct Candidate {
    // Index into the engine's pack table, or kUserPack for a word from the personal dictionary.
    int32_t packIndex;
    // Word index inside that pack, or entry index inside the user model.
    int32_t wordIndex;
    float score;
    // The walk's edit cost in key widths, its edit count, the characters past the last one typed,
    // and how many of the edits were a neighbouring key in place of the typed one.
    float editCost = 0.0f;
    uint8_t edits = 0;
    uint8_t runOn = 0;
    uint8_t slips = 0;

    /** Whether every edit was a neighbouring key in place of the typed one, with nothing run on. */
    bool slipsOnly() const { return edits > 0 && slips == edits && runOn == 0; }

    static constexpr int32_t kUserPack = -1;

    /**
     * Two words offered as one suggestion, composed by the engine; `wordIndex` is then a slot in
     * the engine's phrase buffer, valid for one request.
     */
    static constexpr int32_t kPhrasePack = -2;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_CANDIDATE_HPP
