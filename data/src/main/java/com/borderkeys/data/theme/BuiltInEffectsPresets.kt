// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/** One region's (outline, fill) pair in a [BuiltInEffectsPreset]. */
data class ParticleRegionLook(val outline: ParticleOutlineLayer, val fill: ParticleFillLayer) {
    fun appliedTo(region: ParticleRegionSettings, enabled: Boolean = true): ParticleRegionSettings =
        region.copy(enabled = enabled, outline = outline, fill = fill)

    fun matches(region: ParticleRegionSettings, enabled: Boolean = true): Boolean =
        region.enabled == enabled && region.outline == outline && region.fill == fill
}

/**
 * A whole-keyboard look that ships with the app: an (outline, fill) pair per region, each with its
 * own speed, density and width. [id] is a stable slug the caller maps to a translated name.
 * [enabled] is written to every region's switch; only [BuiltInEffectsPresets.OFF] has it false.
 */
data class BuiltInEffectsPreset(
    val id: String,
    val keyboard: ParticleRegionLook,
    val radial: ParticleRegionLook,
    val strip: ParticleRegionLook,
    val languageRevert: ParticleRegionLook,
    val quickActions: ParticleRegionLook,
    val enabled: Boolean = true,
) {
    /**
     * [settings] with the five looks written to their regions and this preset recorded as
     * [ParticleEffectsSettings.appliedPresetId].
     */
    fun appliedTo(settings: ParticleEffectsSettings): ParticleEffectsSettings = settings.copy(
        keyboard = keyboard.appliedTo(settings.keyboard, enabled),
        radial = radial.appliedTo(settings.radial, enabled),
        strip = strip.appliedTo(settings.strip, enabled),
        languageRevert = languageRevert.appliedTo(settings.languageRevert, enabled),
        quickActions = quickActions.appliedTo(settings.quickActions, enabled),
        appliedPresetId = id,
    )

    fun matches(settings: ParticleEffectsSettings): Boolean =
        keyboard.matches(settings.keyboard, enabled) &&
            radial.matches(settings.radial, enabled) &&
            strip.matches(settings.strip, enabled) &&
            languageRevert.matches(settings.languageRevert, enabled) &&
            quickActions.matches(settings.quickActions, enabled)

    /** The look this preset gives the region [key] ("keyboard", "radial", "strip",
     *  "language_revert", "quick_actions" -- the settings screen's own region keys). */
    fun lookFor(key: String): ParticleRegionLook = when (key) {
        "keyboard" -> keyboard
        "radial" -> radial
        "strip" -> strip
        "language_revert" -> languageRevert
        else -> quickActions
    }
}

/** The looks "My presets" ships with; every colour is one of [ThemePalette.COLOURS]. */
object BuiltInEffectsPresets {

    private val BLUE_SOFT = ThemePalette.COLOURS[0]
    private val BLUE = ThemePalette.COLOURS[1]
    private val BLUE_DARK = ThemePalette.COLOURS[2]
    private val TEAL = ThemePalette.COLOURS[3]
    private val GREEN_EMERALD = ThemePalette.COLOURS[4]
    private val GREEN_LIGHT = ThemePalette.COLOURS[5]
    private val AMBER = ThemePalette.COLOURS[6]
    private val ORANGE = ThemePalette.COLOURS[7]
    private val RED = ThemePalette.COLOURS[8]
    private val PINK = ThemePalette.COLOURS[9]
    private val PURPLE = ThemePalette.COLOURS[10]
    private val BLUE_LAVENDER = ThemePalette.COLOURS[11]
    private val BLACK = ThemePalette.COLOURS[12]
    private val GREY_DARK = ThemePalette.COLOURS[13]
    private val GREY_LIGHT = ThemePalette.COLOURS[14]
    private val WHITE = ThemePalette.COLOURS[15]

    private fun outline(
        type: Int,
        primary: Int,
        secondary: Int,
        speed: Float,
        density: Float,
        width: Float,
    ) = ParticleOutlineLayer(
        type = type,
        primaryColor = primary,
        secondaryColor = secondary,
        speed = speed,
        density = density,
        width = width,
    )

    private fun fill(type: Int, primary: Int, secondary: Int, speed: Float, density: Float) =
        ParticleFillLayer(type = type, primaryColor = primary, secondaryColor = secondary, speed = speed, density = density)

