// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.UserPhrase
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
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
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    // The phrases listed when Delete filtered phrases was pressed, until the dialog closes.
    var confirmingDelete by remember { mutableStateOf<List<UserPhrase>?>(null) }
    val saved by repository.phrases.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the phrases that count as learned, as the keyboard counts them.
    val themes = remember { DataGraph.themes }
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    val now = remember { System.currentTimeMillis() }
    val phrases = remember(saved, preferences.learnAfter, preferences.unlearnHalfLifeDays) {
        PersonalEntries.phrases(saved, preferences, now)
    }
    val listed = remember(phrases, query) {
        if (filtering) phrases.filter { textOf(it, strings).contains(query, ignoreCase = true) } else phrases
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_PHRASES, listed.size)) {
                Explanation(strings[Keys.DICTIONARY_PHRASES_NOTE])
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (filtering) {
                    TextButton(
                        onClick = { confirmingDelete = listed },
                        enabled = listed.isNotEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(strings.getString(Keys.DICTIONARY_DELETE_FILTERED_PHRASES, listed.size)) }
                } else {
                    TextButton(
                        onClick = { confirmingDeleteAll = true },
                        enabled = phrases.isNotEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(strings[Keys.DICTIONARY_DELETE_ALL_PHRASES]) }
                }
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
        items(listed, key = { it.words.joinToString(KEY_SEPARATOR) }) { phrase ->
            SettingRow(
                title = textOf(phrase, strings),
                subtitle = strings.counted(Keys.DICTIONARY_PHRASE_USED, phrase.count),
                trailing = {
                    TextButton(onClick = { scope.launch { repository.forgetPhrase(phrase) } }) {
                        Text(strings[Keys.DICTIONARY_DELETE])
                    }
                },
            )
        }
    }

    // Asked first: this deletes every pair and triple; the words stay.
    if (confirmingDeleteAll) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_DELETE_ALL_PHRASES_TITLE],
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_DELETE],
            onDismiss = { confirmingDeleteAll = false },
        ) { scope.launch { repository.forgetAllPhrases() } }
    }

    // Asked first: this deletes the listed phrases; their words and the rest stay.
    confirmingDelete?.let { doomed ->
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_DELETE_FILTERED_PHRASES_TITLE],
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_DELETE],
            onDismiss = { confirmingDelete = null },
        ) { scope.launch { repository.forgetPhrases(doomed) } }
    }
}

/** The row text of [phrase]: its words, or for a sentence opener its word and where it stood. */
private fun textOf(phrase: UserPhrase, strings: LanguageManager): String =
    if (phrase.opensSentence) {
        strings.getString(Keys.DICTIONARY_SENTENCE_OPENER, phrase.words.last())
    } else {
        phrase.words.joinToString(WORD_SEPARATOR)
    }

/** Between the words of a listed phrase. */
private const val WORD_SEPARATOR = " "

/** Between the words of a phrase in its list key; no word holds it. */
private const val KEY_SEPARATOR = "\u0000"
