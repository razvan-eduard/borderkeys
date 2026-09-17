// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * One region's own slice of a [BuiltInEffectsPreset] -- an (outline, fill) pair, the same shape
 * [CustomEffectsPresetEntry] saves, but never persisted: there are exactly five of these per
 * preset, one per region, written by hand below rather than by the user.
 */
data class ParticleRegionLook(val outline: ParticleOutlineLayer, val fill: ParticleFillLayer) {
    fun appliedTo(region: ParticleRegionSettings, enabled: Boolean = true): ParticleRegionSettings =
        region.copy(enabled = enabled, outline = outline, fill = fill)

    fun matches(region: ParticleRegionSettings, enabled: Boolean = true): Boolean =
        region.enabled == enabled && region.outline == outline && region.fill == fill
}

/**
 * A whole-keyboard look that ships with the app, rather than one the user built and saved --
 * [CustomEffectsPresetEntry]'s counterpart, one level up. A [CustomEffectsPresetEntry] is a
 * single (outline, fill) pair broadcast identically to all five regions, because it is a snapshot
 * of whichever one region the user had open; a built-in preset is not limited to that, and tunes
 * each region on its own -- a ring reads differently from a key or a strip, so "Fire" earns its
 * name by giving each region a combination suited to it, the way a person dialling in five
 * regions by hand would, rather than repeating one pair five times.
 *
 * Every one of the five regions gets a real, non-default outline style and a real fill style --
 * never left at [ParticleEffectsSettings.OUTLINE_NONE] -- and its own speed/density (and, for
 * outline, width), so a preset reads as a deliberately paced, deliberately coloured look rather
 * than five regions each quietly sitting at the 1.0 default.
 *
 * Not renameable, not deletable and not stored in [CustomEffectsPresetLibrary] -- there is nothing
 * here for the user to have authored, so there is nothing here for them to rename or delete.
 *
 * [id] is a bare, stable slug ("fire", "ice", ...), not a display name -- this module has no
 * dependency on `:i18n` and never will, so the caller maps [id] to a translated string itself,
 * the same way it already maps [ParticleFillLayer.type] to one of [ParticleEffectsSettings]'s
 * own `FILL_*` constants to a string.
 *
 * [enabled] is what the preset writes to every region's own master switch: `true` for every
 * named look (Fire, Ice, ...), `false` for exactly one built-in, [BuiltInEffectsPresets.OFF].
 * That is the only thing that sets Off apart. It is a preset of exactly the same weight as the
 * named ones: applying it writes its five looks and switches the regions off, and it matches
 * only while every region still carries those looks, switched off -- so the moment anything is
 * changed from it, a region switched on included, that is a change from Off the same way a
 * colour changed from Fire is a change from Fire. It used to touch only the switches and leave
 * the looks alone, which made "custom" mean something different depending on which preset was
 * applied.
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
    /** The five looks written to their regions, and this preset recorded as the applied one --
     *  [ParticleEffectsSettings.appliedPresetId] -- in the same value, so the two can never
     *  disagree about which preset the regions came from. */
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

/**
 * The five starting looks "My presets" ships with -- see [BuiltInEffectsPreset]'s own doc for
 * why each region gets its own combination rather than one pair repeated five times.
 *
 * Every colour below is one of [ThemePalette.COLOURS] by reference, not a hand-typed hex value:
 * a swatch row rings the entry that equals the current value, and a colour that is not actually
 * in that list draws as the unmarked, uncommitted-looking "transient" swatch at the end of the
 * row instead -- exactly what a built-in preset, of all things, should never look like.
 */
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

    /** Fast and dense throughout -- this one is meant to feel urgent, not merely coloured red. */
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

    /** Slow and sparse throughout -- crystalline rather than lively, on purpose. */
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

    /** Warm and unhurried -- the palette has no tan of its own, so amber and orange carry the
     *  warmth and grey/white stand in for the pale, sun-bleached half of the look. */
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

    /** Middling speed, but dense throughout -- lush rather than sparse, the one axis this preset
     *  leans on hardest. */
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

    /** Fast, dense and wide throughout -- the most extreme preset on every axis at once, on
     *  purpose: this is the one that should look nothing like sitting at the 1.0 default. */
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

    /** The one-tap "everything off" -- see [BuiltInEffectsPreset.enabled]'s own doc. Its looks
     *  are each layer's stock values (no outline, the stock fill), written like any other
     *  preset's, so that a region switched back on by hand starts from the same place a fresh
     *  install does. */
    val OFF = BuiltInEffectsPreset(
        id = "off",
        enabled = false,
        keyboard = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        radial = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        strip = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        languageRevert = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
        quickActions = ParticleRegionLook(ParticleOutlineLayer(), ParticleFillLayer()),
    )

    /** Declared after the six presets above, not before -- a property initialiser can only
     *  read what has already run, and an `object`'s properties run top to bottom. [OFF] first:
     *  the one-tap "back to nothing" reads as the baseline every named look departs from, not
     *  one more look alongside them. */
    val ALL: List<BuiltInEffectsPreset> = listOf(OFF, FIRE, ICE, SAND, FOREST, NEON)
}
