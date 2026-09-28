// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Where a particle is at a given age, in closed form from its spawn point. Pure and
 * allocation-free.
 */
object ParticleMotion {

    /**
     * Horizontal drift for the rise-and-shrink motion, leftwards or rightwards as the truncated
     * [spawnX] is even or odd.
     */
    fun riseAndShrinkX(spawnX: Float, ageSeconds: Float, driftPxPerSecond: Float): Float {
        val direction = if (spawnX.toInt() % 2 == 0) 1f else -1f
        return spawnX + driftPxPerSecond * direction * ageSeconds
    }

    /** Rises at a constant rate -- y decreases, screen coordinates grow downward. */
    fun riseAndShrinkY(spawnY: Float, ageSeconds: Float, riseSpeedPxPerSecond: Float): Float =
        spawnY - riseSpeedPxPerSecond * ageSeconds

    /** The radius decaying exponentially at [shrinkPerSecond]. */
    fun riseAndShrinkRadius(spawnRadiusPx: Float, ageSeconds: Float, shrinkPerSecond: Float): Float =
        spawnRadiusPx * exp(-shrinkPerSecond * ageSeconds)

    /**
     * The outline motion: out along the normal ([normalX], [normalY]) at [outwardPxPerSecond],
     * plus a drift of [driftPxPerSecond] along the unit vector ([driftX], [driftY]).
     */
    fun outwardX(spawnX: Float, normalX: Float, driftX: Float, ageSeconds: Float, outwardPxPerSecond: Float, driftPxPerSecond: Float): Float =
        spawnX + (normalX * outwardPxPerSecond + driftX * driftPxPerSecond) * ageSeconds

    fun outwardY(spawnY: Float, normalY: Float, driftY: Float, ageSeconds: Float, outwardPxPerSecond: Float, driftPxPerSecond: Float): Float =
        spawnY + (normalY * outwardPxPerSecond + driftY * driftPxPerSecond) * ageSeconds

    /** Oscillates around the spawn point. */
    fun pulseInPlaceY(spawnY: Float, ageSeconds: Float, amplitudePx: Float, frequencyHz: Float): Float =
        spawnY + amplitudePx * sin(ageSeconds * frequencyHz * TWO_PI)

    /** A travelling wave, its phase from [spawnX]. */
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

    // Static flicker stays at the spawn point; only its colour moves.

    private val TWO_PI = (2.0 * PI).toFloat()
}
