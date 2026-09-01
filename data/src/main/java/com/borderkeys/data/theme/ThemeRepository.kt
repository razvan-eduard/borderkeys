// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
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
    private val preferencesStore: DataStore<KeyboardPreferences>,
) {
    val theme: Flow<KeyboardTheme> = themeStore.data
    val preferences: Flow<KeyboardPreferences> = preferencesStore.data

    suspend fun updateTheme(transform: (KeyboardTheme) -> KeyboardTheme) {
        themeStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        preferencesStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun resetTheme() {
        themeStore.updateData { KeyboardTheme() }
    }

    /**
     * The stored preferences, read on the calling thread.
     *
     * The one sanctioned blocking read, and only for what has to be known before anything is
     * drawn: which language the interface is in. Collecting it as a flow instead would draw the
     * first frame in the wrong language and then correct it, which is a visible flash on every
     * launch. DataStore is a small file and this is a single read at startup, not a pattern to
     * copy -- everything else goes through [preferences] and re-renders when it re-emits.
     */
    fun currentPreferences(): KeyboardPreferences = runBlocking { preferences.first() }
}
