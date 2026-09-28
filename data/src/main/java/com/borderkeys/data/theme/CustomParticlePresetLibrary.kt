// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * One look someone saved under a name they chose: an [outline] and a [fill], applied to every
 * region at once.
 */
@Serializable
data class CustomEffectsPresetEntry(
    val id: String,
    val name: String,
    val outline: ParticleOutlineLayer,
    val fill: ParticleFillLayer,
    val createdAt: Long = 0L,
) {
    /** Read-time repair, the same reasoning as [CustomThemeEntry.sanitised]. */
    fun sanitised(): CustomEffectsPresetEntry = copy(
        name = name.take(MAX_NAME_LENGTH),
        outline = outline.sanitised(),
        fill = fill.sanitised(),
    )

    /** Whether every region in [settings] still shows exactly this pair. */
    fun matches(settings: ParticleEffectsSettings): Boolean {
        // Locals: inside the extension below, `outline` and `fill` name the region's.
        val presetOutline = outline
        val presetFill = fill
        fun ParticleRegionSettings.matchesThis() = this.outline == presetOutline && this.fill == presetFill
        return settings.keyboard.matchesThis() &&
            settings.radial.matchesThis() &&
            settings.strip.matchesThis() &&
            settings.languageRevert.matchesThis() &&
            settings.quickActions.matchesThis()
    }

    /**
     * [settings] with this pair written to every region, each switched on, and this entry recorded
     * as the applied preset.
     */
    fun appliedTo(settings: ParticleEffectsSettings): ParticleEffectsSettings {
        fun apply(region: ParticleRegionSettings) = region.copy(enabled = true, outline = outline, fill = fill)
        return settings.copy(
            keyboard = apply(settings.keyboard),
            radial = apply(settings.radial),
            strip = apply(settings.strip),
            languageRevert = apply(settings.languageRevert),
            quickActions = apply(settings.quickActions),
            appliedPresetId = id,
        )
    }

    companion object {
        /** Long enough for a real name, short enough a chip never has to wrap it. */
        const val MAX_NAME_LENGTH = 40
    }
}

/** The whole saved collection, as one DataStore file. */
@Serializable
data class CustomEffectsPresetLibrary(val presets: List<CustomEffectsPresetEntry> = emptyList()) {
    fun sanitised(): CustomEffectsPresetLibrary = copy(
        presets = presets.map { it.sanitised() }.take(MAX_CUSTOM_PRESETS),
    )

    companion object {
        /** Same reasoning as [CustomThemeLibrary.MAX_CUSTOM_THEMES]. */
        const val MAX_CUSTOM_PRESETS = 50
    }
}

/**
 * The shape an earlier build wrote to `keyboard_custom_outline_presets.json`, read once by
 * [ThemeRepository.importLegacyOutlinePresets].
 */
@Serializable
internal data class LegacyOutlinePreset(
    val id: String,
    val name: String,
    val layer: ParticleOutlineLayer,
    val createdAt: Long = 0L,
)

@Serializable
internal data class LegacyOutlinePresetLibrary(val presets: List<LegacyOutlinePreset> = emptyList())
