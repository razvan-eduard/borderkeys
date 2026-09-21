// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_READING_HPP
#define BORDERKEYS_READING_HPP

#include <cstdint>

namespace borderkeys {

/** What a candidate is, relative to the letters typed. Classified by edit cost and depth. */
enum class Reading : uint8_t {
    /** The typed letters, same spelling. Reaches the strip only. */
    Exact,
    /** The typed letters, spelled with a mark the fold discards. Takes the respelling tier. */
    Respelling,
    /** The typed letters plus at most kMaxCorrectionCompletion characters. May be committed. */
    ShortCompletion,
    /** The typed letters plus more than that. Reaches the strip only. */
    LongCompletion,
    /** Reached by at least one edit. May be committed. */
    Correction,
};

/** Greatest depth at which a completion still counts as a correction rather than a new word. */
constexpr int kMaxCorrectionCompletion = 1;

/** Whether `text` contains a byte the fold maps to a plain ASCII letter -- a diacritic. Case is
 *  not such a mark: it folds without changing byte width. */
inline bool carriesFoldedMark(const char* text, uint32_t length) {
    for (uint32_t i = 0; i < length; ++i) {
        if (static_cast<unsigned char>(text[i]) >= 0x80u) {
            return true;
        }
    }
    return false;
}

/** Classifies a candidate. `text` is read only to separate Exact from Respelling, and may be
 *  null when `cost` or `depth` already decides. */
inline Reading readingOf(float cost, int depth, const char* text, uint32_t length) {
    if (cost > 0.0f) {
        return Reading::Correction;
    }
    if (depth <= 0) {
        return (text != nullptr && length != 0 && carriesFoldedMark(text, length))
                   ? Reading::Respelling
                   : Reading::Exact;
    }
    return (depth <= kMaxCorrectionCompletion) ? Reading::ShortCompletion
                                               : Reading::LongCompletion;
}

/** Whether this reading is offered to the corrections heap. */
inline bool reachesCorrectionHeap(Reading reading) {
    return reading == Reading::Correction || reading == Reading::ShortCompletion;
}

/** Whether this reading takes the respelling tier, which outranks the corrections heap. */
inline bool takesRespellingTier(Reading reading) {
    return reading == Reading::Respelling;
}

}  // namespace borderkeys

#endif  // BORDERKEYS_READING_HPP
