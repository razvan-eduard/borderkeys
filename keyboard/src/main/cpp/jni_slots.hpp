// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_JNI_SLOTS_HPP
#define BORDERKEYS_JNI_SLOTS_HPP

#include <cstdint>
#include <limits>

// The bridge's limits, the classes it registers, and the array slots it fills, which
// NativePredictor's constants of the same names mirror.
namespace borderkeys::bridge {

/** The longest word the engine takes, in UTF-16 units. */
inline constexpr int32_t kMaxStringUnits = 64;

/** The most bytes modified UTF-8 writes for one UTF-16 unit. */
inline constexpr int32_t kModifiedUtf8BytesPerUnit = 3;

/** The longest word in modified UTF-8, with its terminator. */
inline constexpr int32_t kStringBufferBytes = kMaxStringUnits * kModifiedUtf8BytesPerUnit + 1;

/** The most words one nativeSetBlockedWords call takes: KeyboardPreferences.MAX_LEARNED_WORD_LIMIT. */
inline constexpr int32_t kMaxUserWordsPerCall = 50000;

/** The most gesture points taken; the same as GestureCapture's capacity. */
inline constexpr int32_t kMaxGesturePoints = 512;

/** The classes whose natives this library registers: the engine's, and the text rules `:data` reads. */
inline constexpr const char* kPredictorClass = "com/borderkeys/predict/NativePredictor";
inline constexpr const char* kTextClass = "com/borderkeys/data/NativeText";

/**
 * The slots of nativeAnswer's and nativeDecodeGesture's `outTexts`, NativePredictor's TEXT_
 * constants; nativeDecodeGesture fills kTextAnswerPack alone.
 */
inline constexpr int32_t kTextKnownSpelling = 0;
inline constexpr int32_t kTextPossessive = 1;
inline constexpr int32_t kTextDecoded = 2;
inline constexpr int32_t kTextAnswerPack = 3;
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

/**
 * The ints each row of nativeLoadWordTable's and nativePutWords' `fields` holds, in this order,
 * and the id a put names a word the engine does not hold yet by. WordRows' constants.
 */
inline constexpr int32_t kWordCount = 0;
inline constexpr int32_t kWordDeliberateCapitals = 1;
inline constexpr int32_t kWordAsserted = 2;
inline constexpr int32_t kWordAdded = 3;
inline constexpr int32_t kWordFields = 4;
inline constexpr int32_t kNewWordId = -1;

/**
 * The most rows one nativeLoadWordTable or nativePutWords call takes: as many as one int array
 * holds the kWordFields ints of, the most a WordRows' `fields` carries.
 */
inline constexpr int32_t kMaxWordRowsPerCall = 536870911;
static_assert(kMaxWordRowsPerCall == std::numeric_limits<int32_t>::max() / kWordFields,
              "one int array holds the fields of kMaxWordRowsPerCall rows and of no more");

/**
 * Whether `fields` ints are kWordFields per row of `rows` rows, at most kMaxWordRowsPerCall of
 * them: the row count is checked before the fields are counted, so no count passes an int.
 */
constexpr bool wordFieldsAgree(int32_t rows, int32_t fields) {
    return rows >= 0 && rows <= kMaxWordRowsPerCall && fields == rows * kWordFields;
}

/**
 * The slots of nativePushSlice's arrays, SliceRows' constants: the ints of the header,
 * of each link and of each hop, the longs before each link's stamp, the most hops a slice holds,
 * and the id naming no language or word. A request's `personalContext` holds the header's first
 * kPersonalContextSlots ints: the language and the two words before.
 */
inline constexpr int32_t kSliceHeaderFields = 8;
inline constexpr int32_t kSliceLinkFields = 3;
inline constexpr int32_t kSliceHopFields = 4;
inline constexpr int32_t kSliceContextSeenFields = 2;
inline constexpr int32_t kSliceMaxHops = 3;
inline constexpr int32_t kSliceNone = -1;
inline constexpr int32_t kPersonalContextSlots = 3;

}  // namespace borderkeys::bridge

#endif  // BORDERKEYS_JNI_SLOTS_HPP
