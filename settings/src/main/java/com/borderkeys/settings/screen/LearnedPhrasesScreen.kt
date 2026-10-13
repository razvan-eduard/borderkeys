// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.UserPhrase
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.language.LanguageTags
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberLearned
import kotlinx.coroutines.launch

/**
 * Every pair and triple this device has learned, searchable by the text each row shows, each with
 * Delete. Below the search, Delete all phrases, or, while a search filters the list, Delete
 * filtered phrases.
 */
@Composable
fun LearnedPhrasesScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val filtering = query.isNotBlank()
    val saved by repository.phrases.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the phrases that count as learned, as the keyboard counts them.
    val phrases = rememberLearned(saved, PersonalEntries::phrases)
    // Each word in the spelling it was learned in when its capital was typed on purpose.
    val words by repository.words.collectAsStateWithLifecycle(initialValue = emptyList())
    val spellings = remember(words) {
        words.filter { it.deliberateCapitals > 0 }.associate { PhraseKey.word(it.word) to it.word }
    }
    val listed = remember(phrases, query, spellings) {
        if (filtering) {
            phrases.filter { textOf(it, spellings, strings).contains(query, ignoreCase = true) }
        } else {
            phrases
        }
    }
    // Under a heading per language.
    val byLanguage = remember(listed) { listed.groupBy { it.language } }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_PHRASES, listed.size)) {
                Explanation(strings[Keys.DICTIONARY_PHRASES_NOTE])
                SearchAndDelete(
                    query = query,
                    onQuery = { query = it },
                    listed = listed,
                    deleteListedLabel = strings.getString(Keys.DICTIONARY_DELETE_FILTERED_PHRASES, listed.size),
                    deleteListedTitle = strings[Keys.DICTIONARY_DELETE_FILTERED_PHRASES_TITLE],
                    // The listed phrases; their words and the rest stay.
                    onDeleteListed = { doomed -> scope.launch { repository.forgetPhrases(doomed) } },
                    clearAllLabel = strings[Keys.DICTIONARY_DELETE_ALL_PHRASES],
                    clearAllTitle = strings[Keys.DICTIONARY_DELETE_ALL_PHRASES_TITLE],
                    clearAllConfirm = strings[Keys.DICTIONARY_DELETE],
                    clearAllEnabled = phrases.isNotEmpty(),
                    // Every pair and triple; the words stay.
                    onClearAll = { scope.launch { repository.forgetAllPhrases() } },
                )
            }
        }
        if (listed.isEmpty()) {
            item {
                SettingRow(
                    title = if (filtering) {
                        strings[Keys.DICTIONARY_NO_MATCH]
                    } else {
                        strings[Keys.DICTIONARY_NOTHING_LEARNED_YET]
                    },
                )
            }
        }
        for ((language, inLanguage) in byLanguage) {
            item(key = language) {
                SectionHeader(
                    if (language == LanguageTags.UNKNOWN) {
                        strings[Keys.DICTIONARY_PHRASES_UNKNOWN_LANGUAGE]
                    } else {
                        displayNameFor(language)
                    },
                )
            }
            items(inLanguage, key = { (listOf(it.language) + it.words).joinToString(KEY_SEPARATOR) }) { phrase ->
                SettingRow(
                    title = textOf(phrase, spellings, strings),
                    subtitle = strings.counted(Keys.DICTIONARY_PHRASE_USED, phrase.count),
                    trailing = {
                        TextButton(onClick = { scope.launch { repository.forgetPhrase(phrase) } }) {
                            Text(strings[Keys.DICTIONARY_DELETE])
                        }
                    },
                )
            }
        }
    }
}

/**
 * The row text of [phrase]: its words, or for a sentence opener its word and where it stood, each
 * word in the spelling [spellings] holds for it.
 */
private fun textOf(phrase: UserPhrase, spellings: Map<String, String>, strings: LanguageManager): String {
    val shown = phrase.words.map { spellings[it] ?: it }
    return if (phrase.opensSentence) {
        strings.getString(Keys.DICTIONARY_SENTENCE_OPENER, shown.last())
    } else {
        shown.joinToString(WORD_SEPARATOR)
    }
}

/** Between the words of a listed phrase. */
private const val WORD_SEPARATOR = " "

/** Between the words of a phrase in its list key; no word holds it. */
private const val KEY_SEPARATOR = "\u0000"
