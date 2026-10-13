// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SPACE_XSMALL
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberLearned
import kotlinx.coroutines.launch

/**
 * Every word this device has learned, searchable, each with Block and Delete. Below the search,
 * Forget everything, or, while a search filters the list, Delete filtered words.
 */
@Composable
fun LearnedWordsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val filtering = query.isNotBlank()
    val listed = remember(query) { if (filtering) repository.search(query) else repository.words }
    val found by listed.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the words that count as learned, as the keyboard counts them.
    val saved by repository.words.collectAsStateWithLifecycle(initialValue = emptyList())
    val learnedWords = rememberLearned(saved, PersonalEntries::words)
    val learned = remember(learnedWords) { learnedWords.mapTo(HashSet()) { it.word } }
    val words = remember(found, learned) { found.filter { it.word in learned } }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, words.size)) {
                SearchAndDelete(
                    query = query,
                    onQuery = { query = it },
                    listed = words,
                    deleteListedLabel = strings.getString(Keys.DICTIONARY_DELETE_FILTERED_WORDS, words.size),
                    deleteListedTitle = strings[Keys.DICTIONARY_DELETE_FILTERED_WORDS_TITLE],
                    // The listed words and their phrases; the rest stays.
                    onDeleteListed = { doomed -> scope.launch { repository.forget(doomed.map { it.word }) } },
                    clearAllLabel = strings[Keys.DICTIONARY_FORGET_EVERYTHING],
                    clearAllTitle = strings[Keys.DICTIONARY_FORGET_EVERYTHING_TITLE],
                    clearAllConfirm = strings[Keys.DICTIONARY_FORGET_EVERYTHING],
                    clearAllEnabled = true,
                    // Every learned word and phrase, and the heatmap.
                    onClearAll = { scope.launch { repository.forgetEverything() } },
                )
            }
        }
        if (words.isEmpty()) {
            item {
                SettingRow(
                    title = if (filtering) {
                        strings[Keys.DICTIONARY_NO_MATCH]
                    } else {
                        strings[Keys.DICTIONARY_NOTHING_LEARNED_YET]
                    },
                    subtitle = if (filtering) null else strings[Keys.DICTIONARY_A_WORD_IS_LEARNED_WHEN_YOU],
                )
            }
        }
        items(words, key = { it.word }) { word ->
            SettingRow(
                title = word.word,
                subtitle = strings.counted(Keys.DICTIONARY_CHOSEN_TIMES_TYPED_ON, word.count, word.count, word.locale),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(SPACE_XSMALL)) {
                        TextButton(onClick = { scope.launch { repository.block(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_BLOCK])
                        }
                        TextButton(onClick = { scope.launch { repository.forgetEveryCase(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_DELETE])
                        }
                    }
                },
            )
        }
    }
}
