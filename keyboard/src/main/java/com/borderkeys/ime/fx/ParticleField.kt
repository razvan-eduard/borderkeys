// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import kotlin.math.max
import kotlin.math.min

/**
 * A fixed-size pool of particles owned by one view: a [ParticleSimulation] advanced by a frame
 * callback that runs only while something is live, and drawn onto a `Canvas`.
 */
class ParticleField(
    capacity: Int,
    private val onInvalidate: () -> Unit,
) {

    private val simulation = ParticleSimulation(capacity)

    var preset: ParticleEffectPreset
        get() = simulation.preset
        set(value) { simulation.preset = value }

    var speedMultiplier: Float
        get() = simulation.speedMultiplier
        set(value) { simulation.speedMultiplier = value }

    var densityMultiplier: Float
        get() = simulation.densityMultiplier
        set(value) { simulation.densityMultiplier = value }

    var widthMultiplier: Float
        get() = simulation.widthMultiplier
        set(value) { simulation.widthMultiplier = value }

    var primaryColor: Int = DEFAULT_COLOR
    var secondaryColor: Int = DEFAULT_COLOR

    /**
     * The outline a stroke traces for a [ParticleGeometry.Exact] region, set by [bind]; null
     * traces [ParticleSimulation.currentAmbientOutlineShape].
     */
    private var strokePath: Path? = null

    /** Scratch for [ParticleSimulation.currentAmbientOutlineShape], six floats. */
    private val ambientShapeScratch = FloatArray(6)

    /** The wedge outline [drawAmbientStroke] traces, reused. */
    private val wedgeStrokePath = Path()
    private val wedgeOuterBounds = RectF()
    private val wedgeInnerBounds = RectF()

    /** Whether the field runs; off clears every particle, stops the ambient and cancels the frame
     *  callback. */
    var enabled: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (!value) {
                cancel()
            }
        }

    val hasLiveParticles: Boolean get() = simulation.hasLiveParticles

    private var animating = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    /**
     * A burst inside [geometry] of [burstMultiplier] times the preset's count, scaled by its area.
     * Null, or a [ParticleGeometry.Exact] without an emitter shape, spawns nothing.
     */
    fun burstIn(geometry: ParticleGeometry?, burstMultiplier: Int = 1) {
        if (!enabled) {
            return
        }
        val count = preset.burstCount * burstMultiplier
        when (val emitter = geometry?.emitter) {
            null -> return
            is ParticleGeometry.RoundedRect -> simulation.spawnBurstInRoundedRect(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx, count,
            )
            is ParticleGeometry.AnnularWedge -> simulation.spawnBurstInAnnularWedge(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg, count,
            )
        }
        scheduleFrame()
    }

    /** Starts or moves an ambient spawning inside [geometry]; null stops it. */
    fun ambientIn(geometry: ParticleGeometry?) {
        if (!enabled) {
            return
        }
        when (val emitter = geometry?.emitter) {
            null -> simulation.stopAmbient()
            is ParticleGeometry.RoundedRect -> simulation.setAmbientRoundedRectInterior(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx,
            )
            is ParticleGeometry.AnnularWedge -> simulation.setAmbientAnnularWedgeInterior(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg,
            )
        }
        scheduleFrame()
    }

    fun stopAmbient() {
        simulation.stopAmbient()
    }

    /**
     * Traces [geometry]'s outline: particles along it and, for a stroke style, a stroke. Null
     * stops the ambient.
     */
    fun bind(geometry: ParticleGeometry?) {
        if (!enabled) {
            return
        }
        strokePath = (geometry as? ParticleGeometry.Exact)?.path
        when (val emitter = geometry?.emitter) {
            null -> simulation.stopAmbient()
            is ParticleGeometry.RoundedRect -> simulation.setAmbientRectanglePerimeter(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx,
            )
            is ParticleGeometry.AnnularWedge -> simulation.setAmbientAnnularWedgePerimeter(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg,
            )
        }
        scheduleFrame()
    }

    /**
     * Draws every live particle as a filled circle with [paint], its colour set per particle, over
     * a stroke along the ambient shape when [ParticleEffectPreset.strokeWidthPx] is set. Does
     * nothing while [enabled] is false.
     */
    fun draw(canvas: Canvas, paint: Paint) {
        if (!enabled) {
            return
        }
        if (preset.strokeWidthPx > 0f) {
            drawAmbientStroke(canvas, paint)
        }
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) {
                continue
            }
            val radius = simulation.radiusAt(slot)
            val life = simulation.lifeFractionAt(slot)
            if (radius <= 0f || life <= 0f) {
                continue
            }
            paint.color = simulation.colorAt(slot, primaryColor, secondaryColor)
            canvas.drawCircle(simulation.xAt(slot), simulation.yAt(slot), radius, paint)
        }
    }

    /**
     * Strokes the ambient shape with [paint], restoring its style and width after. The width is
     * scaled by the width multiplier and floored at [ParticleSimulation.MIN_LEGIBLE_SIZE_PX].
     */
    private fun drawAmbientStroke(canvas: Canvas, paint: Paint) {
        val strokeWidth = (preset.strokeWidthPx * widthMultiplier).coerceAtLeast(ParticleSimulation.MIN_LEGIBLE_SIZE_PX)
        val savedStyle = paint.style
        val savedWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = primaryColor
        val path = strokePath
        if (path != null) {
            canvas.drawPath(path, paint)
            paint.style = savedStyle
            paint.strokeWidth = savedWidth
            return
        }
        val shape = simulation.currentAmbientOutlineShape(ambientShapeScratch)
        if (shape == null) {
            paint.style = savedStyle
            paint.strokeWidth = savedWidth
            return
        }
        when (shape) {
            ParticleSimulation.AmbientOutlineShape.RECTANGLE_PERIMETER -> canvas.drawRoundRect(
                ambientShapeScratch[0], ambientShapeScratch[1], ambientShapeScratch[2], ambientShapeScratch[3],
                ambientShapeScratch[4], ambientShapeScratch[4], paint,
            )
            ParticleSimulation.AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER -> {
                val centerX = ambientShapeScratch[0]
                val centerY = ambientShapeScratch[1]
                val innerRadius = ambientShapeScratch[2]
                val outerRadius = ambientShapeScratch[3]
                val start = ambientShapeScratch[4]
                val sweep = ambientShapeScratch[5]
                wedgeOuterBounds.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius)
                wedgeInnerBounds.set(centerX - innerRadius, centerY - innerRadius, centerX + innerRadius, centerY + innerRadius)
                wedgeStrokePath.reset()
                wedgeStrokePath.arcTo(wedgeOuterBounds, start, sweep, true)
                wedgeStrokePath.arcTo(wedgeInnerBounds, start + sweep, -sweep, false)
                wedgeStrokePath.close()
                canvas.drawPath(wedgeStrokePath, paint)
            }
        }
        paint.style = savedStyle
        paint.strokeWidth = savedWidth
    }

    /** Puts the live particles' bounding box in [out]; false, [out] untouched, if none. */
    fun computeLiveBounds(out: RectF): Boolean {
        var found = false
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) {
                continue
            }
            val x = simulation.xAt(slot)
            val y = simulation.yAt(slot)
            val r = simulation.radiusAt(slot)
            if (!found) {
                left = x - r
                top = y - r
                right = x + r
                bottom = y + r
                found = true
            } else {
                left = min(left, x - r)
                top = min(top, y - r)
                right = max(right, x + r)
                bottom = max(bottom, y + r)
            }
        }
        if (found) {
            out.set(left, top, right, bottom)
        }
        return found
    }

    /** Cancels the pending frame callback; call from `onDetachedFromWindow`. */
    fun cancel() {
        if (animating) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
        }
        simulation.clear()
    }

    private fun scheduleFrame() {
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        val rawDeltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
        }
        lastFrameNanos = frameTimeNanos

        val stillAnimating = simulation.advance(rawDeltaSeconds)
        onInvalidate()

        if (stillAnimating) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            animating = false
        }
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    }
}
