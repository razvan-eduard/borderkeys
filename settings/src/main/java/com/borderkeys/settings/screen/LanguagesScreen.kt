// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingsSectionCard
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
    val strings = LocalStrings.current
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
            message = withContext(Dispatchers.IO) { importPack(strings, context, repository, uri) }
            importing = false
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InterfaceLanguage(preferences) { code ->
            scope.launch { DataGraph.themes.updatePreferences { it.copy(uiLanguage = code) } }
        }
        LanguageLock(preferences) { lock ->
            scope.launch { DataGraph.themes.updatePreferences { it.copy(languageLock = lock) } }
        }

        SettingsSectionCard(strings[Keys.LANGUAGES_INSTALLED_PACKS]) {
            if (packs.isEmpty()) {
                SettingRow(
                    title = strings[Keys.LANGUAGES_NONE_YET],
                    subtitle = strings[Keys.LANGUAGES_NOTHING_IS_DOWNLOADED_EVER_ADD_ONE],
                )
            }
            for (pack in packs) {
                PackRow(pack, repository, scope)
            }
            val installable = BundledDictionaries.ALL.filter { candidate ->
                packs.none { it.tag.equals(candidate.tag, ignoreCase = true) }
            }
            if (installable.isNotEmpty()) {
        }
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
        SettingsSectionCard(strings[Keys.LANGUAGES_PER_APP_LANGUAGE_MEMORY]) {
            SwitchRow(
                title = strings[Keys.LANGUAGES_REMEMBER_WHICH_LANGUAGES_YOU_USE_IN],
                subtitle = strings[Keys.LANGUAGES_OFF_BY_DEFAULT_IT_STORES_A],
                checked = preferences.perAppLanguageMemory,
            ) { value ->
                scope.launch { themes.updatePreferences { it.copy(perAppLanguageMemory = value) } }
            }
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
            // Refused means refused: the file goes, so a pack that cannot be read cannot sit in
            // private storage taking up space and waiting to be tried again.
            pack.file.delete()
            strings.getString(Keys.LANGUAGES_REFUSED, strings.getString(verdict.reasonKey, verdict.reasonArgument))
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
                    licenseNote = strings[Keys.LANGUAGES_NOT_RECORDED_SET_BY_WHOEVER_BUILT],
                ),
            )
            strings.getString(
                Keys.LANGUAGES_ADDED_WORDS_FOR_2,
                info.wordCount,
                info.tag,
                pack.sha256.take(16),
            )
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
            // A pack this application compiled itself failing its own validator is a build
            // problem, not a user problem, and saying so is more use than "import failed".
            strings.getString(
                Keys.LANGUAGES_THE_BUNDLED_DICTIONARY_IS_NOT_VALID,
                strings.getString(verdict.reasonKey, verdict.reasonArgument),
            )
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
                    licenseNote = strings[Keys.LANGUAGES_GPL_3_0_OR_LATER_THE],
                ),
            )
            strings.getString(Keys.LANGUAGES_ADDED_WORDS_FOR, info.wordCount, info.tag)
        }
    }
}

@Composable
private fun PackRow(
    pack: LanguagePackEntry,
    repository: com.borderkeys.data.LanguagePackRepository,
    scope: kotlinx.coroutines.CoroutineScope,
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
                    onCheckedChange = { scope.launch { repository.setEnabled(pack.id, it) } },
                )
            },
        )
        Text(
            strings.getString(Keys.LANGUAGES_WEIGHT, "%.2f".format(pack.weight)),
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
        ) { Text(strings[Keys.LANGUAGES_REMOVE]) }
    }
}

/**
 * Which language the interface is written in.
 *
 * Offered as a list of what is actually shipped rather than of every language that exists, and
 * each is named in itself -- someone looking for Romanian is looking for "Română", not for
 * whatever English calls it. "Follow the phone" is first and is the default, because a phone
 * later switched to a language BorderKeys ships should pick it up without anyone coming back
 * here.
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
            // Named in itself where the catalogue says so, and by its code where it does not.
            // A missing entry comes back as its own key, which is the loader's way of making a
            // gap visible -- useful on a settings row, useless on a list someone has to choose
            // from, so here it becomes the code instead.
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

/** `language_name_ro` holds "Română". Built from the code so adding a language adds no code. */
private const val LANGUAGE_NAME_PREFIX = "language_name_"

/**
 * How readily the keyboard stops offering words from the languages you are not writing in.
 *
 * A choice rather than a constant because the right answer depends on how someone writes, and
 * the two ends are both reasonable: one person writes one language at a time and wants the
 * others out of the way; another writes two in the same sentence and would be actively harmed
 * by the keyboard picking a side.
 */
@Composable
private fun LanguageLock(preferences: KeyboardPreferences, update: (Int) -> Unit) {
    val strings = LocalStrings.current

    SectionHeader(strings[Keys.LANGUAGES_STICK_TO_ONE_LANGUAGE])
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LockChip(strings[Keys.LANGUAGES_LOCK_OFF], KeyboardPreferences.LANGUAGE_LOCK_OFF,
            preferences, update)
        LockChip(strings[Keys.LANGUAGES_LOCK_PATIENT], KeyboardPreferences.LANGUAGE_LOCK_PATIENT,
            preferences, update)
        LockChip(strings[Keys.LANGUAGES_LOCK_BALANCED], KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
            preferences, update)
        LockChip(strings[Keys.LANGUAGES_LOCK_QUICK], KeyboardPreferences.LANGUAGE_LOCK_QUICK,
            preferences, update)
    }
    Explanation(strings[Keys.LANGUAGES_LOCK_EXPLANATION])
    Explanation(
        if (preferences.languageLock == KeyboardPreferences.LANGUAGE_LOCK_OFF) {
            strings[Keys.LANGUAGES_LOCK_OFF_NOTE]
        } else {
            strings[Keys.LANGUAGES_LOCK_EVIDENCE_NOTE]
        },
    )
}

@Composable
private fun LockChip(
    label: String,
    lock: Int,
    preferences: KeyboardPreferences,
    update: (Int) -> Unit,
) {
    FilterChip(
        selected = preferences.languageLock == lock,
        onClick = { update(lock) },
        label = { Text(label) },
    )
}
