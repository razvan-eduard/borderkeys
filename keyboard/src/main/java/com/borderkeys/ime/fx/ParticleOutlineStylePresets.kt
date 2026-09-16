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
 */
object ParticleOutlineStylePresets {

    /**
     * One bright point continuously advancing around the perimeter/arc, trailing fainter
     * particles behind it. A spawned particle never itself moves (`STATIC_FLICKER`) -- the
     * "travel" is entirely the moving *emission point*, driven by [ParticleEffectPreset
     * .travelLoopsPerSecond] and [ParticleSimulation]'s own ambient-phase advance. `CROSSFADE`
     * then reads as bright near the head (life close to 1, spawned moments ago -> primaryColor)
     * fading toward secondaryColor as each trailing particle ages -- the comet-trail look, from
     * a colour kind that already existed for Fill's own Glow/Waves.
     */
    val COMET = ParticleEffectPreset(
        motion = ParticleMotionKind.STATIC_FLICKER,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 40f,
        maxParticles = 18,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 0.35f,
        travelLoopsPerSecond = 0.6f,
    )

    /** Random-along-perimeter spawn + `STATIC_FLICKER` + `SINGLE_COLOR_PULSE` -- the whole
     *  outline breathes in sync. Shares its mechanism with [SPARKLE]; the two differ only in
     *  numbers, the same way [ParticleEffectPresets]' own five differ from each other purely by
     *  numbers on a shared set of motion/colour kinds. */
    val PULSE = ParticleEffectPreset(
        motion = ParticleMotionKind.STATIC_FLICKER,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 6f,
        maxParticles = 14,
        burstCount = 0,
        minRadiusPx = 3f,
        maxRadiusPx = 6f,
        lifetimeSeconds = 1.2f,
        pulseFrequencyHz = 0.8f,
    )

    /** Same mechanism as [PULSE], tuned for a twinkle: shorter lifetime, higher spawn rate,
     *  faster flicker, smaller radius. */
    val SPARKLE = ParticleEffectPreset(
        motion = ParticleMotionKind.STATIC_FLICKER,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 16f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 1.5f,
        maxRadiusPx = 3f,
        lifetimeSeconds = 0.4f,
        pulseFrequencyHz = 3.5f,
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
        else -> PULSE
    }
}
