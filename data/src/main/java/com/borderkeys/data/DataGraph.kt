// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardPreferencesSerializer
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.KeyboardThemeSerializer
import com.borderkeys.data.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Object graph, wired by hand.
 *
 * No Hilt, no Dagger, no Koin. What this application needs is one database, two data stores and
 * four repositories, all of them process-scoped singletons -- and `by lazy` already does that,
 * with no annotation processor, no generated code, no reflection and nothing on the classpath.
 * A container earns its keep when the graph is large enough to be hard to read; this one fits on
 * a screen, so it is written on that screen.
 *
 * **One instance per process.** The text assistant runs in `:assist`, which means it has its own
 * copy of everything below and its own connection to the database. That is why the database is
 * opened with multi-instance invalidation: without it, a write from one process would leave the
 * other's flows serving stale rows until something restarted.
 */
object DataGraph {

    @Volatile
    private var context: Context? = null

    /**
     * Safe to call more than once and from more than one entry point -- the IME service and the
     * settings activity both do, and whichever runs first wins.
     */
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

    /**
     * Scope for the stores' own bookkeeping. Deliberately never cancelled: it lives as long as
     * the process, and a data store whose scope was cancelled would fail every subsequent read
     * with no way to recover short of restarting.
     */
    private val storeScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val database: BorderKeysDatabase by lazy { BorderKeysDatabase.open(requireContext) }

    private val themeStore by lazy {
        DataStoreFactory.create(
            serializer = KeyboardThemeSerializer,
            // A file that cannot be parsed is replaced with the defaults instead of throwing at
            // every collector. The alternative is a keyboard that will not draw because of a
            // colour, and no way to reach the settings screen that would fix it.
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

    val themes: ThemeRepository by lazy { ThemeRepository(themeStore, preferencesStore) }

    val clipboard: ClipboardRepository by lazy {
        ClipboardRepository(database.clipboardDao(), themes.preferences)
    }

    val dictionary: DictionaryRepository by lazy {
        DictionaryRepository(database.userWordDao(), database.blockedWordDao())
    }

    val languagePacks: LanguagePackRepository by lazy {
        LanguagePackRepository(
            database.languagePackDao(),
            File(requireContext.filesDir, "packs"),
        )
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
