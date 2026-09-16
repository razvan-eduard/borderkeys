// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Where a particle sits at a given age, in closed form.
 *
 * Every function here takes the particle's fixed spawn point plus how long it has lived, and
 * returns where it is *now* -- never a velocity to integrate. That is the whole reason a
 * [ParticleField] can hold a particle as `spawnX`/`spawnY`/`ageSeconds` and nothing else: there is
 * no "current position" to carry from one frame to the next, so there is nothing here for a
 * frame's rounding error to accumulate in either.
 *
 * Pure and allocation-free, the same shape as
 * [com.borderkeys.ime.RadialSuggestionMenuView.wedgeCentreDegrees] -- plain numbers in, one
 * `Float` out, safe to call sixty times a second per particle.
 */
object ParticleMotion {

    /**
     * Horizontal drift for the rise-and-shrink motion (the "Fire" preset).
     *
     * The direction is not stored anywhere: it falls out of the spawn point itself, even/odd on
     * its truncated `spawnX`, so two particles spawned a pixel apart drift opposite ways without
     * the field needing a seed array just to remember which.
     */
    fun riseAndShrinkX(spawnX: Float, ageSeconds: Float, driftPxPerSecond: Float): Float {
        val direction = if (spawnX.toInt() % 2 == 0) 1f else -1f
        return spawnX + driftPxPerSecond * direction * ageSeconds
    }

    /** Rises at a constant rate -- y decreases, screen coordinates grow downward. */
    fun riseAndShrinkY(spawnY: Float, ageSeconds: Float, riseSpeedPxPerSecond: Float): Float =
        spawnY - riseSpeedPxPerSecond * ageSeconds

    /**
     * Exponential decay, the closed-form equivalent of "shrinks by a fraction every second": a
     * loop that multiplied the radius by `(1 - rate * dt)` every frame would approach this same
     * curve in the limit of small `dt`, but would also have to store the shrinking radius itself
     * as state. This computes it directly from age instead.
     */
    fun riseAndShrinkRadius(spawnRadiusPx: Float, ageSeconds: Float, shrinkPerSecond: Float): Float =
        spawnRadiusPx * exp(-shrinkPerSecond * ageSeconds)

    /** Oscillates around the spawn point -- the "Glow" and "Rainbow" presets' motion. */
    fun pulseInPlaceY(spawnY: Float, ageSeconds: Float, amplitudePx: Float, frequencyHz: Float): Float =
        spawnY + amplitudePx * sin(ageSeconds * frequencyHz * TWO_PI)

    /**
     * A travelling wave, not synchronised bobbing: the phase depends on [spawnX], so a whole
     * field of particles reads as one wave passing through rather than everything pulsing in
     * lockstep.
     */
    fun waveDriftY(
        spawnX: Float,
        spawnY: Float,
        ageSeconds: Float,
        amplitudePx: Float,
        wavelengthPx: Float,
        speedRadPerSecond: Float,
    ): Float {
        if (wavelengthPx == 0f) {
            return spawnY
        }
        val phase = (spawnX / wavelengthPx) * TWO_PI + ageSeconds * speedRadPerSecond
        return spawnY + amplitudePx * sin(phase)
    }

    // Static flicker needs no function of its own: x stays spawnX, y stays spawnY: only the
    // colour moves, in ParticleColor.singleColorPulse.

    private val TWO_PI = (2.0 * PI).toFloat()
}
