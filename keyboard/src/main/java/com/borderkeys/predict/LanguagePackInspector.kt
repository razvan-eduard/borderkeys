// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.i18n.Keys

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Reads what a `.bkd` says about itself, using the same validator the engine uses.
 *
 * The one public way into the native format code. Settings needs to name a pack the user has
 * just chosen -- its language, how many words it holds -- and needs to refuse a file that is not
 * a pack. Doing that in Kotlin would mean a second implementation of the header layout, and two
 * implementations of a binary format agree right up until the day they do not. So the header
 * parser stays in C++, where the engine's copy already is, and this asks it.
 *
 * Checksums the whole file, so it belongs on a background thread.
 */
object LanguagePackInspector {

    /** What a pack that passed validation says about itself. */
    data class PackInfo(
        /** BCP-47, as written into the header by `tools/build_dict.py`. */
        val tag: String,
        val formatVersion: Int,
        val wordCount: Int,
    )

    sealed interface Result {
        data class Valid(val info: PackInfo) : Result

        /**
         * The pack was refused. [status] is the negative `BkdStatus` from `bkd_format.hpp`.
         *
         * [reasonKey] is a catalogue key rather than the sentence itself: this runs in the
         * keyboard, which has no reason to hold a language manager, while what reads the result
         * is the settings screen, which already has one. [reasonArgument] fills the one `%s` in
         * the two reasons that carry a detail.
         */
        data class Refused(
            val status: Int,
            val reasonKey: String,
            val reasonArgument: String = "",
        ) : Result
    }

    fun inspect(file: File): Result {
        val out = IntArray(3)
        val tag = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                NativePredictor.nativeInspectPack(
                    descriptor.fd,
                    0L,
                    file.length(),
                    out,
                )
            }
        } catch (error: java.io.IOException) {
            return Result.Refused(
                STATUS_UNREADABLE,
                Keys.PACK_THE_FILE_COULD_NOT_BE_OPENED_2,
                error.message.orEmpty(),
            )
        }

        return if (tag != null) {
            Result.Valid(PackInfo(tag = tag, formatVersion = out[1], wordCount = out[2]))
        } else {
            Result.Refused(out[0], reasonFor(out[0]), out[0].toString())
        }
    }

    /**
     * The `BkdStatus` codes, as catalogue keys.
     *
     * Mirrors the enum in `bkd_format.hpp`. Kept as a `when` over literals rather than as
     * constants shared with the native side, because the numbers are part of a published format
     * and a name here that drifted from the header would be worse than a number: the unknown
     * branch says the number, which is always true.
     */
    private fun reasonFor(status: Int): String = when (status) {
        -1 -> Keys.PACK_THE_FILE_IS_LARGER_THAN_A
        -2 -> Keys.PACK_THE_FILE_IS_TOO_SMALL_TO
        -3 -> Keys.PACK_THIS_IS_NOT_A_BORDERKEYS_LANGUAGE
        -4 -> Keys.PACK_THE_PACK_WAS_BUILT_FOR_A
        -5 -> Keys.PACK_THE_HEADER_IS_THE_WRONG_SIZE
        -6 -> Keys.PACK_THE_FILE_SIZE_DOES_NOT_MATCH
        -7 -> Keys.PACK_THE_HEADER_CHECKSUM_DOES_NOT_MATCH
        -8 -> Keys.PACK_THE_CONTENT_CHECKSUM_DOES_NOT_MATCH
        -9 -> Keys.PACK_A_SECTION_POINTS_OUTSIDE_THE_FILE
        -10 -> Keys.PACK_A_SECTION_IS_MISALIGNED
        -11 -> Keys.PACK_A_SECTION_IS_THE_WRONG_SIZE
        -12 -> Keys.PACK_THE_COUNTS_IN_THE_HEADER_CONTRADICT
        -13 -> Keys.PACK_A_HASH_CAPACITY_IS_NOT_A
        STATUS_UNREADABLE -> Keys.PACK_THE_FILE_COULD_NOT_BE_OPENED
        else -> Keys.PACK_THE_PACK_WAS_REFUSED_STATUS
    }

    private const val STATUS_UNREADABLE = -1000
}
