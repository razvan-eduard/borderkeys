// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What this device has learned, and the only way it can be moved to another one.
 *
 * A CSV export is the whole of "sync" in an application with no network. The user exports a
 * file, carries it, imports it. Nothing leaves the device unless a person moves it.
 */
@Composable
fun DictionaryScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingForgetAll by remember { mutableStateOf(false) }

    val words by (if (query.isBlank()) repository.words else repository.search(query))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val blocked by repository.blocked.collectAsStateWithLifecycle(initialValue = emptyList())
    val pairs by repository.topPairsLive().collectAsStateWithLifecycle(initialValue = emptyList())
    val triples by repository.topTriplesLive().collectAsStateWithLifecycle(initialValue = emptyList())
    val pairCount by repository.pairCount.collectAsStateWithLifecycle(initialValue = 0)
    val tripleCount by repository.tripleCount.collectAsStateWithLifecycle(initialValue = 0)

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val export = repository.exportCsv()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(export.csv.encodeToByteArray())
                    }
                }.isSuccess
            }
            // The count of what was written, not of the list on screen -- that one is capped
            // and follows the search box, and used to be what this sentence reported.
            message = if (written) {
                strings.getString(Keys.DICTIONARY_EXPORTED_WORDS, export.words)
            } else {
                strings[Keys.DICTIONARY_EXPORT_FAILED]
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
            message = if (csv == null) {
                strings[Keys.DICTIONARY_THE_FILE_COULD_NOT_BE_READ]
            } else {
                strings.getString(Keys.DICTIONARY_IMPORTED_WORDS, repository.importCsv(csv))
            }
        }
    }

    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.DICTIONARY_HOW_QUICKLY_IT_LEARNS]) {
            SwitchRow(
                title = strings[Keys.DICTIONARY_LEARN_AT_ALL],
                subtitle = strings[Keys.DICTIONARY_OFF_MEANS_NOTHING_NEW_IS_RECORDED],
                checked = preferences.learningEnabled,
            ) { value -> update { it.copy(learningEnabled = value) } }
            AdvancedSection {
                Explanation(
                    strings[Keys.DICTIONARY_THIS_DOES_NOT_CHANGE_WHAT_IS],
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.DICTIONARY_CAUTIOUS],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_CAUTIOUS,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_CAUTIOUS) } }
                    PickerChip(
                        strings[Keys.DICTIONARY_BALANCED],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_BALANCED,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_BALANCED) } }
                    PickerChip(
                        strings[Keys.DICTIONARY_IMMEDIATE],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_IMMEDIATE,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_IMMEDIATE) } }
                }
                Explanation(
                    when (preferences.learningSpeed) {
                        KeyboardPreferences.LEARNING_CAUTIOUS ->
                            strings[Keys.DICTIONARY_ABOUT_SIX_REPETITIONS_BEFORE_A_PHRASE]
                        KeyboardPreferences.LEARNING_IMMEDIATE ->
                            strings[Keys.DICTIONARY_THE_FIRST_TIME_COUNTS_BEST_IF]
                        else ->
                            strings[Keys.DICTIONARY_A_PHRASE_WRITTEN_TWICE_STARTS_TO]
                    },
                )
            }
        }
        // Shortcuts beside the learned words: both are "what this keyboard knows that the
        // dictionaries do not", one taught by typing and one written down on purpose.
        SettingsSectionCard(strings[Keys.DICTIONARY_SHORTCUTS]) {
            Explanation(strings[Keys.DICTIONARY_SHORTCUTS_NOTE])
            if (preferences.textShortcuts.isEmpty()) {
                SettingRow(title = strings[Keys.DICTIONARY_SHORTCUTS_NONE])
            }
            for (shortcut in preferences.textShortcuts) {
                SettingRow(
                    title = shortcut.trigger,
                    subtitle = shortcut.expansion,
                    trailing = {
                        TextButton(onClick = {
                            update { it.copy(textShortcuts = it.textShortcuts - shortcut) }
                        }) { Text(strings[Keys.DICTIONARY_SHORTCUT_REMOVE]) }
                    },
                )
            }
            var trigger by remember { mutableStateOf("") }
            var expansion by remember { mutableStateOf("") }
            OutlinedTextField(
                value = trigger,
                onValueChange = { trigger = it },
                label = { Text(strings[Keys.DICTIONARY_SHORTCUT_TRIGGER]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = expansion,
                onValueChange = { expansion = it },
                label = { Text(strings[Keys.DICTIONARY_SHORTCUT_EXPANSION]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val addable = TextShortcut.isValidTrigger(trigger.trim()) && expansion.isNotBlank() &&
                preferences.textShortcuts.size < TextShortcut.MAX_SHORTCUTS
            TextButton(
                enabled = addable,
                onClick = {
                    val added = TextShortcut(trigger.trim(), expansion.trim())
                    // Replaces a shortcut with the same trigger rather than adding a second that
                    // would never fire -- sanitised() keeps the first one it meets.
                    update { current ->
                        current.copy(
                            textShortcuts = current.textShortcuts
                                .filterNot { it.trigger.equals(added.trigger, ignoreCase = true) } + added,
                        )
                    }
                    trigger = ""
                    expansion = ""
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(strings[Keys.DICTIONARY_SHORTCUT_ADD]) }
        }
        SettingsSectionCard(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, words.size)) {
            // At the top of the list it filters. It used to close the "how quickly it learns"
            // card instead, two cards above this one and separated from it by the shortcuts --
            // near nothing it affected, so the list it belongs to read as having no search at
            // all, and the note under a long list telling you to use one pointed at thin air.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
            if (words.isEmpty()) {
                SettingRow(
                    title = if (query.isBlank()) strings[Keys.DICTIONARY_NOTHING_LEARNED_YET] else strings[Keys.DICTIONARY_NO_MATCH],
                    subtitle = if (query.isBlank()) {
                        strings[Keys.DICTIONARY_A_WORD_IS_LEARNED_WHEN_YOU]
                    } else {
                        null
                    },
                )
            }
            for (word in words.take(200)) {
                SettingRow(
                    title = word.word,
                    subtitle = strings.getString(Keys.DICTIONARY_CHOSEN_TIMES_TYPED_ON, word.count, word.locale),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { scope.launch { repository.forget(word.word) } }) {
                                Text(strings[Keys.DICTIONARY_FORGET])
                            }
                            TextButton(onClick = { scope.launch { repository.block(word.word) } }) {
                                Text(strings[Keys.DICTIONARY_BLOCK])
                            }
                        }
                    },
                )
            }
            if (words.size > 200) {
                Explanation(strings[Keys.DICTIONARY_SHOWING_THE_FIRST_200_USE_SEARCH])
            }
        }
        // The phrases beside the words they are made of: every pair and triple the keyboard
        // has seen written in that order, each with a Forget of its own.
        SettingsSectionCard(strings.getString(Keys.DICTIONARY_PHRASES, pairCount + tripleCount)) {
            Explanation(strings[Keys.DICTIONARY_PHRASES_NOTE])
            if (pairs.isEmpty() && triples.isEmpty()) {
                SettingRow(title = strings[Keys.DICTIONARY_NOTHING_LEARNED_YET])
            }
            for (pair in pairs) {
                val phrase = listOf(pair.previousWord, pair.word).joinToString(WORD_SEPARATOR)
                SettingRow(
                    title = phrase,
                    subtitle = strings.getString(Keys.DICTIONARY_PHRASE_USED, pair.count),
                    trailing = {
                        TextButton(onClick = {
                            scope.launch { repository.forgetPair(pair.previousWord, pair.word) }
                        }) { Text(strings[Keys.DICTIONARY_FORGET]) }
                    },
                )
            }
            for (triple in triples) {
                val phrase = listOf(triple.previousWord2, triple.previousWord1, triple.word)
                    .joinToString(WORD_SEPARATOR)
                SettingRow(
                    title = phrase,
                    subtitle = strings.getString(Keys.DICTIONARY_PHRASE_USED, triple.count),
                    trailing = {
                        TextButton(onClick = {
                            scope.launch {
                                repository.forgetTriple(triple.previousWord2, triple.previousWord1, triple.word)
                            }
                        }) { Text(strings[Keys.DICTIONARY_FORGET]) }
                    },
                )
            }
            if (pairCount + tripleCount > pairs.size + triples.size) {
                Explanation(strings.getString(Keys.DICTIONARY_SHOWING_PHRASES, pairs.size + triples.size))
            }
        }
        SettingsSectionCard(strings.getString(Keys.DICTIONARY_BLOCKED, blocked.size)) {
            Explanation(
                strings[Keys.DICTIONARY_A_BLOCKED_WORD_IS_NEVER_SUGGESTED],
            )
            for (entry in blocked) {
                SettingRow(
                    title = entry.word,
                    trailing = {
                        TextButton(onClick = { scope.launch { repository.unblock(entry.word) } }) {
                            Text(strings[Keys.DICTIONARY_UNBLOCK])
                        }
                    },
                )
            }
        }
        SettingsSectionCard(strings[Keys.DICTIONARY_MOVE_IT_TO_ANOTHER_PHONE]) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { exporter.launch("borderkeys-dictionary.csv") }) {
                    Text(strings[Keys.DICTIONARY_EXPORT_CSV])
                }
                TextButton(onClick = { importer.launch(arrayOf("text/*", "*/*")) }) {
                    Text(strings[Keys.DICTIONARY_IMPORT_CSV])
                }
                TextButton(onClick = { confirmingForgetAll = true }) {
                    Text(strings[Keys.DICTIONARY_FORGET_EVERYTHING])
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Explanation(
                strings[Keys.DICTIONARY_THIS_IS_THE_ONLY_FORM_OF],
            )
        }
    }

    // Asked first: this is every word and pair the device has learned, and the one tap that
    // used to do it sat on a row beside Export and Import.
    if (confirmingForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmingForgetAll = false },
            title = { Text(strings[Keys.DICTIONARY_FORGET_EVERYTHING_TITLE]) },
            text = { Text(strings[Keys.COMMON_CANNOT_BE_UNDONE]) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingForgetAll = false
                    scope.launch { repository.forgetEverything() }
                }) { Text(strings[Keys.DICTIONARY_FORGET_EVERYTHING], color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForgetAll = false }) { Text(strings[Keys.THEME_CANCEL]) }
            },
        )
    }
}

/** Between the words of a listed phrase. */
private const val WORD_SEPARATOR = " "
