// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeRepositoryCustomThemesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepository(): ThemeRepository {
        val dir = tempFolder.newFolder()
        fun <T> store(serializer: androidx.datastore.core.Serializer<T>, name: String, default: T) =
            DataStoreFactory.create(
                serializer = serializer,
                corruptionHandler = ReplaceFileCorruptionHandler { default },
                produceFile = { File(dir, name) },
            )
        return ThemeRepository(
            themeStore = store(KeyboardThemeSerializer, "theme.json", KeyboardTheme()),
            lightThemeStore = store(KeyboardThemeSerializer, "light_theme.json", KeyboardTheme()),
            preferencesStore = store(KeyboardPreferencesSerializer, "preferences.json", KeyboardPreferences()),
            customThemeLibraryStore = store(CustomThemeLibrarySerializer, "custom_themes.json", CustomThemeLibrary()),
        )
    }

    @Test
    fun `a saved theme is listed under the name it was given`() = runTest {
        val repo = newRepository()
        val id = repo.saveCustomTheme("Sunset Two", KeyboardTheme(accentColor = 0xFF112233.toInt()))
        assertNotNull(id)
        val entry = repo.currentCustomThemes().single()
        assertEquals("Sunset Two", entry.name)
        assertEquals(id, entry.id)
    }

    @Test
    fun `saving under an existing id overwrites rather than duplicating`() = runTest {
        val repo = newRepository()
        val id = requireNotNull(repo.saveCustomTheme("First", KeyboardTheme()))
        repo.saveCustomTheme("First renamed", KeyboardTheme(accentColor = 0xFF000001.toInt()), id = id)
        val themes = repo.currentCustomThemes()
        assertEquals(1, themes.size)
        assertEquals("First renamed", themes.first().name)
    }

    @Test
    fun `rename changes the name and leaves the theme alone`() = runTest {
        val repo = newRepository()
        val theme = KeyboardTheme(accentColor = 0xFF445566.toInt())
        val id = requireNotNull(repo.saveCustomTheme("Before", theme))
        repo.renameCustomTheme(id, "After")
        val entry = repo.currentCustomThemes().single()
        assertEquals("After", entry.name)
        assertEquals(theme, entry.theme)
    }

    @Test
    fun `delete removes exactly that entry`() = runTest {
        val repo = newRepository()
        val keep = requireNotNull(repo.saveCustomTheme("Keep", KeyboardTheme()))
        val gone = requireNotNull(repo.saveCustomTheme("Gone", KeyboardTheme(accentColor = 0xFF010101.toInt())))
        repo.deleteCustomTheme(gone)
        val themes = repo.currentCustomThemes()
        assertEquals(1, themes.size)
        assertEquals(keep, themes.first().id)
    }

    @Test
    fun `the library refuses a new entry past the limit`() = runTest {
        val repo = newRepository()
        repeat(CustomThemeLibrary.MAX_CUSTOM_THEMES) { i ->
            assertNotNull(repo.saveCustomTheme("Theme $i", KeyboardTheme()))
        }
        assertEquals(CustomThemeLibrary.MAX_CUSTOM_THEMES, repo.currentCustomThemes().size)
        assertNull(repo.saveCustomTheme("One too many", KeyboardTheme()))
        assertEquals(CustomThemeLibrary.MAX_CUSTOM_THEMES, repo.currentCustomThemes().size)
    }

    @Test
    fun `overwriting an existing entry is allowed even at the limit`() = runTest {
        val repo = newRepository()
        val firstId = requireNotNull(repo.saveCustomTheme("Theme 0", KeyboardTheme()))
        for (i in 1 until CustomThemeLibrary.MAX_CUSTOM_THEMES) {
            assertNotNull(repo.saveCustomTheme("Theme $i", KeyboardTheme()))
        }
        val overwritten = repo.saveCustomTheme(
            "Renamed",
            KeyboardTheme(accentColor = 0xFF010203.toInt()),
            id = firstId,
        )
        assertEquals(firstId, overwritten)
        assertEquals(CustomThemeLibrary.MAX_CUSTOM_THEMES, repo.currentCustomThemes().size)
    }
}
