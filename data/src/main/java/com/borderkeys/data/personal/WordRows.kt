// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

/**
 * Rows of the engine's word table as a load or a put hands them over: each word's id, or
 * [NEW_WORD] for one the engine does not hold yet, its spelling, and its [FIELDS] counts.
 */
class WordRows internal constructor(
    /** Each row's word, as [PersonalLookup] keys it. */
    internal val keys: List<String>,
    val ids: IntArray,
    val spellings: Array<String?>,
    /** [FIELDS] ints per row, at [COUNT], [DELIBERATE_CAPITALS], [ASSERTED] and [ADDED]. */
    val fields: IntArray,
) {
    val size: Int get() = keys.size

    companion object {
        /** The slots of a row's counts in [fields], and how many a row holds: the bridge's kWord constants. */
        const val COUNT = 0
        const val DELIBERATE_CAPITALS = 1
        const val ASSERTED = 2
        const val ADDED = 3
        const val FIELDS = 4

        /** The id of a word the engine does not hold yet: the bridge's kNewWordId. */
        const val NEW_WORD = -1
    }
}
