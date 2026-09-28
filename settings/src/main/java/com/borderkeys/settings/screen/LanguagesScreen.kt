// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The language packs, their weights, and where a new one comes from. Several are active at once.
 */
@Composable
fun LanguagesScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val repository = remember { DataGraph.languagePacks }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val packs by repository.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })

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
            message = withContext(Dispatchers.IO) { importPack(strings, context, repository, uri) }
            importing = false
        }
    }

    // The switches stop at MAX_ENABLED packs, and say so.
    val atLimit = packs.count { it.enabled } >= LanguagePackRepository.MAX_ENABLED

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InterfaceLanguage(preferences) { code ->
            scope.launch { DataGraph.themes.updatePreferences { it.copy(uiLanguage = code) } }
        }
        SettingsSectionCard(strings[Keys.LANGUAGES_INSTALLED_PACKS]) {
            if (packs.isEmpty()) {
                SettingRow(
                    title = strings[Keys.LANGUAGES_NONE_YET],
                    subtitle = strings[Keys.LANGUAGES_NOTHING_IS_DOWNLOADED_EVER_ADD_ONE],
                )
            }
            for (pack in packs) {
                PackRow(
                    pack,
                    repository,
                    scope,
                    canSwitchOn = !atLimit,
                    preferred = preferences.preferredLanguageTag.equals(pack.tag, ignoreCase = true),
                ) { tag ->
                    scope.launch {
                        DataGraph.themes.updatePreferences { it.copy(preferredLanguageTag = tag) }
                    }
                }
            }
            if (atLimit) {
                Explanation(
                    strings.getString(Keys.LANGUAGES_LIMIT_REACHED, LanguagePackRepository.MAX_ENABLED),
                )
            }
            // Once, under the packs.
            if (packs.isNotEmpty()) {
                Explanation(strings[Keys.LANGUAGES_PREFERRED_NOTE])
            }
            // How the packs are weighed against each other while writing.
            AdvancedSection(strings[Keys.LANGUAGES_ADVANCED_NOTE]) {
                LanguageLock(preferences) { lock ->
                    scope.launch { DataGraph.themes.updatePreferences { it.copy(languageLock = lock) } }
                }
            }
        }
        val installable = BundledDictionaries.ALL.filter { candidate ->
            packs.none { it.tag.equals(candidate.tag, ignoreCase = true) }
        }
        // Only while a bundled language is not installed.
        if (installable.isNotEmpty()) {
            SettingsSectionCard(strings[Keys.LANGUAGES_INCLUDED_WITH_THE_APP]) {
                Explanation(
                    strings[Keys.LANGUAGES_THESE_ARE_IN_THE_APPLICATION_ITSELF],
                )
                for (candidate in installable) {
                    SettingRow(
                        title = candidate.displayName,
                        subtitle = strings.getString(Keys.LANGUAGES_WORDS_WRITTEN_IN_THIS_REPOSITORY_A, candidate.wordCount),
                        trailing = {
                            TextButton(
                                enabled = !importing,
                                onClick = {
                                    importing = true
                                    message = null
                                    scope.launch {
                                        message = withContext(Dispatchers.IO) {
                                            installBundled(strings, context, repository, candidate)
                                        }
                                        importing = false
                                    }
                                },
                            ) { Text(strings[Keys.LANGUAGES_ADD]) }
                        },
                    )
                }
            }
        }
        // The packs the project publishes beyond the bundled six: fetched in a browser, never
        // by this app, and brought in through the picker below like any other file.
        SettingsSectionCard(strings[Keys.LANGUAGES_MORE_TITLE]) {
            Explanation(strings[Keys.LANGUAGES_MORE_NOTE])
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, PACKS_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(strings[Keys.LANGUAGES_MORE_OPEN]) }
        }
        SettingsSectionCard(strings[Keys.LANGUAGES_IMPORT_YOUR_OWN]) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !importing,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(strings[Keys.LANGUAGES_CHOOSE_A_BKD_FILE]) }
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
                strings[Keys.LANGUAGES_A_PACK_S_HEADER_IS_VALIDATED],
            )
        }
    }
}


/**
 * Copies a chosen file into private storage, validates the copy, and records it or deletes it.
 * Returns the sentence to show under the button.
 */
