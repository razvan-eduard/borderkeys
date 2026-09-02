// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.borderkeys.data.AssistModelRepository
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.KnownAssistModels
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
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
    val scope = rememberCoroutineScope()
    val models by repository.models.collectAsStateWithLifecycle(initialValue = emptyList())
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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
                SettingRow(
                    title = model.displayName + if (model.active) strings[Keys.ASSISTANT_ACTIVE] else "",
                    subtitle = buildString {
                        append("${model.sizeBytes / 1024 / 1024} MB · ${model.license}\n")
                        append(model.source)
                        if (model.integrityFailedAt != null) {
                            append(strings[Keys.ASSISTANT_SWITCHED_OFF_THE_FILE_NO_LONGER])
                        }
                    },
                    trailing = {
                        TextButton(onClick = { scope.launch { repository.remove(model) } }) {
                            Text(strings[Keys.ASSISTANT_REMOVE])
                        }
                    },
                )
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
                SettingRow(
                    title = entry.displayName,
                    subtitle = strings.getString(Keys.ASSISTANT_MB_NEEDS_ABOUT_MB_OF_RAM, entry.sizeBytes / 1024 / 1024, entry.license, entry.approximateRamMb, entry.source, entry.sha256.take(24)),
                )
            }
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
}