    /** Fast and dense throughout. */
    private val FIRE = BuiltInEffectsPreset(
        id = "fire",
        keyboard = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, RED, BLACK, 1.75f, 1.5f, 1.25f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, AMBER, RED, 1.5f, 1.75f),
        ),
        radial = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, AMBER, RED, 1.5f, 1.25f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_FIRE, ORANGE, RED, 1.75f, 1.5f),
        ),
        strip = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, AMBER, ORANGE, 1.75f, 1.75f, 0.75f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, RED, AMBER, 1.5f, 1.25f),
        ),
        languageRevert = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, ORANGE, BLACK, 1.5f, 1.5f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, RED, AMBER, 1.75f, 1.5f),
        ),
        quickActions = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, RED, ORANGE, 1.75f, 1.25f, 1.25f),
            fill = fill(ParticleEffectsSettings.FILL_FIRE, AMBER, RED, 1.5f, 1.75f),
        ),
    )

    /** Slow and sparse throughout. */
    private val ICE = BuiltInEffectsPreset(
        id = "ice",
        keyboard = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, WHITE, BLUE_DARK, 0.75f, 0.5f, 0.5f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, BLUE_SOFT, BLUE_DARK, 0.5f, 0.75f),
        ),
        radial = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, BLUE_SOFT, BLUE_DARK, 0.5f, 0.75f, 0.5f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, TEAL, BLUE, 0.75f, 0.5f),
        ),
        strip = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, WHITE, BLUE, 0.75f, 0.5f, 0.75f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, BLUE, BLUE_DARK, 0.5f, 0.75f),
        ),
        languageRevert = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, BLUE_SOFT, BLUE_DARK, 0.5f, 0.5f, 0.5f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, TEAL, BLUE_DARK, 0.75f, 0.5f),
        ),
        quickActions = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, WHITE, BLUE, 0.75f, 0.75f, 0.5f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, BLUE_SOFT, BLUE_DARK, 0.5f, 0.75f),
        ),
    )

    /** Warm and unhurried: amber and orange, with grey and white. */
    private val SAND = BuiltInEffectsPreset(
        id = "sand",
        keyboard = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, AMBER, GREY_DARK, 1f, 0.75f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, ORANGE, AMBER, 0.75f, 1f),
        ),
        radial = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, WHITE, AMBER, 0.75f, 1f, 0.75f),
            fill = fill(ParticleEffectsSettings.FILL_FIRE, AMBER, ORANGE, 1f, 0.75f),
        ),
        strip = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, GREY_LIGHT, AMBER, 1f, 1f, 0.75f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, AMBER, ORANGE, 0.75f, 1f),
        ),
        languageRevert = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, ORANGE, GREY_DARK, 0.75f, 0.75f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, AMBER, ORANGE, 1f, 0.75f),
        ),
        quickActions = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, AMBER, GREY_LIGHT, 1f, 0.75f, 0.75f),
            fill = fill(ParticleEffectsSettings.FILL_FIRE, ORANGE, AMBER, 0.75f, 1f),
        ),
    )

    /** Middling speed, dense throughout. */
    private val FOREST = BuiltInEffectsPreset(
        id = "forest",
        keyboard = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, GREEN_LIGHT, BLACK, 1.25f, 1.75f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, GREEN_EMERALD, TEAL, 1f, 1.5f),
        ),
        radial = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, GREEN_LIGHT, GREEN_EMERALD, 1f, 1.5f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, TEAL, GREEN_EMERALD, 1.25f, 1.75f),
        ),
        strip = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, GREEN_EMERALD, BLACK, 1.25f, 1.5f, 1.25f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, GREEN_LIGHT, TEAL, 1f, 1.75f),
        ),
        languageRevert = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, TEAL, GREEN_EMERALD, 1f, 1.75f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_WAVES, GREEN_LIGHT, GREEN_EMERALD, 1.25f, 1.5f),
        ),
        quickActions = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, GREEN_EMERALD, TEAL, 1.25f, 1.5f, 1f),
            fill = fill(ParticleEffectsSettings.FILL_GLOW, GREEN_LIGHT, GREEN_EMERALD, 1f, 1.75f),
        ),
    )

    /** Fast, dense and wide throughout. */
    private val NEON = BuiltInEffectsPreset(
        id = "neon",
        keyboard = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, PINK, PURPLE, 2f, 1.75f, 1.5f),
            fill = fill(ParticleEffectsSettings.FILL_NEON, PINK, PURPLE, 1.75f, 2f),
        ),
        radial = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, PURPLE, BLUE_LAVENDER, 1.75f, 1.75f, 1.5f),
            fill = fill(ParticleEffectsSettings.FILL_RAINBOW, PINK, PURPLE, 2f, 1.75f),
        ),
        strip = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_PULSE, BLUE_LAVENDER, PINK, 2f, 2f, 1.25f),
            fill = fill(ParticleEffectsSettings.FILL_NEON, PURPLE, PINK, 1.75f, 1.75f),
        ),
        languageRevert = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_SPARKLE, PINK, BLUE_LAVENDER, 1.75f, 1.75f, 1.5f),
            fill = fill(ParticleEffectsSettings.FILL_RAINBOW, PURPLE, PINK, 2f, 1.75f),
        ),
        quickActions = ParticleRegionLook(
            outline = outline(ParticleEffectsSettings.OUTLINE_COMET, PURPLE, PINK, 2f, 1.75f, 1.5f),
            fill = fill(ParticleEffectsSettings.FILL_NEON, PINK, PURPLE, 1.75f, 2f),
        ),
    )

    /** Every region switched off, with each layer's stock values. */
    val OFF = BuiltInEffectsPreset(
        id = "off",
        enabled = false,
        keyboard = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        radial = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        strip = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        languageRevert = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        quickActions = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
    )

    /** Declared after the presets it lists, which initialise top to bottom; [OFF] first. */
    val ALL: List<BuiltInEffectsPreset> = listOf(OFF, FIRE, ICE, SAND, FOREST, NEON)
}
