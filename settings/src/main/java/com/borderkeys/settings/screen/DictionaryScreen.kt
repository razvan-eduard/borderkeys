// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
            message = if (written) "Exported ${words.size} words." else "Export failed."
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
                "The file could not be read."
            } else {
                "Imported ${repository.importCsv(csv)} words."
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
        SectionHeader("How quickly it learns")
        Explanation(
            "This does not change what is recorded — every word and pair you confirm is stored " +
                "either way. It changes how soon what you write starts leading the suggestion " +
                "strip instead of what the dictionary says.",
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeedChip("Cautious", KeyboardPreferences.LEARNING_CAUTIOUS, preferences, ::update)
            SpeedChip("Balanced", KeyboardPreferences.LEARNING_BALANCED, preferences, ::update)
            SpeedChip("Immediate", KeyboardPreferences.LEARNING_IMMEDIATE, preferences, ::update)
        }
        Explanation(
            when (preferences.learningSpeed) {
                KeyboardPreferences.LEARNING_CAUTIOUS ->
                    "About six repetitions before a phrase you write outranks what the " +
                        "dictionary predicts. The keyboard will not rearrange itself around one " +
                        "sentence you wrote once."
                KeyboardPreferences.LEARNING_IMMEDIATE ->
                    "The first time counts. Best if you write the same things every day; it " +
                        "does mean a one-off phrase leads until you write something else."
                else ->
                    "A phrase written twice starts to lead. What the dictionary predicts stays " +
                        "in the strip behind it."
            },
        )
        SwitchRow(
            title = "Learn at all",
            subtitle = "Off means nothing new is recorded. What has already been learned stays " +
                "until you delete it below.",
            checked = preferences.learningEnabled,
        ) { value -> update { it.copy(learningEnabled = value) } }

        Divider()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )

        SectionHeader("Learned words (${words.size})")
        if (words.isEmpty()) {
            SettingRow(
                title = if (query.isBlank()) "Nothing learned yet" else "No match",
                subtitle = if (query.isBlank()) {
                    "A word is learned when you choose it from the suggestion strip. Nothing is " +
                        "learned from what you merely typed, and nothing at all in a password " +
                        "field."
                } else {
                    null
                },
            )
        }
        for (word in words.take(200)) {
            SettingRow(
                title = word.word,
                subtitle = "chosen ${word.count} times · typed on ${word.locale}",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { scope.launch { repository.forget(word.word) } }) {
                            Text("Forget")
                        }
                        TextButton(onClick = { scope.launch { repository.block(word.word) } }) {
                            Text("Block")
                        }
                    }
                },
            )
        }
        if (words.size > 200) {
            Explanation("Showing the first 200. Use search to narrow it down.")
        }

        Divider()
        SectionHeader("Blocked (${blocked.size})")
        Explanation(
            "A blocked word is never suggested again. Blocking also forgets it: deleting alone " +
                "would let the word come straight back from the language pack.",
        )
        for (entry in blocked) {
            SettingRow(
                title = entry.word,
                trailing = {
                    TextButton(onClick = { scope.launch { repository.unblock(entry.word) } }) {
                        Text("Unblock")
                    }
                },
            )
        }

        Divider()
        SectionHeader("Move it to another phone")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { exporter.launch("borderkeys-dictionary.csv") }) {
                Text("Export CSV")
            }
            TextButton(onClick = { importer.launch(arrayOf("text/*", "*/*")) }) {
                Text("Import CSV")
            }
            TextButton(onClick = { scope.launch { repository.forgetEverything() } }) {
                Text("Forget everything")
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
            "This is the only form of sync there is. The app cannot reach a network, so a file " +
                "you carry is the only way anything moves.",
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
