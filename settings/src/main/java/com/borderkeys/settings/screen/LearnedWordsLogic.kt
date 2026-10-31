// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.WordShape
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.ActivePacks
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.language.LanguageTags

/** Whether [candidate] can be added: a word by shape, and no listed word is that exact spelling. */
internal fun addable(candidate: String, listed: Set<String>): Boolean =
    WordShape.isWord(candidate) && candidate !in listed

/** The enabled language tags with [current] first, when it is one of them. */
internal fun languageChoices(enabled: List<String>, current: String?): List<String> =
    if (current != null && current in enabled) listOf(current) + (enabled - current) else enabled

/** Whether a search or a filter by origin narrows the words listed. */
internal fun narrowed(query: String, origin: PersonalEntries.Origin): Boolean =
    query.isNotBlank() || origin != PersonalEntries.Origin.ANY

/** The label and title keys of the words page's delete button, over the words listed. */
internal fun deleteKeys(narrowed: Boolean): Pair<String, String> =
    if (narrowed) {
        Keys.DICTIONARY_DELETE_FILTERED_WORDS to Keys.DICTIONARY_DELETE_FILTERED_WORDS_TITLE
    } else {
        Keys.DICTIONARY_DELETE_ALL_WORDS to Keys.DICTIONARY_DELETE_ALL_WORDS_TITLE
    }

/** The words the Learned words page lists under one [language], and whether it is [off]: no active pack is in it. */
internal data class WordGroup(val language: String, val off: Boolean, val words: List<PersonalEntries.ListedWord>)

/**
 * [listed] in one group per language, each group's words in the order listed: the languages the
 * keyboard counts, with packs active in [active] ([ActivePacks.counts]), then those it does not,
 * each by its subtag, then the uses in no language known ([UserWord.UNPLACED]).
 */
internal fun wordGroups(listed: List<PersonalEntries.ListedWord>, active: Set<String>): List<WordGroup> {
    val byLanguage = listed.groupBy { it.language }
    val languages = (byLanguage.keys - UserWord.UNPLACED).sortedWith(
        compareBy<String> { !ActivePacks.counts(active, it) }.thenBy { it },
    )
    val groups = languages.map { WordGroup(it, !ActivePacks.counts(active, it), byLanguage.getValue(it)) }
    val unplaced = byLanguage[UserWord.UNPLACED]?.let { WordGroup(UserWord.UNPLACED, off = false, words = it) }
    return groups + listOfNotNull(unplaced)
}

/** The heading the Learned pages give [language]: its name, or what stands for no language. */
internal fun languageHeading(language: String, strings: LanguageManager): String = when (language) {
    UserWord.UNPLACED -> strings[Keys.DICTIONARY_WORDS_UNPLACED]
    LanguageTags.UNKNOWN -> strings[Keys.DICTIONARY_PHRASES_UNKNOWN_LANGUAGE]
    else -> displayNameFor(language)
}
