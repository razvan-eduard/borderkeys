// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A region's fill layer: particles inside it. [type] is one of
 * [ParticleEffectsSettings.FILL_NONE]..[ParticleEffectsSettings.FILL_NEON]; the colours default to
 * [type]'s own pair.
 */
@Serializable
data class ParticleFillLayer(
    val type: Int = ParticleEffectsSettings.FILL_GLOW,
    val primaryColor: Int = defaultFillPrimaryColor(type),
    val secondaryColor: Int = defaultFillSecondaryColor(type),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
) {
    fun sanitised(): ParticleFillLayer = copy(
        type = if (type in ParticleEffectsSettings.FILL_NONE..ParticleEffectsSettings.FILL_NEON) {
            type
        } else {
            ParticleEffectsSettings.FILL_GLOW
        },
        speed = speed.coerceIn(ParticleEffectsSettings.MIN_SPEED, ParticleEffectsSettings.MAX_SPEED),
        density = density.coerceIn(ParticleEffectsSettings.MIN_DENSITY, ParticleEffectsSettings.MAX_DENSITY),
    )
}

/**
 * A region's outline layer: particles tracing its border or arc, styled by
 * [com.borderkeys.ime.fx.ParticleOutlineStylePresets]. [type] is one of
 * [ParticleEffectsSettings.OUTLINE_NONE]..[ParticleEffectsSettings.OUTLINE_WIND]; the colours
 * default to [type]'s own pair.
 */
@Serializable
data class ParticleOutlineLayer(
    val type: Int = ParticleEffectsSettings.OUTLINE_NONE,
    val primaryColor: Int = defaultOutlinePrimaryColor(type),
    val secondaryColor: Int = defaultOutlineSecondaryColor(type),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
    val width: Float = ParticleEffectsSettings.DEFAULT_WIDTH,
) {
    fun sanitised(): ParticleOutlineLayer = copy(
        type = if (type in ParticleEffectsSettings.OUTLINE_NONE..ParticleEffectsSettings.OUTLINE_WIND) {
            type
        } else {
            ParticleEffectsSettings.OUTLINE_NONE
        },
        speed = speed.coerceIn(ParticleEffectsSettings.MIN_SPEED, ParticleEffectsSettings.MAX_SPEED),
        density = density.coerceIn(ParticleEffectsSettings.MIN_DENSITY, ParticleEffectsSettings.MAX_DENSITY),
        width = width.coerceIn(ParticleEffectsSettings.MIN_WIDTH, ParticleEffectsSettings.MAX_WIDTH),
    )
}

/** A fill type's default primary colour, the young particle's; Glow's for anything else. */
private fun defaultFillPrimaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.FILL_FIRE -> 0xFFFFC107.toInt()
    ParticleEffectsSettings.FILL_WAVES -> 0xFF4FC3F7.toInt()
    ParticleEffectsSettings.FILL_RAINBOW -> 0xFFEC407A.toInt()
    ParticleEffectsSettings.FILL_NEON -> 0xFFFF4081.toInt()
    else -> 0xFFFF9800.toInt()
}

/**
 * A fill type's default secondary colour. Rainbow's pair and Neon's secondary are shown by the
 * picker only; nothing draws with them.
 */
private fun defaultFillSecondaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.FILL_FIRE -> 0xFFBF360C.toInt()
    ParticleEffectsSettings.FILL_WAVES -> 0xFF01579B.toInt()
    ParticleEffectsSettings.FILL_RAINBOW -> 0xFF7E57C2.toInt()
    ParticleEffectsSettings.FILL_NEON -> 0xFF18FFFF.toInt()
    else -> 0xFF6EA8FE.toInt()
}

/** An outline type's default primary colour; the plain default pair for anything else. */
private fun defaultOutlinePrimaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.OUTLINE_COMET -> 0xFFE1F5FE.toInt()
    ParticleEffectsSettings.OUTLINE_PULSE -> 0xFF7C4DFF.toInt()
    ParticleEffectsSettings.OUTLINE_SPARKLE -> 0xFFFFF59D.toInt()
    ParticleEffectsSettings.OUTLINE_FIRE -> 0xFFFF5722.toInt()
    ParticleEffectsSettings.OUTLINE_WIND -> 0xFFB3E5FC.toInt()
    else -> 0xFFFF9800.toInt()
}

/**
 * An outline type's default secondary colour. Pulse's and Sparkle's are shown by the picker only;
 * nothing draws with them.
 */
