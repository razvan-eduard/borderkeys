// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.effects

import kotlin.math.PI
import kotlin.math.sin

/**
 * Where a playing effect sits at one instant: an offset from its resting place, a size and an
 * opacity. Everything a style has to say, and nothing about what is being moved.
 */
data class EffectTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
)

/**
 * How an effect moves, as a pure function of how far through it is. `travel` is the distance the
 * effect has to play with, in pixels: the key area's height.
 */
enum class EffectStyle {

    /** Rises the whole way and fades in then out across it, brightest halfway up. */
    RiseFade {
        override fun at(progress: Float, travel: Float): EffectTransform = EffectTransform(
            dy = -travel * RISE_FRACTION * smoothStep(progress),
            alpha = sin(progress * PI.toFloat()).coerceIn(0f, 1f),
        )
    },

    /** Enters from below and leaves through the top at an even pace, fading only at the ends. */
    Slide {
        override fun at(progress: Float, travel: Float): EffectTransform = EffectTransform(
            dy = travel * (HALF - progress),
            alpha = edgeFade(progress),
        )
    },

    /** Collapses the way a lamp does when it is switched off: flat, then gone. */
    LampHide {
        override fun at(progress: Float, travel: Float): EffectTransform {
            val shrink = 1f - smoothStep(progress)
            return EffectTransform(
                dy = -travel * QUARTER * progress,
                scale = shrink.coerceAtLeast(0f),
                alpha = shrink,
            )
        }
    },

    /** Overshoots its size and settles back, then fades out where it stands. */
    Pop {
        override fun at(progress: Float, travel: Float): EffectTransform = EffectTransform(
            scale = 1f + POP_OVERSHOOT * sin(progress * PI.toFloat()),
            alpha = if (progress < HALF) 1f else 1f - (progress - HALF) / HALF,
        )
    },

    /** Drifts up and sideways, slowly, and takes the whole time to fade. */
    Drift {
        override fun at(progress: Float, travel: Float): EffectTransform = EffectTransform(
            dx = travel * DRIFT_FRACTION * sin(progress * PI.toFloat()),
            dy = -travel * RISE_FRACTION * progress,
            alpha = 1f - smoothStep(progress),
        )
    };

    /** Where the effect is at [progress], a fraction from 0 at the start to 1 at the end. */
    abstract fun at(progress: Float, travel: Float): EffectTransform

    companion object {

        /** The default, and the style a swipe's accepted word uses. */
        val DEFAULT = RiseFade

        /** Ease in and out, the curve the composer's own swipe hint uses. */
        private fun smoothStep(progress: Float): Float = progress * progress * (3f - 2f * progress)

        /** Opaque in the middle, fading across the first and last fifth. */
        private fun edgeFade(progress: Float): Float = when {
            progress < EDGE -> progress / EDGE
            progress > 1f - EDGE -> (1f - progress) / EDGE
            else -> 1f
        }.coerceIn(0f, 1f)

        private const val RISE_FRACTION = 0.5f
        private const val DRIFT_FRACTION = 0.18f
        private const val POP_OVERSHOOT = 0.35f
        private const val EDGE = 0.2f
        private const val HALF = 0.5f
        private const val QUARTER = 0.25f
    }
}
