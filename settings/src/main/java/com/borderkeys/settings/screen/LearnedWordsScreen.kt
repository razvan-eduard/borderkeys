// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
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
    var confirmingForgetAll by remember { mutableStateOf(false) }
    // The words listed when Delete filtered words was pressed, until the dialog closes.
    var confirmingDelete by remember { mutableStateOf<List<String>?>(null) }
    val listed = remember(query) { if (filtering) repository.search(query) else repository.words }
    val words by listed.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, words.size)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (filtering) {
                    TextButton(
                        onClick = { confirmingDelete = words.map { it.word } },
                        enabled = words.isNotEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(strings.getString(Keys.DICTIONARY_DELETE_FILTERED_WORDS, words.size)) }
                } else {
                    TextButton(
                        onClick = { confirmingForgetAll = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(strings[Keys.DICTIONARY_FORGET_EVERYTHING]) }
                }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { scope.launch { repository.block(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_BLOCK])
                        }
                        TextButton(onClick = { scope.launch { repository.forget(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_DELETE])
                        }
                    }
                },
            )
        }
    }

    // Asked first: this forgets every learned word and phrase, and the heatmap.
    if (confirmingForgetAll) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_FORGET_EVERYTHING_TITLE],
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_FORGET_EVERYTHING],
            onDismiss = { confirmingForgetAll = false },
        ) { scope.launch { repository.forgetEverything() } }
    }

    // Asked first: this deletes the listed words and their phrases; the rest stays.
    confirmingDelete?.let { doomed ->
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_DELETE_FILTERED_WORDS_TITLE],
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_DELETE],
            onDismiss = { confirmingDelete = null },
        ) { scope.launch { repository.forget(doomed) } }
    }
}
