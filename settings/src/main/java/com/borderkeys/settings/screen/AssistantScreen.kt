// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
import com.borderkeys.settings.SectionHeader
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
                null -> "The file could not be read."
                is AssistModelRepository.ImportResult.Accepted ->
                    "Imported ${result.entry.displayName}."
                is AssistModelRepository.ImportResult.UnknownModel ->
                    "Refused. This file hashes to ${result.sha256.take(16)}…, which is not a " +
                        "model this build knows. The copy has been deleted."
                is AssistModelRepository.ImportResult.Failed ->
                    "Import failed: ${result.cause.message}"
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Installed")
        if (models.isEmpty()) {
            SettingRow(
                title = "No model",
                subtitle = "The assistant does nothing until one is imported. Nothing is " +
                    "downloaded; you choose a file.",
            )
        }
        for (model in models) {
            SettingRow(
                title = model.displayName + if (model.active) " · active" else "",
                subtitle = buildString {
                    append("${model.sizeBytes / 1024 / 1024} MB · ${model.license}\n")
                    append(model.source)
                    if (model.integrityFailedAt != null) {
                        append("\nSwitched off: the file no longer matches its recorded hash.")
                    }
                },
                trailing = {
                    TextButton(onClick = { scope.launch { repository.remove(model) } }) {
                        Text("Remove")
                    }
                },
            )
        }

        Divider()
        SectionHeader("Import")
        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !importing,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) { Text("Choose a GGUF file") }
        if (importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(20.dp))
            Explanation("Copying and hashing. A model is hundreds of megabytes; this takes a while.")
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Divider()
        SectionHeader("Models this build will load")
        Explanation(
            "A GGUF file is not a document — it is weights that a runtime maps and executes in " +
                "a process holding your selected text. So a file is accepted only if its " +
                "SHA-256 matches one of these, taken from the publishing repository's own " +
                "metadata. Anything else is refused and deleted.",
        )
        for (entry in KnownAssistModels.entries) {
            SettingRow(
                title = entry.displayName,
                subtitle = "${entry.sizeBytes / 1024 / 1024} MB · ${entry.license} · needs about " +
                    "${entry.approximateRamMb} MB of RAM\n${entry.source}\n" +
                    "sha256 ${entry.sha256.take(24)}…",
            )
        }

        Divider()
        SectionHeader("How it runs")
        Explanation(
            "In a separate process, started when you ask for something and stopped ninety " +
                "seconds after you stop. The keyboard never loads a model: a process holding " +
                "several hundred megabytes is the first thing Android reclaims, and the keyboard " +
                "has to still be there when you tap the next text field.",
        )
        Explanation(
            "It is reached only from a text selection, never from what you are typing, and it " +
                "is refused outright in a password field. Nothing it produces is inserted until " +
                "you press Replace.",
        )
    }
}
