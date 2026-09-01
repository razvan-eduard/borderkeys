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
import androidx.compose.material3.FilterChip
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
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
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

    val words by (if (query.isBlank()) repository.words else repository.search(query))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val blocked by repository.blocked.collectAsStateWithLifecycle(initialValue = emptyList())

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = repository.exportCsv()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(csv.encodeToByteArray())
                    }
                }.isSuccess
            }
            message = if (written) strings.getString(Keys.DICTIONARY_EXPORTED_WORDS, words.size) else strings[Keys.DICTIONARY_EXPORT_FAILED]
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
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { themes.updatePreferences(transform) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(strings[Keys.DICTIONARY_HOW_QUICKLY_IT_LEARNS])
        Explanation(
            strings[Keys.DICTIONARY_THIS_DOES_NOT_CHANGE_WHAT_IS],
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeedChip(strings[Keys.DICTIONARY_CAUTIOUS], KeyboardPreferences.LEARNING_CAUTIOUS, preferences, ::update)
            SpeedChip(strings[Keys.DICTIONARY_BALANCED], KeyboardPreferences.LEARNING_BALANCED, preferences, ::update)
            SpeedChip(strings[Keys.DICTIONARY_IMMEDIATE], KeyboardPreferences.LEARNING_IMMEDIATE, preferences, ::update)
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
        SwitchRow(
            title = strings[Keys.DICTIONARY_LEARN_AT_ALL],
            subtitle = strings[Keys.DICTIONARY_OFF_MEANS_NOTHING_NEW_IS_RECORDED],
            checked = preferences.learningEnabled,
        ) { value -> update { it.copy(learningEnabled = value) } }

        Divider()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )

        SectionHeader(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, words.size))
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

        Divider()
        SectionHeader(strings.getString(Keys.DICTIONARY_BLOCKED, blocked.size))
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

        Divider()
        SectionHeader(strings[Keys.DICTIONARY_MOVE_IT_TO_ANOTHER_PHONE])
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
            TextButton(onClick = { scope.launch { repository.forgetEverything() } }) {
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

@Composable
private fun SpeedChip(
    label: String,
    speed: Int,
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    FilterChip(
        selected = preferences.learningSpeed == speed,
        onClick = { update { it.copy(learningSpeed = speed) } },
        label = { Text(label) },
    )
}
