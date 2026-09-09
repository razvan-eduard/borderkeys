// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.AssistModelRepository
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.KnownAssistModels
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LinkedText
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The text assistant's model: which one, where it came from, and why an unknown file is refused.
 *
 * Present only in the `plus` build — the Home screen resolves the service before offering the
 * row, so reaching this screen at all means the assistant exists.
 */
@Composable
fun AssistantScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val repository = remember { DataGraph.assistModels }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val models by repository.models.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences.collectAsStateWithLifecycle(
        initialValue = remember { themes.currentPreferences() },
    )
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Set only right after a successful import, to the URI just imported from -- asked about
    // there rather than upfront, so the choice is "delete the file that became this model" with
    // the model already sitting safely in the list, not a checkbox ticked in advance of an
    // import that might still have failed.
    var offeringDeleteSource by remember { mutableStateOf<Uri?>(null) }

    fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { themes.updatePreferences(transform) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.take(96)
                        ?: "model.gguf"
                    context.contentResolver.openInputStream(uri)?.use {
                        repository.import(it, name)
                    }
                }.getOrNull()
            }
            if (result is AssistModelRepository.ImportResult.Accepted) {
                offeringDeleteSource = uri
            }
            importing = false
            message = when (result) {
                null -> strings[Keys.ASSISTANT_THE_FILE_COULD_NOT_BE_READ]
                is AssistModelRepository.ImportResult.Accepted ->
                    strings.getString(Keys.ASSISTANT_IMPORTED, result.entry.displayName)
                is AssistModelRepository.ImportResult.UnknownModel ->
                    strings.getString(
                        Keys.ASSISTANT_REFUSED_THIS_FILE_HASHES_TO_WHICH,
                        result.sha256.take(16),
                    )
                is AssistModelRepository.ImportResult.Failed ->
                    strings.getString(Keys.ASSISTANT_IMPORT_FAILED, result.cause.message)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.ASSISTANT_INSTALLED]) {
            if (models.isEmpty()) {
                SettingRow(
                    title = strings[Keys.ASSISTANT_NO_MODEL],
                    subtitle = strings[Keys.ASSISTANT_THE_ASSISTANT_DOES_NOTHING_UNTIL_ONE],
                )
            }
            for (model in models) {
                ModelRow(
                    title = model.displayName + if (model.active) strings[Keys.ASSISTANT_ACTIVE] else "",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Only offered for the models not already carrying the load -- a
                            // button whose entire effect is "this is already what happens"
                            // would just be a second, redundant way to read the label above it.
                            if (!model.active) {
                                TextButton(onClick = { scope.launch { repository.activate(model) } }) {
                                    Text(strings[Keys.ASSISTANT_ACTIVATE])
                                }
                            }
                            TextButton(onClick = { scope.launch { repository.remove(model) } }) {
                                Text(strings[Keys.ASSISTANT_REMOVE])
                            }
                        }
                    },
                ) {
                    Text(
                        "${model.sizeBytes / 1024 / 1024} MB · ${model.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinkedText(model.source, model.source)
                    if (model.integrityFailedAt != null) {
                        Text(
                            strings[Keys.ASSISTANT_SWITCHED_OFF_THE_FILE_NO_LONGER],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        SettingsSectionCard(strings[Keys.ASSISTANT_IMPORT]) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !importing,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(strings[Keys.ASSISTANT_CHOOSE_A_GGUF_FILE]) }
            if (importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(20.dp))
                Explanation(strings[Keys.ASSISTANT_COPYING_AND_HASHING_A_MODEL_IS])
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        SettingsSectionCard(strings[Keys.ASSISTANT_MODELS_THIS_BUILD_WILL_LOAD]) {
            Explanation(
                strings[Keys.ASSISTANT_A_GGUF_FILE_IS_NOT_A],
            )
            for (entry in KnownAssistModels.entries) {
                ModelRow(title = entry.displayName) {
                    LinkedText(
                        strings.getString(
                            Keys.ASSISTANT_MB_NEEDS_ABOUT_MB_OF_RAM,
                            entry.sizeBytes / 1024 / 1024, entry.license, entry.approximateRamMb,
                            entry.source, entry.sha256.take(24),
                        ),
                        entry.source,
                    )
                    Explanation(strings[modelNoteFor(entry)])
                }
            }
        }
        SettingsSectionCard(strings[Keys.ASSISTANT_HOW_IT_CHOOSES_WORDS]) {
            Text(
                strings[Keys.ASSISTANT_TEMPERATURE],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "%.2f".format(preferences.assistTemperature),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Slider(
                value = preferences.assistTemperature,
                valueRange = KeyboardPreferences.MIN_ASSIST_TEMPERATURE..
                    KeyboardPreferences.MAX_ASSIST_TEMPERATURE,
                onValueChange = { value ->
                    updatePreferences { it.copy(assistTemperature = value) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Explanation(strings[Keys.ASSISTANT_TEMPERATURE_NOTE])
            Text(
                strings[Keys.ASSISTANT_TOP_P],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "%.2f".format(preferences.assistTopP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Slider(
                value = preferences.assistTopP,
                valueRange = KeyboardPreferences.MIN_ASSIST_TOP_P..
                    KeyboardPreferences.MAX_ASSIST_TOP_P,
                onValueChange = { value ->
                    updatePreferences { it.copy(assistTopP = value) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Explanation(strings[Keys.ASSISTANT_TOP_P_NOTE])
            Button(
                onClick = {
                    updatePreferences {
                        it.copy(
                            assistTemperature = KeyboardPreferences.DEFAULT_ASSIST_TEMPERATURE,
                            assistTopP = KeyboardPreferences.DEFAULT_ASSIST_TOP_P,
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            ) { Text(strings[Keys.COMMON_RESET_TO_DEFAULTS]) }
            Explanation(strings[Keys.COMMON_RESET_TO_DEFAULTS_NOTE])
        }
        SettingsSectionCard(strings[Keys.ASSISTANT_HOW_IT_RUNS]) {
            Explanation(
                strings[Keys.ASSISTANT_IN_A_SEPARATE_PROCESS_STARTED_WHEN],
            )
            Explanation(
                strings[Keys.ASSISTANT_IT_IS_REACHED_ONLY_FROM_A],
            )
        }
    }

    offeringDeleteSource?.let { uri ->
        AlertDialog(
            onDismissRequest = { offeringDeleteSource = null },
            title = { Text(strings[Keys.ASSISTANT_DELETE_THE_ORIGINAL_FILE]) },
            text = { Text(strings[Keys.ASSISTANT_THE_MODEL_IS_IMPORTED_AND_WORKS]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        offeringDeleteSource = null
                        scope.launch {
                            // Best-effort: OpenDocument grants a write URI alongside the read
                            // one, but whether the provider on the other end honours a delete
                            // through it belongs to that provider, not this application. Nothing
                            // about the model just imported depends on this succeeding -- it
                            // already has its own copy, independent of the source from here on.
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        strings[Keys.ASSISTANT_DELETE],
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { offeringDeleteSource = null }) {
                    Text(strings[Keys.ASSISTANT_CANCEL])
                }
            },
        )
    }
}

/**
 * [SettingRow]'s layout with the subtitle replaced by a composable slot.
 *
 * Needed here and only here: a model's row wants its source rendered as a tappable link, which a
 * plain `String` subtitle cannot carry.
 */
@Composable
private fun ModelRow(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            content()
        }
        trailing?.invoke()
    }
}

/**
 * What each entry in [KnownAssistModels.entries] is actually good at, keyed by its file name --
 * already a stable, unique identifier per entry, so nothing new has to be added to that class
 * just to carry a piece of Settings-screen copy a pure data layer has no other reason to know
 * about.
 */
private fun modelNoteFor(entry: KnownAssistModels.Entry): String = when (entry.fileName) {
    "Qwen3-0.6B-Q8_0.gguf" -> Keys.ASSISTANT_MODEL_QWEN3_06B_NOTE
    "Qwen3-1.7B-Q8_0.gguf" -> Keys.ASSISTANT_MODEL_QWEN3_17B_NOTE
    "SmolLM3-Q4_K_M.gguf" -> Keys.ASSISTANT_MODEL_SMOLLM3_3B_NOTE
    "EuroLLM-1.7B-Instruct.Q8_0.gguf" -> Keys.ASSISTANT_MODEL_EUROLLM_17B_NOTE
    "EuroLLM-9B-Instruct-Q4_K_M.gguf" -> Keys.ASSISTANT_MODEL_EUROLLM_9B_NOTE
    // Reached only if a future entry is added to KnownAssistModels.kt without a matching case
    // here -- honest about being generic rather than silently wearing another model's note.
    else -> Keys.ASSISTANT_MODEL_GENERAL_NOTE
}
