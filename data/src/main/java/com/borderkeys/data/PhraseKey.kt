// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.language.LanguageTags

/**
 * How the personal dictionary keys what it learns: a link by its language
 * ([LanguageTags.primary]), a word in lower case.
 */
object PhraseKey {

    /** [word] as the personal dictionary and the engine key it: in the engine's lower case, its marks kept. */
    fun word(word: String): String = NativeText.lowerCase(word)

    /**
     * The language of a link that names none, by its last word: the [LanguageTags.primary] of the
     * locale [localeOf] holds for that word, else of the locale most words hold, else [fallback]. A
     * locale that names no language counts as none.
     */
    fun languageOfWord(localeOf: Map<String, String>, fallback: String): (String) -> String {
        val known = localeOf.filterValues { LanguageTags.primary(it) != LanguageTags.UNKNOWN }
        val commonest = known.values.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { it.key }))?.key
        val otherwise = commonest?.let(LanguageTags::primary) ?: fallback
        return { word -> known[word]?.let(LanguageTags::primary) ?: otherwise }
    }
}
