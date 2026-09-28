// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Path

/** The shapes a [ParticleElement] can draw, which the engine traces, walks and spawns inside. */
sealed class ParticleGeometry {

    companion object {
        /** A circle, as a square rounded all the way. */
        fun circle(centerX: Float, centerY: Float, radius: Float): RoundedRect =
            RoundedRect(centerX - radius, centerY - radius, centerX + radius, centerY + radius, radius)
    }

    /** A shape [ParticleSimulation] has a closed-form perimeter sampler for. */
    sealed class ClosedForm : ParticleGeometry()

    /** A rectangle rounded by [cornerRadiusPx]; `0f` is square. */
    data class RoundedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerRadiusPx: Float = 0f,
    ) : ClosedForm()

    /**
     * An annular slice between [innerRadiusPx] and [outerRadiusPx], from [startDeg] through
     * [sweepDeg], angles as [android.graphics.Canvas.drawArc] measures them.
     */
    data class AnnularWedge(
        val centerX: Float,
        val centerY: Float,
        val innerRadiusPx: Float,
        val outerRadiusPx: Float,
        val startDeg: Float,
        val sweepDeg: Float,
    ) : ClosedForm()

    /** A shape traced by [path], its particles spawned from [emitterFallback]; null spawns none. */
    data class Exact(val path: Path, val emitterFallback: ClosedForm? = null) : ParticleGeometry()

    /** The closed-form shape particles spawn on or in: itself, or an [Exact]'s fallback. */
    val emitter: ClosedForm?
        get() = when (this) {
            is ClosedForm -> this
            is Exact -> emitterFallback
        }
}
