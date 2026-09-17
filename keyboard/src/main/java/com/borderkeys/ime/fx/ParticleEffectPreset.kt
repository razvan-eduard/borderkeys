// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/** Which [ParticleMotion] function moves a particle -- resolved once per particle per frame by
 *  [ParticleField], not stored per particle. */
/**
 * [OUTWARD] is the outline layer's own motion: a particle leaves its spawn point along the
 * outline's outward normal at [ParticleEffectPreset.outwardSpeedPxPerSecond], plus a drift along
 * [ParticleEffectPreset.emitDirectionX]/[ParticleEffectPreset.emitDirectionY] at
 * [ParticleEffectPreset.driftPxPerSecond], shrinking as it goes -- so an outline effect radiates
 * *out of* the element and is never seen inside it (that is what the fill layer is for).
 */
enum class ParticleMotionKind { RISE_AND_SHRINK, PULSE_IN_PLACE, WAVE_DRIFT, STATIC_FLICKER, OUTWARD }

/** Which [ParticleColor] function colours a particle. */
enum class ParticleColorKind { THERMAL_GRADIENT, CROSSFADE, HUE_CYCLE, SINGLE_COLOR_PULSE }

/**
 * One named look -- Fire, Glow, Waves, Rainbow, Neon Pulse -- as plain numbers, not code: which
 * motion and colour function apply, how fast particles spawn and how many may be alive at once,
 * their size and lifetime, and whichever of the motion/colour tuning parameters that particular
 * combination actually reads (the rest are left at their default `0f`, harmless because the
 * `when` in [ParticleField] never reaches a branch that would use them).
 *
 * See [ParticleEffectPresets] for the five concrete values -- retuned down from the calendar
 * "today" cell decoration this was ported from, for a brief keyboard burst or a small ambient
 * glow rather than an always-on background decoration.
 */
data class ParticleEffectPreset(
    val motion: ParticleMotionKind,
    val color: ParticleColorKind,
    /** Ambient spawn rate. Bursts ignore this and spawn [burstCount] particles at once instead. */
    val spawnRatePerSecond: Float,
    /** Soft cap the ambient spawn accumulator will not spawn past. A burst may briefly exceed
     *  this by up to [burstCount] - 1 -- deliberately: a burst is a deliberate moment, not
     *  something worth dropping particles over. */
    val maxParticles: Int,
    val burstCount: Int,
    val minRadiusPx: Float,
    val maxRadiusPx: Float,
    val lifetimeSeconds: Float,
    val riseSpeedPxPerSecond: Float = 0f,
    val driftPxPerSecond: Float = 0f,
    val shrinkPerSecond: Float = 0f,
    /** Amplitude for both PULSE_IN_PLACE and WAVE_DRIFT -- the same "how far it swings" knob
     *  either way. */
    val pulseAmplitudePx: Float = 0f,
    /** Frequency for PULSE_IN_PLACE's motion and for SINGLE_COLOR_PULSE's colour -- the two
     *  presets that actually oscillate on a clock rather than drift or cycle. */
    val pulseFrequencyHz: Float = 0f,
    val waveWavelengthPx: Float = 0f,
    val waveSpeedRadPerSecond: Float = 0f,
    val hueOffsetDegPerPx: Float = 0f,
    val hueDegPerSecond: Float = 0f,
    /** Full loops per second a *traveling* ambient emission point advances around its
     *  perimeter/arc, before the layer's own speed multiplier scales it -- see
     *  [ParticleSimulation]'s own ambient-phase doc. Only meaningful for a preset whose ambient
     *  is spawned traveling (the outline "Comet" look); every other preset leaves this at its
     *  harmless default. */
    val travelLoopsPerSecond: Float = 0f,
    /** A real stroke traced along the current ambient shape itself, under the particles --
     *  `0f` (every preset but the outline "Fire"/"Wind" looks) means no stroke at all, so
     *  Comet/Pulse/Sparkle and every Fill preset stay pure particle scatter, unchanged. See
     *  [ParticleSimulation.drawAmbientStroke]. */
    val strokeWidthPx: Float = 0f,
    /** [ParticleMotionKind.OUTWARD] only: how fast a particle leaves the outline along its
     *  outward normal. */
    val outwardSpeedPxPerSecond: Float = 0f,
    /**
     * An emission direction for an outline preset -- unit vector, screen coordinates (`0, -1`
     * is up). Two things read it: [ParticleMotionKind.OUTWARD] drifts along it at
     * [driftPxPerSecond], and, with [emitConeCos] above `-1`, particles only spawn on the parts
     * of the outline whose outward normal points within that cone of it -- fire rises, so it
     * shoots off the top of a key and never off its bottom edge into the row below. `0, 0`
     * (every preset but the outline Fire/Wind looks) means no direction: spawn everywhere,
     * drift nowhere.
     */
    val emitDirectionX: Float = 0f,
    val emitDirectionY: Float = 0f,
    /** Minimum dot product between an outline point's outward normal and the emit direction for
     *  a particle to spawn there -- `-1` accepts the whole outline, `0` the half facing the
     *  emit direction, higher a narrower cone. */
    val emitConeCos: Float = -1f,
)