private fun defaultOutlineSecondaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.OUTLINE_COMET -> 0xFF0277BD.toInt()
    ParticleEffectsSettings.OUTLINE_PULSE -> 0xFFB388FF.toInt()
    ParticleEffectsSettings.OUTLINE_SPARKLE -> 0xFFFFFFFF.toInt()
    ParticleEffectsSettings.OUTLINE_FIRE -> 0xFFBF360C.toInt()
    ParticleEffectsSettings.OUTLINE_WIND -> 0xFFFFFFFF.toInt()
    else -> 0xFF6EA8FE.toInt()
}

/** Whether this layer equals a fresh layer of its own [ParticleFillLayer.type]. */
fun ParticleFillLayer.matchesPreset(): Boolean = this == ParticleFillLayer(type = type)

fun ParticleOutlineLayer.matchesPreset(): Boolean = this == ParticleOutlineLayer(type = type)

/** This layer switched to [type] with that type's colours, keeping [speed] and [density]. */
fun ParticleFillLayer.withPresetType(type: Int): ParticleFillLayer =
    ParticleFillLayer(type = type).copy(speed = speed, density = density)

/** This layer switched to [type] with that type's colours, keeping speed, density and width. */
fun ParticleOutlineLayer.withPresetType(type: Int): ParticleOutlineLayer =
    ParticleOutlineLayer(type = type).copy(speed = speed, density = density, width = width)

/** One region's particle configuration: its own on/off switch and its two layers. */
@Serializable
data class ParticleRegionSettings(
    val enabled: Boolean = false,
    val outline: ParticleOutlineLayer = ParticleOutlineLayer(),
    val fill: ParticleFillLayer = ParticleFillLayer(),
) {
    fun sanitised(): ParticleRegionSettings = copy(outline = outline.sanitised(), fill = fill.sanitised())
}

/** Particle effects for every surface that has them, in their own store. */
@Serializable
data class ParticleEffectsSettings(
    val keyboard: ParticleRegionSettings = ParticleRegionSettings(),
    val radial: ParticleRegionSettings = ParticleRegionSettings(),
    val strip: ParticleRegionSettings = ParticleRegionSettings(),
    val languageRevert: ParticleRegionSettings = ParticleRegionSettings(),
    val quickActions: ParticleRegionSettings = ParticleRegionSettings(),
    /**
     * The preset applied last, a [BuiltInEffectsPreset.id] or a [CustomEffectsPresetEntry.id], or
     * empty. Written with the regions it was applied to. A deleted preset matches nothing.
     */
    val appliedPresetId: String = "",
) {
    /** Clamps every field; applied on read. */
    fun sanitised(): ParticleEffectsSettings = copy(
        keyboard = keyboard.sanitised(),
        radial = radial.sanitised(),
        strip = strip.sanitised(),
        languageRevert = languageRevert.sanitised(),
        quickActions = quickActions.sanitised(),
        appliedPresetId = appliedPresetId.take(MAX_PRESET_ID_LENGTH),
    )

    companion object {
        /**
         * [ParticleFillLayer.type] values, in the same order as
         * [com.borderkeys.ime.fx.ParticleEffectPresets.forSetting].
         */
        const val FILL_NONE = 0
        const val FILL_FIRE = 1
        const val FILL_GLOW = 2
        const val FILL_WAVES = 3
        const val FILL_RAINBOW = 4
        const val FILL_NEON = 5

        /**
         * [ParticleOutlineLayer.type] values, in the same order as
         * [com.borderkeys.ime.fx.ParticleOutlineStylePresets.forSetting].
         */
        const val OUTLINE_NONE = 0
        const val OUTLINE_COMET = 1
        const val OUTLINE_PULSE = 2
        const val OUTLINE_SPARKLE = 3

        /** A stroke along the region's shape with embers rising off it. */
        const val OUTLINE_FIRE = 4

        /** A stroke along the region's shape with particles blown sideways off it. */
        const val OUTLINE_WIND = 5

        /** The longest [appliedPresetId] accepted. */
        const val MAX_PRESET_ID_LENGTH = 64

        /** The range every layer's speed is clamped to. */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
        const val DEFAULT_SPEED = 1f

        /** The range every layer's density is clamped to. */
        const val MIN_DENSITY = 0.5f
        const val MAX_DENSITY = 2f
        const val DEFAULT_DENSITY = 1f

        /** The range [ParticleOutlineLayer.width] is clamped to. */
        const val MIN_WIDTH = 0.5f
        const val MAX_WIDTH = 2f
        const val DEFAULT_WIDTH = 1f
    }
}
