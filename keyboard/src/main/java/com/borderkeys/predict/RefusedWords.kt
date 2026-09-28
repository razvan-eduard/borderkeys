// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import java.util.Locale

/**
 * Words never offered or learned: the user's blocked words, matched by exact spelling in any
 * case, and the offensive-word list, matched through [WordFold].
 */
class RefusedWords private constructor(
    private val blocked: Set<String>,
    private val offensive: Set<String>,
) {

    val isEmpty: Boolean get() = blocked.isEmpty() && offensive.isEmpty()

    fun refuses(word: String): Boolean =
        (blocked.isNotEmpty() && word.lowercase(Locale.ROOT) in blocked) ||
            (offensive.isNotEmpty() && WordFold.fold(word) in offensive)

    companion object {

        val NONE = RefusedWords(emptySet(), emptySet())

        /** [blocked] as the user stored them; [offensive] already folded by [WordFold]. */
        fun of(blocked: Collection<String>, offensive: Set<String>): RefusedWords =
            RefusedWords(blocked.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }, offensive)
    }
}
