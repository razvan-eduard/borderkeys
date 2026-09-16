// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import kotlin.math.max
import kotlin.math.min

/**
 * A fixed-size pool of particles, owned and driven entirely by whichever `View` wants them --
 * never a shared singleton, the same way [com.borderkeys.ime.KeyboardCanvasView] owns its own
 * key-press animation state independently of every other view in the process.
 *
 * The actual particle state and physics live in [ParticleSimulation]; this class adds the real
 * frame clock (one shared [Choreographer.FrameCallback] per instance, re-armed only while
 * something is live -- [com.borderkeys.ime.KeyboardCanvasView]'s own `scheduleFrame`/
 * `onAnimationFrame`/`animating` idiom, ported rather than reinvented) and lands the result on a
 * `Canvas`. Split specifically so the simulation itself stays plain-JVM testable -- see
 * [ParticleSimulation]'s own doc for why this half, not that one, is where `Choreographer` lives.
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

    /** The hard gate. `false` clears every live particle, stops any ambient emission and cancels
     *  the frame callback immediately -- while off, [Choreographer.postFrameCallback] is never
     *  called at all, not merely drawing nothing. */
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

    fun spawnBurstAtPoint(x: Float, y: Float, count: Int = preset.burstCount) {
        if (!enabled) {
            return
        }
        simulation.spawnBurstAtPoint(x, y, count)
        scheduleFrame()
    }

    fun spawnBurstInRectangle(left: Float, top: Float, right: Float, bottom: Float, count: Int = preset.burstCount) {
        if (!enabled) {
            return
        }
        simulation.spawnBurstInRectangle(left, top, right, bottom, count)
        scheduleFrame()
    }

    fun spawnBurstOnRing(centerX: Float, centerY: Float, radius: Float, insetFraction: Float, count: Int = preset.burstCount) {
        if (!enabled) {
            return
        }
        simulation.spawnBurstOnRing(centerX, centerY, radius, insetFraction, count)
        scheduleFrame()
    }

    /** Starts (or moves) a low-rate ambient trickle from a fixed point -- the ring's highlighted
     *  wedge, for instance. Replaces any rectangle ambient already running. */
    fun setAmbientPoint(x: Float, y: Float) {
        if (!enabled) {
            return
        }
        simulation.setAmbientPoint(x, y)
        scheduleFrame()
    }

    /** Same as [setAmbientPoint], spawning anywhere inside a rect instead -- the strip's applied
     *  chip, for instance. */
    fun setAmbientRectangle(left: Float, top: Float, right: Float, bottom: Float) {
        if (!enabled) {
            return
        }
        simulation.setAmbientRectangle(left, top, right, bottom)
        scheduleFrame()
    }

    /** See [ParticleSimulation.setAmbientRectanglePerimeter]. */
    fun setAmbientRectanglePerimeter(left: Float, top: Float, right: Float, bottom: Float) {
        if (!enabled) {
            return
        }
        simulation.setAmbientRectanglePerimeter(left, top, right, bottom)
        scheduleFrame()
    }

    /** See [ParticleSimulation.setAmbientArc]. */
    fun setAmbientArc(centerX: Float, centerY: Float, radius: Float, startDeg: Float, sweepDeg: Float) {
        if (!enabled) {
            return
        }
        simulation.setAmbientArc(centerX, centerY, radius, startDeg, sweepDeg)
        scheduleFrame()
    }

    fun stopAmbient() {
        simulation.stopAmbient()
    }

    /** Draws every live particle as a flat filled circle into [paint], mutating its colour in
     *  place per particle -- never allocating a `Paint`, matching how [Paint] is used everywhere
     *  else in this package's views. No-op while [enabled] is false. */
    fun draw(canvas: Canvas, paint: Paint) {
        if (!enabled) {
            return
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
     * The tight bounding box of every live particle, for
     * [com.borderkeys.ime.KeyboardCanvasView]'s own dirty-rect invalidation -- the one view among
     * this package's callers that must not fall back to a bare `invalidate()`. Returns false
     * (and leaves [out] untouched) when nothing is live.
     */
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

    /** Call from `onDetachedFromWindow`. [Choreographer] is a process-global singleton, not tied
     *  to a view's lifecycle -- nothing else would cancel a pending callback on detach. */
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
