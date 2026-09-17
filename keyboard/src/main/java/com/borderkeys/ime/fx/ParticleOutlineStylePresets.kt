// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * Outline's own three looks -- Comet, Pulse, Sparkle -- distinct from [ParticleEffectPresets]'
 * five (Fill keeps those; Outline does not reuse them). All three are built from
 * [ParticleMotionKind.STATIC_FLICKER] and one of two existing colour kinds -- no new motion or
 * colour function was needed for any of them, the same way [ParticleEffectPresets]' own five
 * already share only four motion/colour kinds between them (Glow and Rainbow both pulse in
 * place; Glow and Waves both crossfade) and differ mostly by numbers.
 *
 * Every style here radiates *outward*: an outline particle spawns on the traced shape's own
 * edge (see [EmitterShape.roundedRectPerimeterSample]), is pushed out along the edge's normal by
 * its own radius so the whole dot sits outside the element tangent to it, and then keeps moving
 * away along that normal ([ParticleMotionKind.OUTWARD]) -- slowly for [COMET]/[PULSE], as a dart
 * for [SPARKLE], and for [FIRE]/[WIND] with a directional drift and an emission cone on top.
 * Nothing from this layer is ever seen inside the element; that is what the fill layer is for.
 * Dots stay small (at most a 4.5px radius at the default width): big dots centred on the line
 * once read as a soft pill-shaped halo around a key rather than as its outline -- per the user's
 * own report. The Width slider still scales these up for anyone who wants that.
 */
object ParticleOutlineStylePresets {

    /**
     * One bright point continuously advancing around the perimeter/arc, trailing fainter
     * particles behind it. The "travel" is the moving *emission point*, driven by
     * [ParticleEffectPreset.travelLoopsPerSecond] and [ParticleSimulation]'s own ambient-phase
     * advance; each spawned particle then drifts slowly outward (`OUTWARD`, no emission cone,
     * no directional drift), so the trail fans away from the element as it fades. `CROSSFADE`
     * reads as bright near the head (life close to 1, spawned moments ago -> primaryColor)
     * fading toward secondaryColor as each trailing particle ages -- the comet-trail look, from
     * a colour kind that already existed for Fill's own Glow/Waves.
     */
    val COMET = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 40f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 3.5f,
        lifetimeSeconds = 0.4f,
        travelLoopsPerSecond = 0.6f,
        outwardSpeedPxPerSecond = 12f,
    )

    /** Random-along-perimeter spawn + `OUTWARD` + `SINGLE_COLOR_PULSE` -- dots breathing in sync
     *  as they drift away from the whole outline, all around it. Shares its mechanism with
     *  [SPARKLE]; the two differ only in numbers, the same way [ParticleEffectPresets]' own five
     *  differ from each other purely by numbers on a shared set of motion/colour kinds. */
    val PULSE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 12f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 1.2f,
        pulseFrequencyHz = 0.8f,
        outwardSpeedPxPerSecond = 14f,
    )

    /** Same mechanism as [PULSE], tuned for a twinkle: shorter lifetime, higher spawn rate,
     *  faster flicker, a quicker outward dart. */
    val SPARKLE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 26f,
        maxParticles = 28,
        burstCount = 0,
        minRadiusPx = 1.5f,
        maxRadiusPx = 3f,
        lifetimeSeconds = 0.45f,
        pulseFrequencyHz = 3.5f,
        outwardSpeedPxPerSecond = 30f,
    )

    /**
     * A real stroke -- [ParticleEffectPreset.strokeWidthPx] -- traced along the region's own
     * shape, with a handful of embers drifting up off it. `RISE_AND_SHRINK`, the same motion
     * Fill's own [ParticleEffectPresets.FIRE] already uses, is reused rather than a new function:
     * [ParticleMotion.riseAndShrinkX]/[.riseAndShrinkY] already take independent drift/rise
     * speeds, which is exactly the two knobs this and [WIND] each need in a different balance.
     * Few, small, short-lived particles on purpose -- this is an accent on the line, not the
     * main event the way Fill's own Fire burst is.
     */
    val FIRE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.THERMAL_GRADIENT,
        spawnRatePerSecond = 16f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4.5f,
        lifetimeSeconds = 0.7f,
        // Embers leave the line outward and rise: they only ever spawn on the upward-facing
        // parts of the outline (top edge, upper corner arcs), so fire comes off the top of a
        // key and never off its bottom into the row below.
        outwardSpeedPxPerSecond = 20f,
        driftPxPerSecond = 60f,
        emitDirectionX = 0f,
        emitDirectionY = -1f,
        emitConeCos = 0.05f,
        shrinkPerSecond = 0.7f,
        strokeWidthPx = 2.5f,
    )

    /** Same mechanism as [FIRE] -- a real stroke plus `RISE_AND_SHRINK` particles -- balanced the
     *  other way: almost no rise, a wider sideways drift, so it reads as blown off the line
     *  rather than rising from it. `CROSSFADE` rather than `THERMAL_GRADIENT`, so a region's own
     *  colour pair reads through the drift instead of always warming toward orange/red the way
     *  the thermal gradient does regardless of which colours are actually set. */
    val WIND = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 18f,
        maxParticles = 22,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 0.8f,
        // Blown off the line to the right: spawns only on the right-facing parts of the
        // outline and drifts that way, so nothing is ever blown back across the element.
        outwardSpeedPxPerSecond = 12f,
        driftPxPerSecond = 40f,
        emitDirectionX = 1f,
        emitDirectionY = 0f,
        emitConeCos = 0.05f,
        shrinkPerSecond = 0.5f,
        strokeWidthPx = 2f,
    )

    /** [type] is `ParticleEffectsSettings.OUTLINE_*` by convention -- `:keyboard` cannot
     *  reference `:data`'s type, the same reasoning [ParticleEffectPresets.forSetting]'s own
     *  doc already gives for Fill. `OUTLINE_NONE`/an out-of-range value fall back to [PULSE]
     *  rather than crashing; in practice a caller never asks for a preset when the type is
     *  None -- see [com.borderkeys.ime.BorderKeysService]'s own outline wiring. */
    fun forSetting(type: Int): ParticleEffectPreset = when (type) {
        1 -> COMET
        2 -> PULSE
        3 -> SPARKLE
        4 -> FIRE
        5 -> WIND
        else -> PULSE
    }
}
