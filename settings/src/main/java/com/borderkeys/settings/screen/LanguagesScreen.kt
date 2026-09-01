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
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.predict.LanguagePackInspector
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
            message = withContext(Dispatchers.IO) { importPack(context, repository, uri) }
            importing = false
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Installed packs")
        if (packs.isEmpty()) {
            SettingRow(
                title = "None yet",
                subtitle = "Nothing is downloaded, ever. Add one of the dictionaries below, " +
                    "which are inside the application, or import a file you built yourself " +
                    "with tools/build_dict.py from a word list whose licence you know.",
            )
        }
        for (pack in packs) {
            PackRow(pack, repository, scope)
            Divider()
        }

        val installable = BundledDictionaries.ALL.filter { candidate ->
            packs.none { it.tag.equals(candidate.tag, ignoreCase = true) }
        }
        if (installable.isNotEmpty()) {
            SectionHeader("Included with the app")
            Explanation(
                "These are in the application itself, not on a server — there is no network " +
                    "here to fetch anything from. Adding one copies it out of the app and " +
                    "validates it exactly like a file you chose.",
            )
            for (candidate in installable) {
                SettingRow(
                    title = candidate.displayName,
                    subtitle = "${candidate.wordCount} words, written in this repository. A " +
                        "starter: enough for everyday words, and meant to be replaced by a " +
                        "dictionary compiled from a real corpus.",
                    trailing = {
                        TextButton(
                            enabled = !importing,
                            onClick = {
                                importing = true
                                message = null
                                scope.launch {
                                    message = withContext(Dispatchers.IO) {
                                        installBundled(context, repository, candidate)
                                    }
                                    importing = false
                                }
                            },
                        ) { Text("Add") }
                    },
                )
            }
            Divider()
        }

        SectionHeader("Import your own")
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


/**
 * Copies a chosen file into private storage, validates it, and records it -- or removes it.
 *
 * The three steps are one operation on purpose. Staging a pack and leaving it unregistered would
 * put a file on disk that nothing lists, nothing loads and nothing can delete through the UI,
 * which is what this screen used to do.
 *
 * The file is copied before it is validated rather than validated in place. A `content://` URI
 * is served by another application, which is free to rewrite what is behind it between the
 * moment it is checked and the moment it is mapped; the copy in private storage cannot be
 * rewritten by anyone, so the bytes that were validated are the bytes that get mapped.
 *
 * Returns the sentence to show under the button, whatever happened.
 */
private suspend fun importPack(
    context: android.content.Context,
    repository: com.borderkeys.data.LanguagePackRepository,
    uri: android.net.Uri,
): String {
    val name = uri.lastPathSegment?.substringAfterLast('/')?.take(64) ?: "imported.bkd"
    val staged = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            repository.stage(stream, name)
        }
    }.getOrNull()

    if (staged == null) {
        return "The file could not be read."
    }
    if (staged.isFailure) {
        return "Import failed: ${staged.exceptionOrNull()?.message}"
    }
    val pack = staged.getOrThrow()

    return when (val verdict = LanguagePackInspector.inspect(pack.file)) {
        is LanguagePackInspector.Result.Refused -> {
            // Refused means refused: the file goes, so a pack that cannot be read cannot sit in
            // private storage taking up space and waiting to be tried again.
            pack.file.delete()
            "Refused — ${verdict.reason}."
        }

        is LanguagePackInspector.Result.Valid -> {
            val info = verdict.info
            repository.register(
                LanguagePackEntry(
                    tag = info.tag,
                    displayName = displayNameFor(info.tag),
                    fileName = pack.file.name,
                    formatVersion = info.formatVersion,
                    wordCount = info.wordCount,
                    sizeBytes = pack.sizeBytes,
                    sha256 = pack.sha256,
                    importedAt = System.currentTimeMillis(),
                    enabled = true,
                    weight = 1f,
                    // Nothing here can know the licence of a word list someone compiled
                    // themselves, and inventing one would be worse than admitting it. The
                    // packs the project publishes carry theirs in docs/licensing.md.
                    licenseNote = "not recorded — set by whoever built the pack",
                ),
            )
            "Added ${info.wordCount} words for ${info.tag}. " +
                "SHA-256 ${pack.sha256.take(16)}…"
        }
    }
}

/** The language tag as a person would read it, falling back to the tag itself. */
private fun displayNameFor(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(java.util.Locale.getDefault())
    return if (name.isBlank() || name == tag) tag else name
}


/**
 * Installs a dictionary that shipped inside the application.
 *
 * The same path a chosen file takes: copied into private storage, validated by the native
 * header checks, recorded in the same table. Nothing about it is special afterwards -- it can be
 * weighted, switched off and removed exactly like an imported one, and replacing it with a real
 * compiled corpus is an import away.
 */
private suspend fun installBundled(
    context: android.content.Context,
    repository: com.borderkeys.data.LanguagePackRepository,
    entry: BundledDictionaries.Entry,
): String {
    val staged = runCatching {
        BundledDictionaries.open(context.assets, entry).use { stream ->
            repository.stage(stream, entry.fileName)
        }
    }.getOrNull()

    if (staged == null || staged.isFailure) {
        return "The bundled dictionary could not be read."
    }
    val pack = staged.getOrThrow()

    return when (val verdict = LanguagePackInspector.inspect(pack.file)) {
        is LanguagePackInspector.Result.Refused -> {
            pack.file.delete()
            // A pack this application compiled itself failing its own validator is a build
            // problem, not a user problem, and saying so is more use than "import failed".
            "The bundled dictionary is not valid: ${verdict.reason}. This is a bug."
        }

        is LanguagePackInspector.Result.Valid -> {
            val info = verdict.info
            repository.register(
                LanguagePackEntry(
                    tag = info.tag,
                    displayName = displayNameFor(info.tag),
                    fileName = pack.file.name,
                    formatVersion = info.formatVersion,
                    wordCount = info.wordCount,
                    sizeBytes = pack.sizeBytes,
                    sha256 = pack.sha256,
                    importedAt = System.currentTimeMillis(),
                    enabled = true,
                    weight = 1f,
                    licenseNote = "GPL-3.0-or-later — the word list is written in this repository",
                ),
            )
            "Added ${info.wordCount} words for ${info.tag}."
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
