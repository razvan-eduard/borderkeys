// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.res.AssetManager

/**
 * The dictionaries that ship inside the application.
 *
 * They are in the APK, not on a server: this application has no `INTERNET` permission and a
 * build gate that fails the build if one ever appears, so "download a language" cannot mean what
 * it means elsewhere. Choosing a language here copies a file out of the APK into private
 * storage, which is the same install path a file the user picked goes through -- the pack is
 * validated by the same native code, recorded in the same table, and can be weighted, disabled
 * and removed the same way.
 *
 * They are also deliberately small. A starter dictionary written by this project is a licence
 * question with an answer; a large corpus is one without, which is the whole reason nothing was
 * bundled before. Replacing one with a proper compiled corpus is an import away, and the entry
 * disappears from the list once its language is installed.
 */
object BundledDictionaries {

    /**
     * One dictionary in the APK.
     *
     * [wordCount] is recorded here rather than read from the pack: the list is shown before
     * anything is opened, and opening a pack to fill in a label would checksum a megabyte to
     * draw a line of text. The build compiles these from the `.tsv` files in `dictionaries`, so a number that
     * drifted would be a stale constant rather than a wrong pack.
     */
    data class Entry(
        val tag: String,
        val displayName: String,
        val assetPath: String,
        val fileName: String,
        val wordCount: Int,
    )

    val ALL: List<Entry> = listOf(
        Entry("ro-RO", "Romanian", "dict/ro_RO.bkd", "ro_RO.bkd", 1100),
        Entry("en-US", "English", "dict/en_US.bkd", "en_US.bkd", 540),
    )

    /** Opens one for reading. The caller closes it; the install path copies and validates. */
    fun open(assets: AssetManager, entry: Entry) = assets.open(entry.assetPath)
}
