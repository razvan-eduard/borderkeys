// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The appearance kept for a start before the first unlock, and what is left out of it. */
class LockedAppearanceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val appearance = KeyboardPreferences(
        themeMode = KeyboardPreferences.THEME_MODE_AUTO_SYSTEM,
        followSystemColors = true,
        heightScale = 0.9f,
        widthScale = 0.8f,
        positionMode = KeyboardPreferences.MODE_FLOATING,
        bottomOffsetDp = 20f,
        horizontalOffsetDp = 10f,
        landscape = KeyboardPlacement(heightScale = 0.6f),
        numberRow = true,
        hapticFeedback = false,
        hapticStrength = KeyboardPreferences.HAPTIC_STRONG,
        hapticKeys = false,
        hapticSuggestions = false,
        hapticRing = false,
        keySound = true,
        keyPopup = false,
        longPressMillis = 400,
        uiLanguage = "ro",
    )

    @Test
    fun `the locked start keeps the appearance and layout fields and nothing personal`() {
        val everything = appearance.copy(
            emojiRecents = listOf("x"),
            clipboardExcludedPackages = listOf("com.example.vault"),
            terminalPackages = listOf("com.example.term"),
            preferredLanguageTag = "ro-RO",
            learningEnabled = false,
            blockOffensiveWords = true,
            quickActionsEnabled = true,
            clipboardSuggestion = true,
            composerEnabled = false,
            assistWriteModel = "model.gguf",
            autoCorrectOnSpace = true,
        )
        assertEquals(appearance, everything.forLockedStart())
    }

    @Test
    fun `a write to the settings is mirrored to the locked copy, appearance only`() = runTest {
        val dir = tempFolder.newFolder()
        fun <T> store(serializer: androidx.datastore.core.Serializer<T>, name: String, default: T) =
            DataStoreFactory.create(
                serializer = serializer,
                corruptionHandler = ReplaceFileCorruptionHandler { default },
                produceFile = { File(dir, name) },
            )
        val locked = store(LockedAppearanceSerializer, "locked.json", LockedAppearance())
        val repository = ThemeRepository(
            themeStore = store(KeyboardThemeSerializer, "theme.json", KeyboardTheme()),
            lightThemeStore = store(KeyboardThemeSerializer, "light_theme.json", KeyboardTheme()),
            preferencesStore = store(KeyboardPreferencesSerializer, "preferences.json", KeyboardPreferences()),
            customThemeLibraryStore = store(CustomThemeLibrarySerializer, "custom_themes.json", CustomThemeLibrary()),
            particleEffectsStore = store(
                ParticleEffectsSettingsSerializer, "particle_effects.json", ParticleEffectsSettings(),
            ),
            customEffectsPresetLibraryStore = store(
                CustomEffectsPresetLibrarySerializer, "custom_effects_presets.json", CustomEffectsPresetLibrary(),
            ),
            lockedStore = locked,
        )

        repository.updatePreferences { it.copy(numberRow = true, preferredLanguageTag = "ro-RO") }
        val afterPreferences = locked.data.first()
        assertTrue(afterPreferences.preferences.numberRow)
        assertEquals("", afterPreferences.preferences.preferredLanguageTag)

        repository.updateTheme { it.copy(accentColor = 0xFF112233.toInt()) }
        assertEquals(0xFF112233.toInt(), locked.data.first().theme.accentColor)

        repository.updateLightTheme { it.copy(accentColor = 0xFF445566.toInt()) }
        assertEquals(0xFF445566.toInt(), locked.data.first().lightTheme.accentColor)
    }
}
