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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.borderkeys.data.entity.LanguagePackEntry
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
 * The language packs, their weights, and where a new one comes from.
 *
 * Several are active at once. There is no "current language" here and no switch to press mid
 * sentence, because writing Romanian and English in the same message is the normal case and a
 * keyboard that makes you announce which one you are using has already lost.
 */
@Composable
fun LanguagesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { DataGraph.languagePacks }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val packs by repository.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        importing = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.take(64)
                        ?: "imported.bkd"
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // Copied into private storage and hashed on the way through. The file
                        // behind a content:// URI can be rewritten by the app that owns it
                        // between being checked and being mapped, so it is never mapped.
                        repository.stage(stream, name)
                    }
                }.getOrNull()
            }
            importing = false
            message = when {
                result == null -> "The file could not be read."
                result.isFailure -> "Import failed: ${result.exceptionOrNull()?.message}"
                else -> {
                    val staged = result.getOrThrow()
                    "Copied ${staged.sizeBytes} bytes. SHA-256 ${staged.sha256.take(16)}… " +
                        "Validation happens when the keyboard next starts."
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Installed packs")
        if (packs.isEmpty()) {
            SettingRow(
                title = "None yet",
                subtitle = "Nothing is downloaded, ever. A pack is a file you choose, built " +
                    "with tools/build_dict.py from a word list whose licence you know.",
            )
        }
        for (pack in packs) {
            PackRow(pack, repository, scope)
            Divider()
        }

        SectionHeader("Import")
        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !importing,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) { Text("Choose a .bkd file") }
        if (importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(20.dp))
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
            "A pack's header is validated completely before any of it is mapped — magic, " +
                "version, checksum, and every section offset against the real file size. A pack " +
                "that does not pass is refused rather than interpreted.",
        )

        Divider()
        SectionHeader("Per-app language memory")
        SwitchRow(
            title = "Remember which languages you use in which app",
            subtitle = "Off by default. It stores a hash of the app's name against learned " +
                "weights — a behavioural profile, however small and however local. Turning it " +
                "off deletes what was learned.",
            checked = preferences.perAppLanguageMemory,
        ) { value ->
            scope.launch { themes.updatePreferences { it.copy(perAppLanguageMemory = value) } }
        }
    }
}

@Composable
private fun PackRow(
    pack: LanguagePackEntry,
    repository: com.borderkeys.data.LanguagePackRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SettingRow(
            title = "${pack.displayName} (${pack.tag})",
            subtitle = buildString {
                append("${pack.wordCount} words, ${pack.sizeBytes / 1024} KB")
                append(" · ").append(pack.licenseNote.ifEmpty { "licence not recorded" })
                if (pack.integrityFailedAt != null) {
                    append("\nSwitched itself off: the file no longer matches the hash it was ")
                    append("imported with.")
                }
            },
            trailing = {
                Switch(
                    checked = pack.enabled,
                    onCheckedChange = { scope.launch { repository.setEnabled(pack.id, it) } },
                )
            },
        )
        Text(
            "Weight ${"%.2f".format(pack.weight)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Slider(
            value = pack.weight.coerceIn(0.05f, 4f),
            valueRange = 0.05f..4f,
            onValueChange = { scope.launch { repository.setWeight(pack.id, it) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        TextButton(
            onClick = { scope.launch { repository.remove(pack) } },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("Remove") }
    }
}
