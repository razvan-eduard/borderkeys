// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

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
         * The pack was refused. [status] is the negative `BkdStatus` from `bkd_format.hpp`, and
         * [reason] is that code in words -- which is what a person reads when an import fails,
         * and the difference between "it did not work" and "the file is truncated".
         */
        data class Refused(val status: Int, val reason: String) : Result
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
            return Result.Refused(STATUS_UNREADABLE, "the file could not be opened: ${error.message}")
        }

        return if (tag != null) {
            Result.Valid(PackInfo(tag = tag, formatVersion = out[1], wordCount = out[2]))
        } else {
            Result.Refused(out[0], reasonFor(out[0]))
        }
    }

    /**
     * The `BkdStatus` codes, in words.
     *
     * Mirrors the enum in `bkd_format.hpp`. Kept as a `when` over literals rather than as
     * constants shared with the native side, because the numbers are part of a published format
     * and a name here that drifted from the header would be worse than a number: the unknown
     * branch says the number, which is always true.
     */
    private fun reasonFor(status: Int): String = when (status) {
        -1 -> "the file is larger than a language pack may be"
        -2 -> "the file is too small to contain a pack header"
        -3 -> "this is not a BorderKeys language pack"
        -4 -> "the pack was built for a different format version"
        -5 -> "the header is the wrong size"
        -6 -> "the file size does not match what the header declares, so it is truncated or padded"
        -7 -> "the header checksum does not match"
        -8 -> "the content checksum does not match, so the file is damaged"
        -9 -> "a section points outside the file"
        -10 -> "a section is misaligned"
        -11 -> "a section is the wrong size for what it holds"
        -12 -> "the counts in the header contradict each other"
        -13 -> "a hash capacity is not a power of two"
        STATUS_UNREADABLE -> "the file could not be opened"
        else -> "the pack was refused (status $status)"
    }

    private const val STATUS_UNREADABLE = -1000
}
