// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_JNI_SLOTS_HPP
#define BORDERKEYS_JNI_SLOTS_HPP

#include <cstdint>

// The bridge's limits, the classes it registers, and the array slots it fills, which
// NativePredictor's constants of the same names mirror.
namespace borderkeys::bridge {

/** The longest word the engine takes, in UTF-16 units. */
inline constexpr int32_t kMaxStringUnits = 64;

/** The most bytes modified UTF-8 writes for one UTF-16 unit. */
inline constexpr int32_t kModifiedUtf8BytesPerUnit = 3;

/** The longest word in modified UTF-8, with its terminator. */
inline constexpr int32_t kStringBufferBytes = kMaxStringUnits * kModifiedUtf8BytesPerUnit + 1;

/** The most personal words one load hands over: KeyboardPreferences.MAX_LEARNED_WORD_LIMIT. */
inline constexpr int32_t kMaxUserWordsPerCall = 50000;

/** The most gesture points taken; the same as GestureCapture's capacity. */
inline constexpr int32_t kMaxGesturePoints = 512;

/** The classes whose natives this library registers: the engine's, and the text rules `:data` reads. */
inline constexpr const char* kPredictorClass = "com/borderkeys/predict/NativePredictor";
inline constexpr const char* kTextClass = "com/borderkeys/data/NativeText";

/**
 * The slots of nativeAnswer's and nativeDecodeGesture's `outTexts`, NativePredictor's TEXT_
 * constants; nativeDecodeGesture fills kTextLanguage alone.
 */
inline constexpr int32_t kTextKnownSpelling = 0;
inline constexpr int32_t kTextPossessive = 1;
inline constexpr int32_t kTextDecoded = 2;
inline constexpr int32_t kTextLanguage = 3;
inline constexpr int32_t kTextSlots = 4;

/**
 * nativeAnswer's `outCorrections` holds autocorrect's list, `outCorrectionNames` whether each entry
 * is a name, `outCorrectionSlips` whether each was reached by neighbouring keys alone and
 * `outCorrectionConfident` whether each clears the tap decoder's weighing; `outSpellingFlags`
 * holds two flags about the typed word. NativePredictor's CORRECTION_SLOTS and SPELLING_ constants.
 */
inline constexpr int32_t kCorrectionSlots = 5;
inline constexpr int32_t kSpellingExact = 0;
inline constexpr int32_t kSpellingName = 1;
inline constexpr int32_t kSpellingFlagSlots = 2;

inline constexpr int32_t kLearnedPair = 1;
inline constexpr int32_t kLearnedTriple = 2;

/**
 * The slots of nativeContextLinks' `outContext`: the context's total and how many times it has been
 * seen. NativePredictor's CONTEXT_ constants.
 */
inline constexpr int32_t kContextTotal = 0;
inline constexpr int32_t kContextSeen = 1;
inline constexpr int32_t kContextSlots = 2;

/**
 * How many of one context's links nativeContextLinks reads on the stack, more than any context the
 * budget keeps; a context holding more is read through a buffer sized to it.
 */
inline constexpr int32_t kContextLinksOnStack = 32;

}  // namespace borderkeys::bridge

#endif  // BORDERKEYS_JNI_SLOTS_HPP
