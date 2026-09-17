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

/** "My presets" -- [ThemeRepositoryCustomThemesTest]'s own shape, one level down, for a whole
 *  (outline, fill) look rather than a whole [KeyboardTheme]. */
class ThemeRepositoryCustomParticlePresetsTest {

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
            particleEffectsStore = store(
                ParticleEffectsSettingsSerializer, "particle_effects.json", ParticleEffectsSettings(),
            ),
            customEffectsPresetLibraryStore = store(
                CustomEffectsPresetLibrarySerializer, "custom_effects_presets.json", CustomEffectsPresetLibrary(),
            ),
        )
    }

    @Test
    fun `presets an earlier build saved as outline-only are imported once, with a default fill`() = runTest {
        val repo = newRepository()
        val legacy = File(tempFolder.newFolder(), "keyboard_custom_outline_presets.json")
        legacy.writeText(
            """{"presets":[{"id":"abc","name":"MyComet","layer":{"type":1,"primaryColor":-26624,""" +
                """"secondaryColor":-9524994,"speed":1.0,"density":1.0,"width":1.0},"createdAt":1789579049613}]}""",
        )
        repo.importLegacyOutlinePresets(legacy)
        val entry = repo.currentCustomEffectsPresets().single()
        assertEquals("MyComet", entry.name)
        assertEquals("abc", entry.id)
        assertEquals(1789579049613L, entry.createdAt)
        assertEquals(ParticleEffectsSettings.OUTLINE_COMET, entry.outline.type)
        assertEquals(-26624, entry.outline.primaryColor)
        assertEquals(ParticleFillLayer(), entry.fill)
        assertEquals(false, legacy.exists())
        // A second import of the same file would find it gone; a second copy with the same id
        // is not added again either.
        legacy.writeText("""{"presets":[{"id":"abc","name":"Renamed","layer":{"type":2}}]}""")
        repo.importLegacyOutlinePresets(legacy)
        assertEquals(1, repo.currentCustomEffectsPresets().size)
        assertEquals("MyComet", repo.currentCustomEffectsPresets().single().name)
    }

    @Test
    fun `applying a saved preset records it, and deleting it clears the record`() = runTest {
        val repo = newRepository()
        val outline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_WIND)
        val fill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_NEON)
        val id = repo.saveCustomEffectsPreset("Storm", outline, fill)!!
        val entry = repo.currentCustomEffectsPresets().single()
        repo.updateParticleEffects { entry.appliedTo(it) }
        val applied = repo.currentParticleEffects()
        assertEquals(id, applied.appliedPresetId)
        assertEquals(true, applied.radial.enabled)
        assertEquals(outline, applied.radial.outline)
        assertEquals(fill, applied.strip.fill)
        assertEquals(true, entry.matches(applied))
        // A region that drifted from the pair no longer matches -- the comparison used to read
        // each region against itself and say yes to everything.
        val drifted = applied.copy(strip = applied.strip.copy(fill = fill.copy(speed = 0.5f)))
        assertEquals(false, entry.matches(drifted))
        assertEquals(false, entry.matches(ParticleEffectsSettings()))
        repo.markEffectsPresetApplied("fire")
        assertEquals("fire", repo.currentParticleEffects().appliedPresetId)
        repo.markEffectsPresetApplied(id)
        repo.deleteCustomEffectsPreset(id)
        assertEquals("", repo.currentParticleEffects().appliedPresetId)
    }

    @Test
    fun `a saved preset is listed under the name it was given, both layers unchanged`() = runTest {
        val repo = newRepository()
        val outline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_COMET, width = 1.6f)
        val fill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_FIRE, speed = 1.7f)
        val id = repo.saveCustomEffectsPreset("Sunset", outline, fill)
        assertNotNull(id)
        val entry = repo.currentCustomEffectsPresets().single()
        assertEquals("Sunset", entry.name)
        assertEquals(id, entry.id)
        assertEquals(outline, entry.outline)
        assertEquals(fill, entry.fill)
    }

    @Test
    fun `saving under an existing id overwrites rather than duplicating`() = runTest {
        val repo = newRepository()
        val id = requireNotNull(repo.saveCustomEffectsPreset("First", ParticleOutlineLayer(), ParticleFillLayer()))
        val newFill = ParticleFillLayer(speed = 1.9f)
        repo.saveCustomEffectsPreset("First renamed", ParticleOutlineLayer(), newFill, id = id)
        val presets = repo.currentCustomEffectsPresets()
        assertEquals(1, presets.size)
        assertEquals("First renamed", presets.first().name)
        assertEquals(1.9f, presets.first().fill.speed, 0.001f)
    }

    @Test
    fun `rename changes the name and leaves both layers alone`() = runTest {
        val repo = newRepository()
        val outline = ParticleOutlineLayer(type = ParticleEffectsSettings.OUTLINE_SPARKLE)
        val fill = ParticleFillLayer(type = ParticleEffectsSettings.FILL_WAVES)
        val id = requireNotNull(repo.saveCustomEffectsPreset("Before", outline, fill))
        repo.renameCustomEffectsPreset(id, "After")
        val entry = repo.currentCustomEffectsPresets().single()
        assertEquals("After", entry.name)
        assertEquals(outline, entry.outline)
        assertEquals(fill, entry.fill)
    }

    @Test
    fun `delete removes exactly that preset`() = runTest {
        val repo = newRepository()
        val keep = requireNotNull(
            repo.saveCustomEffectsPreset("Keep", ParticleOutlineLayer(), ParticleFillLayer()),
        )
        val gone = requireNotNull(
            repo.saveCustomEffectsPreset("Gone", ParticleOutlineLayer(), ParticleFillLayer(speed = 1.5f)),
        )
        repo.deleteCustomEffectsPreset(gone)
        val presets = repo.currentCustomEffectsPresets()
        assertEquals(1, presets.size)
        assertEquals(keep, presets.first().id)
    }

    @Test
    fun `the library refuses a new entry past the limit`() = runTest {
        val repo = newRepository()
        repeat(CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS) { i ->
            assertNotNull(repo.saveCustomEffectsPreset("Preset $i", ParticleOutlineLayer(), ParticleFillLayer()))
        }
        assertEquals(CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS, repo.currentCustomEffectsPresets().size)
        assertNull(repo.saveCustomEffectsPreset("One too many", ParticleOutlineLayer(), ParticleFillLayer()))
        assertEquals(CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS, repo.currentCustomEffectsPresets().size)
    }

    @Test
    fun `overwriting an existing entry is allowed even at the limit`() = runTest {
        val repo = newRepository()
        val firstId = requireNotNull(
            repo.saveCustomEffectsPreset("Preset 0", ParticleOutlineLayer(), ParticleFillLayer()),
        )
        for (i in 1 until CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS) {
            assertNotNull(repo.saveCustomEffectsPreset("Preset $i", ParticleOutlineLayer(), ParticleFillLayer()))
        }
        val overwritten = repo.saveCustomEffectsPreset(
            "Renamed",
            ParticleOutlineLayer(),
            ParticleFillLayer(speed = 1.3f),
            id = firstId,
        )
        assertEquals(firstId, overwritten)
        assertEquals(CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS, repo.currentCustomEffectsPresets().size)
    }
}
