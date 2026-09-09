// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
) {
    val theme: Flow<KeyboardTheme> = themeStore.data

    /** The theme shown instead of [theme] when [KeyboardPreferences.themeMode] is
     *  [KeyboardPreferences.THEME_MODE_AUTO_SYSTEM] and the system is not in dark mode. */
    val lightTheme: Flow<KeyboardTheme> = lightThemeStore.data
    val preferences: Flow<KeyboardPreferences> = preferencesStore.data

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
