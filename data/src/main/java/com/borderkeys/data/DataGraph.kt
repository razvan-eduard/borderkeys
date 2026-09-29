// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.borderkeys.data.theme.CustomEffectsPresetLibrary
import com.borderkeys.data.theme.CustomEffectsPresetLibrarySerializer
import com.borderkeys.data.theme.CustomThemeLibrary
import com.borderkeys.data.theme.CustomThemeLibrarySerializer
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardPreferencesSerializer
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.KeyboardThemeSerializer
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.data.theme.ParticleEffectsSettingsSerializer
import com.borderkeys.data.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The object graph, wired by hand with `by lazy`. One instance per process: `:assist` has its own
 * copy and its own connection to the database.
 */
object DataGraph {

    @Volatile
    private var context: Context? = null

    /** Safe to call more than once and from more than one entry point; the first call wins. */
    fun install(applicationContext: Context) {
        if (context == null) {
            synchronized(this) {
                if (context == null) {
                    context = applicationContext.applicationContext
                }
            }
        }
    }

    private val requireContext: Context
        get() = checkNotNull(context) {
            "DataGraph.install(context) must be called before anything reads from it"
        }

    /** Scope for the stores' own bookkeeping, living as long as the process; never cancelled. */
    private val storeScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val database: BorderKeysDatabase by lazy { BorderKeysDatabase.open(requireContext) }

    private val themeStore by lazy {
        DataStoreFactory.create(
            serializer = KeyboardThemeSerializer,
            // A file that cannot be parsed is replaced with the defaults.
            corruptionHandler = ReplaceFileCorruptionHandler { KeyboardTheme() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_theme.json") },
        )
    }

    private val preferencesStore by lazy {
        DataStoreFactory.create(
            serializer = KeyboardPreferencesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { KeyboardPreferences() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_preferences.json") },
        )
    }

    /**
     * The theme [KeyboardPreferences.THEME_MODE_AUTO_SYSTEM] shows when the system is not in dark
     * mode, in its own file.
     */
    private val lightThemeStore by lazy {
        DataStoreFactory.create(
            serializer = KeyboardThemeSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { KeyboardTheme() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_theme_light.json") },
        )
    }

    private val customThemeLibraryStore by lazy {
        DataStoreFactory.create(
            serializer = CustomThemeLibrarySerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { CustomThemeLibrary() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_custom_themes.json") },
        )
    }

    /** [ParticleEffectsSettings], in its own file. */
    private val particleEffectsStore by lazy {
        DataStoreFactory.create(
            serializer = ParticleEffectsSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { ParticleEffectsSettings() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_particle_effects.json") },
        )
    }

    /** "My presets" -- see [CustomEffectsPresetLibrary]'s own doc. */
    private val customEffectsPresetLibraryStore by lazy {
        DataStoreFactory.create(
            serializer = CustomEffectsPresetLibrarySerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { CustomEffectsPresetLibrary() },
            scope = storeScope,
            produceFile = { requireContext.dataStoreFile("keyboard_custom_effects_presets.json") },
        )
    }

    val themes: ThemeRepository by lazy {
        ThemeRepository(
            themeStore,
            lightThemeStore,
            preferencesStore,
            customThemeLibraryStore,
            particleEffectsStore,
            customEffectsPresetLibraryStore,
        ).also { repository ->
            // Presets an earlier build saved; see ThemeRepository.importLegacyOutlinePresets.
            // Read off the caller's thread.
            val legacy = requireContext.dataStoreFile("keyboard_custom_outline_presets.json")
            storeScope.launch { repository.importLegacyOutlinePresets(legacy) }
        }
    }

    val clipboard: ClipboardRepository by lazy {
        ClipboardRepository(database.clipboardDao(), themes.preferences)
    }

    val dictionary: DictionaryRepository by lazy {
        DictionaryRepository(
            database,
            database.userWordDao(),
            database.blockedWordDao(),
            database.userBigramDao(),
            database.userTrigramDao(),
            database.keyTouchDao(),
        )
    }

    val languagePacks: LanguagePackRepository by lazy {
        LanguagePackRepository(
            database.languagePackDao(),
            File(requireContext.filesDir, "packs"),
        )
    }

    /** Reading what this keyboard knows out to a file, and back in from one. */
    val backups: com.borderkeys.data.backup.BackupRepository by lazy {
        com.borderkeys.data.backup.BackupRepository(database, themes, dictionary)
    }

    val assistModels: AssistModelRepository by lazy {
        AssistModelRepository(
            database.assistModelDao(),
            File(requireContext.filesDir, "models"),
        )
    }

    /** The directory imported packs live in. Private storage, never a content:// URI. */
    val packsDirectory: File
        get() = File(requireContext.filesDir, "packs")
}
