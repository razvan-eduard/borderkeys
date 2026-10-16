// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.WordShape
import com.borderkeys.i18n.Keys

/** Whether [candidate] can be added: a word by shape, and no stored row is that exact spelling. */
internal fun addable(candidate: String, stored: Set<String>): Boolean =
    WordShape.isWord(candidate) && candidate !in stored

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
