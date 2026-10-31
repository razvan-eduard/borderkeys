// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

/**
 * The packs active in the engine, as [PersonalLookup] places and counts words by them: their
 * [languages], primary language subtags, and for each of a list of words the languages of the
 * packs that spell it ([spelling]).
 */
class ActivePacks(
    val languages: Set<String>,
    val spelling: (List<String>) -> List<Set<String>>,
) {

    /** Whether a word's uses in [language] count ([counts]). */
    fun counts(language: String): Boolean = counts(languages, language)

    companion object {
        /** No pack active: no word spelled, and every language counted. */
        val NONE = ActivePacks(emptySet()) { words -> words.map { emptySet() } }

        /** Whether a word's uses in [language] count with packs active in [languages]: one is in it, or none is. */
        fun counts(languages: Set<String>, language: String): Boolean = languages.isEmpty() || language in languages
    }
}
