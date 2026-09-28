// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * One host view's particle layers, [fill] and [outline], driven by elements: [press] bursts
 * inside one and traces its outline, [hold] keeps an ambient inside and around it until
 * [release], and [celebrate] bursts bigger.
 */
class ParticleSurface(
    fillCapacity: Int,
    outlineCapacity: Int,
    onInvalidate: () -> Unit,
) {
    val fill = ParticleField(fillCapacity, onInvalidate)
    val outline = ParticleField(outlineCapacity, onInvalidate)

    fun press(element: ParticleElement) {
        fill.burstIn(element.fillGeometry)
        outline.bind(element.outlineGeometry)
    }

    fun hold(element: ParticleElement) {
        fill.ambientIn(element.fillGeometry)
        outline.bind(element.outlineGeometry)
    }

    fun release() {
        fill.stopAmbient()
        outline.bind(null)
    }

    /** A burst of [burstMultiplier] times the fill preset's count; does not release. */
    fun celebrate(element: ParticleElement, burstMultiplier: Int) {
        fill.burstIn(element.fillGeometry, burstMultiplier)
    }

    val hasLiveParticles: Boolean
        get() = fill.hasLiveParticles || outline.hasLiveParticles

    /** Draws the fill layer, then the outline layer. */
    fun draw(canvas: Canvas, paint: Paint) {
        fill.draw(canvas, paint)
        outline.draw(canvas, paint)
    }

    /** The union of both layers' live bounds; false, [out] untouched, when neither has any. */
    fun computeLiveBounds(out: RectF, scratch: RectF): Boolean {
        val hasFill = fill.computeLiveBounds(out)
        val hasOutline = outline.computeLiveBounds(scratch)
        if (hasFill && hasOutline) {
            out.union(scratch)
        } else if (hasOutline) {
            out.set(scratch)
        }
        return hasFill || hasOutline
    }

    /** Cancels both layers; call from `onDetachedFromWindow`. */
    fun cancel() {
        fill.cancel()
        outline.cancel()
    }
}
