// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The keyboard's appearance and behaviour, as the rest of the application sees them.
 *
 * Exposes flows and suspending updates, and never the [DataStore] itself. That keeps the
 * DataStore and serialization types off the compile classpath of everything that consumes this
 * module, and it enforces the project's rule about state: a caller writes to the store and waits
 * for the flow to re-emit. There is no second copy of the truth held in a `mutableStateOf`
 * somewhere, so what is on screen is always what was written.
 */
class ThemeRepository internal constructor(
    private val themeStore: DataStore<KeyboardTheme>,
    private val lightThemeStore: DataStore<KeyboardTheme>,
    private val preferencesStore: DataStore<KeyboardPreferences>,
    private val customThemeLibraryStore: DataStore<CustomThemeLibrary>,
) {
    val theme: Flow<KeyboardTheme> = themeStore.data

    /** The theme shown instead of [theme] when [KeyboardPreferences.themeMode] is
     *  [KeyboardPreferences.THEME_MODE_AUTO_SYSTEM] and the system is not in dark mode. */
    val lightTheme: Flow<KeyboardTheme> = lightThemeStore.data
    val preferences: Flow<KeyboardPreferences> = preferencesStore.data

    /** Themes the user built and named themselves, newest last -- see [CustomThemeEntry]. */
    val customThemes: Flow<List<CustomThemeEntry>> = customThemeLibraryStore.data.map { it.themes }

    /** [theme], [lightTheme] and [preferences], combined -- see [KeyboardAppearance]. What
     *  anything that draws or previews the keyboard should collect, rather than the three flows
     *  above separately. */
    val appearance: Flow<KeyboardAppearance> =
        combine(theme, lightTheme, preferences, ::KeyboardAppearance)

    suspend fun updateTheme(transform: (KeyboardTheme) -> KeyboardTheme) {
        themeStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun updateLightTheme(transform: (KeyboardTheme) -> KeyboardTheme) {
        lightThemeStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        preferencesStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun resetTheme() {
        themeStore.updateData { KeyboardTheme() }
    }

    /**
     * Saves [theme] under [name] as a new entry, or -- when [id] names an entry that already
     * exists -- overwrites that entry's theme and name in place rather than adding a second one.
     *
     * @param createdAt when the entry was first saved, for a caller that already knows -- a
     *   backup restore, replaying the timestamp the file carries rather than stamping the
     *   moment of the restore itself. Left null for the ordinary "save what I just built" call,
     *   which keeps an overwritten entry's original timestamp and stamps a new one only for a
     *   genuinely new entry.
     * @return the saved entry's id, or null if the library is already at
     *   [CustomThemeLibrary.MAX_CUSTOM_THEMES] and [id] does not match an existing entry.
     */
    suspend fun saveCustomTheme(
        name: String,
        theme: KeyboardTheme,
        id: String = UUID.randomUUID().toString(),
        createdAt: Long? = null,
    ): String? {
        var saved = false
        customThemeLibraryStore.updateData { current ->
            val existingIndex = current.themes.indexOfFirst { it.id == id }
            val entry = CustomThemeEntry(
                id = id,
                name = name,
                theme = theme,
                createdAt = createdAt
                    ?: if (existingIndex >= 0) current.themes[existingIndex].createdAt else System.currentTimeMillis(),
            ).sanitised()
            val updated = when {
                existingIndex >= 0 -> current.themes.toMutableList().also { it[existingIndex] = entry }
                current.themes.size >= CustomThemeLibrary.MAX_CUSTOM_THEMES -> return@updateData current
                else -> current.themes + entry
            }
            saved = true
            current.copy(themes = updated)
        }
        return if (saved) id else null
    }

    suspend fun renameCustomTheme(id: String, name: String) {
        customThemeLibraryStore.updateData { current ->
            current.copy(
                themes = current.themes.map {
                    if (it.id == id) it.copy(name = name).sanitised() else it
                },
            )
        }
    }

    suspend fun deleteCustomTheme(id: String) {
        customThemeLibraryStore.updateData { current ->
            current.copy(themes = current.themes.filterNot { it.id == id })
        }
    }

    fun currentCustomThemes(): List<CustomThemeEntry> = runBlocking { customThemes.first() }

    /** The blocking-read counterpart to [currentPreferences], for the same "must already be
     *  correct on the very first frame" reason. */
    fun currentLightTheme(): KeyboardTheme = runBlocking { lightTheme.first() }

    /** The blocking-read counterpart to [appearance], seeding a screen that shows a preview
     *  before the flow has had a chance to emit. */
    fun currentAppearance(): KeyboardAppearance = runBlocking { appearance.first() }

    /**
     * The stored preferences, read on the calling thread.
     *
     * The sanctioned blocking read, for whatever has to be right on the very first frame drawn.
     * Collecting a preference as a flow instead means that first frame shows this class's own
     * defaults, correcting a moment later once the flow delivers what is actually stored --
     * invisible for most of what a settings screen draws, but not for a `Switch`: Material's own
     * sliding animation plays whenever the value changes, so a preference whose default differs
     * from what the user actually has stored visibly slides from the wrong position to the right
     * one the instant the screen opens. A screen that seeds `collectAsStateWithLifecycle` with
     * this instead of a bare `KeyboardPreferences()` is spared that -- wrapped in `remember` at
     * the call site, so it is read once per composition rather than on every recomposition.
     * DataStore is a small file that is already open and cached well before any settings screen
     * can be reached, so this is one fast, already-resident read, not a disk access -- reserved
     * for exactly this "must already be correct" moment, not a substitute for collecting
     * [preferences] everywhere else.
     */
    fun currentPreferences(): KeyboardPreferences = runBlocking { preferences.first() }
}