private suspend fun importPack(
    strings: LanguageManager,
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
        return strings[Keys.LANGUAGES_THE_FILE_COULD_NOT_BE_READ]
    }
    if (staged.isFailure) {
        return strings.getString(Keys.LANGUAGES_IMPORT_FAILED, staged.exceptionOrNull()?.message)
    }
    val pack = staged.getOrThrow()

    return when (val verdict = LanguagePackInspector.inspect(pack.file)) {
        is LanguagePackInspector.Result.Refused -> {
            // A refused file is deleted.
            pack.file.delete()
            strings.getString(Keys.LANGUAGES_REFUSED, strings.getString(verdict.reasonKey, verdict.reasonArgument))
        }

        is LanguagePackInspector.Result.Valid -> {
            val info = verdict.info
            val room = repository.enabledCount() < LanguagePackRepository.MAX_ENABLED
            repository.registerOrReplace(
                LanguagePackEntry(
                    tag = info.tag,
                    displayName = displayNameFor(info.tag),
                    fileName = pack.file.name,
                    formatVersion = info.formatVersion,
                    wordCount = info.wordCount,
                    sizeBytes = pack.sizeBytes,
                    sha256 = pack.sha256,
                    importedAt = System.currentTimeMillis(),
                    enabled = room,
                    weight = 1f,
                    // The licence of an imported pack is not known here.
                    licenseNote = strings[Keys.LANGUAGES_NOT_RECORDED_SET_BY_WHOEVER_BUILT],
                ),
            )
            val added = strings.getString(
                Keys.LANGUAGES_ADDED_WORDS_FOR_2,
                info.wordCount,
                info.tag,
                pack.sha256.take(16),
            )
            if (room) added else added + " " + switchedOffNote(strings)
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
 * Installs a dictionary shipped inside the application, through the same path as a chosen file.
 */
private suspend fun installBundled(
    strings: LanguageManager,
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
        return strings[Keys.LANGUAGES_THE_BUNDLED_DICTIONARY_COULD_NOT_BE]
    }
    val pack = staged.getOrThrow()

    return when (val verdict = LanguagePackInspector.inspect(pack.file)) {
        is LanguagePackInspector.Result.Refused -> {
            pack.file.delete()
            // A shipped pack failing validation is reported as a build problem.
            strings.getString(
                Keys.LANGUAGES_THE_BUNDLED_DICTIONARY_IS_NOT_VALID,
                strings.getString(verdict.reasonKey, verdict.reasonArgument),
            )
        }

        is LanguagePackInspector.Result.Valid -> {
            val info = verdict.info
            val room = repository.enabledCount() < LanguagePackRepository.MAX_ENABLED
            repository.registerOrReplace(
                LanguagePackEntry(
                    tag = info.tag,
                    displayName = displayNameFor(info.tag),
                    fileName = pack.file.name,
                    formatVersion = info.formatVersion,
                    wordCount = info.wordCount,
                    sizeBytes = pack.sizeBytes,
                    sha256 = pack.sha256,
                    importedAt = System.currentTimeMillis(),
                    enabled = room,
                    weight = 1f,
                    // As docs/licensing.md records for the shipped packs: counts from the
                    // Wortschatz Leipzig corpora under CC BY 4.0.
                    licenseNote = strings[Keys.LANGUAGES_CC_BY_LEIPZIG],
                ),
            )
            val added = strings.getString(Keys.LANGUAGES_ADDED_WORDS_FOR, info.wordCount, info.tag)
            if (room) added else added + " " + switchedOffNote(strings)
        }
    }
}

/**
 * What an import says when the pack was recorded but not switched on, because
 * [LanguagePackRepository.MAX_ENABLED] packs were on.
 */
private fun switchedOffNote(strings: LanguageManager): String =
    strings.getString(Keys.LANGUAGES_ADDED_SWITCHED_OFF, LanguagePackRepository.MAX_ENABLED)

/**
 * One installed pack. [canSwitchOn] is false once [LanguagePackRepository.MAX_ENABLED] packs are
 * on. [onPreferred] gets the whole new preference: this pack's tag, or empty when the chip that
 * is on is tapped again.
 */
@Composable
private fun PackRow(
    pack: LanguagePackEntry,
    repository: com.borderkeys.data.LanguagePackRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    canSwitchOn: Boolean,
    preferred: Boolean,
    onPreferred: (String) -> Unit,
) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        SettingRow(
            title = strings.getString(Keys.LANGUAGES_TEXT, pack.displayName, pack.tag),
            subtitle = buildString {
                append(strings.getString(Keys.LANGUAGES_WORDS_KB, pack.wordCount, pack.sizeBytes / 1024))
                append(" · ").append(pack.licenseNote.ifEmpty { strings[Keys.LANGUAGES_LICENCE_NOT_RECORDED] })
                if (pack.integrityFailedAt != null) {
                    append(strings[Keys.LANGUAGES_SWITCHED_ITSELF_OFF_THE_FILE_NO])
                }
            },
            trailing = {
                Switch(
                    checked = pack.enabled,
                    enabled = pack.enabled || canSwitchOn,
                    onCheckedChange = { scope.launch { repository.setEnabled(pack.id, it) } },
                )
            },
        )
        DefaultableSlider(
            label = strings.getString(Keys.LANGUAGES_WEIGHT, "%.2f".format(pack.weight)),
            value = pack.weight.coerceIn(0.05f, 4f),
            range = 0.05f..4f,
            default = 1f,
        ) { value -> scope.launch { repository.setWeight(pack.id, value) } }
        // Only for a pack that is on.
        if (pack.enabled) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                PickerChip(strings[Keys.LANGUAGES_PREFERRED], preferred) {
                    onPreferred(if (preferred) "" else pack.tag)
                }
            }
        }
        var confirmingRemove by remember { mutableStateOf(false) }
        TextButton(
            onClick = { confirmingRemove = true },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text(strings[Keys.LANGUAGES_REMOVE]) }
        if (confirmingRemove) {
            // Asked first: removing deletes the file.
            AlertDialog(
                onDismissRequest = { confirmingRemove = false },
                title = { Text(strings[Keys.LANGUAGES_REMOVE_PACK_TITLE]) },
                text = { Text(strings.getString(Keys.THEME_DELETE_THEME_MESSAGE, pack.displayName)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingRemove = false
                        scope.launch { repository.remove(pack) }
                    }) { Text(strings[Keys.LANGUAGES_REMOVE], color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingRemove = false }) { Text(strings[Keys.THEME_CANCEL]) }
                },
            )
        }
    }
}

