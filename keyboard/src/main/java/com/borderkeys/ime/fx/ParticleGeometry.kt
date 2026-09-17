// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Path

/**
 * The shapes a [ParticleElement] can draw -- the closed vocabulary the engine knows how to
 * trace a stroke around, walk the perimeter of, and spawn inside. Adding a shape means adding it
 * here once, with its perimeter and interior samplers in [EmitterShape]/[ParticleSimulation];
 * every element of that shape then gets correct placement without any host changing.
 */
sealed class ParticleGeometry {

    companion object {
        /** A circle is a square with its corners rounded all the way -- exactly what
         *  `canvas.drawCircle` and `drawRoundRect` with that radius both paint -- so it needs no
         *  case of its own anywhere below. */
        fun circle(centerX: Float, centerY: Float, radius: Float): RoundedRect =
            RoundedRect(centerX - radius, centerY - radius, centerX + radius, centerY + radius, radius)
    }

    /** A shape [ParticleSimulation] has a closed-form perimeter sampler for, so particles spawn
     *  exactly along the traced line itself rather than an approximation of it. */
    sealed class ClosedForm : ParticleGeometry()

    /** A plain or rounded rectangle -- every key, chip, button and panel row in this app. A
     *  square corner is [cornerRadiusPx] `0f`, not a separate case: most of this app's own
     *  backgrounds are `canvas.drawRect`, not `drawRoundRect`, and a stroke tracing one is a
     *  rounded rect with a radius of zero, not a different shape. */
    data class RoundedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerRadiusPx: Float = 0f,
    ) : ClosedForm()

    /** An annular slice -- the radial suggestion ring's own highlighted wedge, the one shape in
     *  this app that is neither a rectangle nor a full circle: bounded by two arcs (at
     *  [innerRadiusPx] and [outerRadiusPx]) and the two straight radial edges connecting them at
     *  [startDeg] and [startDeg] + [sweepDeg]. [startDeg]/[sweepDeg] use
     *  [android.graphics.Canvas.drawArc]'s own degree convention. */
    data class AnnularWedge(
        val centerX: Float,
        val centerY: Float,
        val innerRadiusPx: Float,
        val outerRadiusPx: Float,
        val startDeg: Float,
        val sweepDeg: Float,
    ) : ClosedForm()

    /** A shape this app has no closed-form model for -- a Compose `Shape` that turned out not to
     *  be a plain or rounded rectangle, for instance (see `com.borderkeys.settings
     *  .ParticleChipPreview`, the one caller that can produce this case). Traces exactly via
     *  [path]; particles still spawn from [emitterFallback] (typically the shape's own bounding
     *  [RoundedRect]) rather than nowhere, since sampling a spawn point along an arbitrary path
     *  has no closed form the way [RoundedRect]/[AnnularWedge] do. `null` means the stroke traces
     *  correctly but nothing embers off it, and nothing fills it either. */
    data class Exact(val path: Path, val emitterFallback: ClosedForm? = null) : ParticleGeometry()

    /** The closed-form shape particles actually spawn on or in -- itself for a [ClosedForm],
     *  the fallback for an [Exact] path. */
    val emitter: ClosedForm?
        get() = when (this) {
            is ClosedForm -> this
            is Exact -> emitterFallback
        }
}
