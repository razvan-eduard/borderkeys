// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * One host view's pair of particle layers -- [fill] and [outline] -- and the only vocabulary a
 * host uses to drive them: an element is pressed, held, or released. The host never passes a
 * coordinate; it passes a [ParticleElement], and this class asks that element where its outline
 * and its interior are. See [ParticleElement]'s own doc for why that split is the whole design.
 *
 * - [press]: a one-shot burst inside the element, and the outline starts tracing it. A key
 *   going down, a button tapped, a panel row picked.
 * - [hold]: an ambient trickle inside the element, and the outline traces it, until [release].
 *   The strip's applied chip while it is on screen, the ring's wedge while the finger hovers it.
 * - [release]: both ambients stop. Bursts already in flight finish on their own.
 * - [celebrate]: a bigger one-shot burst inside the element -- the ring's pick moment.
 *
 * Both layers draw in one call ([draw]) and are torn down together ([cancel]); a host's own
 * dirty-rect invalidation reads [computeLiveBounds] for the union of both.
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

    /** [burstMultiplier] times the fill preset's own burst count -- a deliberately bigger moment
     *  than [press]. Does not release: the caller decides whether the element stays held. */
    fun celebrate(element: ParticleElement, burstMultiplier: Int) {
        fill.burstIn(element.fillGeometry, burstMultiplier)
    }

    val hasLiveParticles: Boolean
        get() = fill.hasLiveParticles || outline.hasLiveParticles

    /** Fill under outline, so a traced line and its dots always read on top of a glow. */
    fun draw(canvas: Canvas, paint: Paint) {
        fill.draw(canvas, paint)
        outline.draw(canvas, paint)
    }

    /** The union of both layers' live bounds -- see [ParticleField.computeLiveBounds]. Returns
     *  false, leaving [out] untouched, when neither layer has anything live. */
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

    /** Call from `onDetachedFromWindow` -- see [ParticleField.cancel]. */
    fun cancel() {
        fill.cancel()
        outline.cancel()
    }
}