/**
 * Which language the interface is written in: "Follow the phone", the default, then each shipped
 * language named in itself.
 */
@Composable
private fun InterfaceLanguage(preferences: KeyboardPreferences, update: (String) -> Unit) {
    val strings = LocalStrings.current
    val available = remember(strings) { strings.availableLanguages() }

    SectionHeader(strings[Keys.LANGUAGES_INTERFACE_LANGUAGE])
    LanguageRow(
        label = strings[Keys.LANGUAGES_FOLLOW_THE_PHONE],
        selected = preferences.uiLanguage.isEmpty(),
    ) { update("") }
    for (code in available) {
        LanguageRow(
            // Named in itself, or by its code when the catalogue returns the key itself.
            label = strings.getString(LANGUAGE_NAME_PREFIX + code)
                .takeIf { it != LANGUAGE_NAME_PREFIX + code } ?: code,
            selected = preferences.uiLanguage == code,
        ) { update(code) }
    }
    Explanation(strings[Keys.LANGUAGES_INTERFACE_EXPLANATION])
    Explanation(strings[Keys.LANGUAGES_RESTART_NOTE])
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** The prefix of a language's catalogue name: `language_name_ro` holds "Română". */
private const val LANGUAGE_NAME_PREFIX = "language_name_"

/** How readily the keyboard stops offering words from the languages you are not writing in. */
@Composable
private fun LanguageLock(preferences: KeyboardPreferences, update: (Int) -> Unit) {
    val strings = LocalStrings.current

    SectionHeader(strings[Keys.LANGUAGES_STICK_TO_ONE_LANGUAGE])
    // Two rows of chips.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerChip(
            strings[Keys.LANGUAGES_LOCK_OFF],
            preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_OFF,
        ) { update(KeyboardPreferences.LANGUAGE_LOCK_OFF) }
        PickerChip(
            strings[Keys.LANGUAGES_LOCK_PATIENT],
            preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_PATIENT,
        ) { update(KeyboardPreferences.LANGUAGE_LOCK_PATIENT) }
        PickerChip(
            strings[Keys.LANGUAGES_LOCK_BALANCED],
            preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
        ) { update(KeyboardPreferences.LANGUAGE_LOCK_BALANCED) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerChip(
            strings[Keys.LANGUAGES_LOCK_QUICK],
            preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_QUICK,
        ) { update(KeyboardPreferences.LANGUAGE_LOCK_QUICK) }
        PickerChip(
            strings[Keys.LANGUAGES_LOCK_STRICT],
            preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_STRICT,
        ) { update(KeyboardPreferences.LANGUAGE_LOCK_STRICT) }
    }
    Explanation(strings[Keys.LANGUAGES_LOCK_EXPLANATION])
    Explanation(
        when (preferences.languageLock) {
            KeyboardPreferences.LANGUAGE_LOCK_OFF -> strings[Keys.LANGUAGES_LOCK_OFF_NOTE]
            KeyboardPreferences.LANGUAGE_LOCK_STRICT -> strings[Keys.LANGUAGES_LOCK_STRICT_NOTE]
            else -> strings[Keys.LANGUAGES_LOCK_EVIDENCE_NOTE]
        },
    )
}

/** The rolling release that carries the packs the project publishes beyond the bundled six. */
private const val PACKS_URL = "https://github.com/razvan-eduard/borderkeys/releases/tag/packs"
