// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.DataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The keyboard's appearance and behaviour, as flows and suspending updates; the [DataStore]
 * itself is not exposed. A caller writes and waits for the flow to re-emit.
 */
class ThemeRepository internal constructor(
    private val themeStore: DataStore<KeyboardTheme>,
    private val lightThemeStore: DataStore<KeyboardTheme>,
    private val preferencesStore: DataStore<KeyboardPreferences>,
    private val customThemeLibraryStore: DataStore<CustomThemeLibrary>,
    private val particleEffectsStore: DataStore<ParticleEffectsSettings>,
    private val customEffectsPresetLibraryStore: DataStore<CustomEffectsPresetLibrary>,
    /** The device-protected copy of the appearance, kept in step; none in a test. */
    private val lockedStore: DataStore<LockedAppearance>? = null,
) {
    val theme: Flow<KeyboardTheme> = themeStore.data

    /** The theme shown instead of [theme] when [KeyboardPreferences.themeMode] is
     *  [KeyboardPreferences.THEME_MODE_AUTO_SYSTEM] and the system is not in dark mode. */
    val lightTheme: Flow<KeyboardTheme> = lightThemeStore.data
    val preferences: Flow<KeyboardPreferences> = preferencesStore.data
    val particleEffects: Flow<ParticleEffectsSettings> = particleEffectsStore.data

    /** Themes the user built and named themselves, newest last -- see [CustomThemeEntry]. */
    val customThemes: Flow<List<CustomThemeEntry>> = customThemeLibraryStore.data.map { it.themes }

    /** Whole (outline, fill) looks the user built and named; applying one sets every region. */
    val customEffectsPresets: Flow<List<CustomEffectsPresetEntry>> =
        customEffectsPresetLibraryStore.data.map { it.presets }

    /** [theme], [lightTheme], [preferences] and [particleEffects] as one [KeyboardAppearance]. */
    val appearance: Flow<KeyboardAppearance> =
        combine(theme, lightTheme, preferences, particleEffects, ::KeyboardAppearance)

    suspend fun updateTheme(transform: (KeyboardTheme) -> KeyboardTheme) {
        themeStore.updateData { current -> transform(current).sanitised() }
        mirrorLocked()
    }

    suspend fun updateLightTheme(transform: (KeyboardTheme) -> KeyboardTheme) {
        lightThemeStore.updateData { current -> transform(current).sanitised() }
        mirrorLocked()
    }

    suspend fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        preferencesStore.updateData { current -> transform(current).sanitised() }
        mirrorLocked()
    }

    suspend fun updateParticleEffects(transform: (ParticleEffectsSettings) -> ParticleEffectsSettings) {
        particleEffectsStore.updateData { current -> transform(current).sanitised() }
    }

    suspend fun resetTheme() {
        themeStore.updateData { KeyboardTheme() }
        mirrorLocked()
    }

    /** Writes the two themes and the appearance preferences to the device-protected copy. */
    suspend fun mirrorLocked() {
        val store = lockedStore ?: return
        val mirrored = LockedAppearance.of(theme.first(), lightTheme.first(), preferences.first())
        store.updateData { mirrored }
    }

    /**
     * Saves [theme] under [name] as a new entry, or -- when [id] names an entry that already
     * exists -- overwrites that entry's theme and name in place rather than adding a second one.
     *
     * @param createdAt when the entry was first saved, from a backup restore; null keeps an
     *   overwritten entry's timestamp and stamps a new entry now.
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

    /** See [saveCustomTheme] -- the same shape, for a whole (outline, fill) look. */
    suspend fun saveCustomEffectsPreset(
        name: String,
        outline: ParticleOutlineLayer,
        fill: ParticleFillLayer,
        id: String = UUID.randomUUID().toString(),
        createdAt: Long? = null,
    ): String? {
        var saved = false
        customEffectsPresetLibraryStore.updateData { current ->
            val existingIndex = current.presets.indexOfFirst { it.id == id }
            val entry = CustomEffectsPresetEntry(
                id = id,
                name = name,
                outline = outline,
                fill = fill,
                createdAt = createdAt
                    ?: if (existingIndex >= 0) current.presets[existingIndex].createdAt else System.currentTimeMillis(),
            ).sanitised()
            val updated = when {
                existingIndex >= 0 -> current.presets.toMutableList().also { it[existingIndex] = entry }
                current.presets.size >= CustomEffectsPresetLibrary.MAX_CUSTOM_PRESETS -> return@updateData current
                else -> current.presets + entry
            }
            saved = true
            current.copy(presets = updated)
        }
        return if (saved) id else null
    }

    /**
     * Folds the outline presets an earlier build kept in [file] into "My presets", each with the
     * default fill and its own id and timestamp, then deletes the file.
     */
    suspend fun importLegacyOutlinePresets(file: File) {
        if (!file.isFile) {
            return
        }
        val legacy = runCatching {
            PERSISTED_JSON.decodeFromString(LegacyOutlinePresetLibrary.serializer(), file.readText())
        }.getOrNull()
        if (legacy != null) {
            val known = customEffectsPresetLibraryStore.data.first().presets.map { it.id }.toSet()
            for (preset in legacy.presets) {
                if (preset.id in known || preset.name.isBlank()) {
                    continue
                }
                saveCustomEffectsPreset(
                    name = preset.name,
                    outline = preset.layer,
                    fill = ParticleFillLayer(),
                    id = preset.id,
                    createdAt = preset.createdAt,
                )
            }
        }
        file.delete()
    }

    suspend fun renameCustomEffectsPreset(id: String, name: String) {
        customEffectsPresetLibraryStore.updateData { current ->
            current.copy(
                presets = current.presets.map {
                    if (it.id == id) it.copy(name = name).sanitised() else it
                },
            )
        }
    }

    suspend fun deleteCustomEffectsPreset(id: String) {
        customEffectsPresetLibraryStore.updateData { current ->
            current.copy(presets = current.presets.filterNot { it.id == id })
        }
        // A deleted preset stops being the applied one.
        particleEffectsStore.updateData { current ->
            if (current.appliedPresetId == id) current.copy(appliedPresetId = "") else current
        }
    }

    /** Records [id] as the preset the regions come from, changing nothing else. */
    suspend fun markEffectsPresetApplied(id: String) {
        particleEffectsStore.updateData { current -> current.copy(appliedPresetId = id).sanitised() }
    }

    fun currentCustomThemes(): List<CustomThemeEntry> = runBlocking { customThemes.first() }

    fun currentCustomEffectsPresets(): List<CustomEffectsPresetEntry> = runBlocking { customEffectsPresets.first() }

    /** [lightTheme], read on the calling thread. */
    fun currentLightTheme(): KeyboardTheme = runBlocking { lightTheme.first() }

    /** [particleEffects], read on the calling thread. */
    fun currentParticleEffects(): ParticleEffectsSettings = runBlocking { particleEffects.first() }

    /** [appearance], read on the calling thread. */
    fun currentAppearance(): KeyboardAppearance = runBlocking { appearance.first() }

    /**
     * The stored preferences, read on the calling thread, to seed what must be right on the first
     * frame drawn.
     */
    fun currentPreferences(): KeyboardPreferences = runBlocking { preferences.first() }
}
