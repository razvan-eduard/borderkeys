// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * A graphic element particles can affect, by the shape it draws: [geometry], in its host's pixel
 * space, null before layout. The outline layer traces [outlineGeometry] and the fill layer spawns
 * inside [fillGeometry], both [geometry] unless overridden.
 */
abstract class ParticleElement {

    /** The shape this element draws, in the owning host's pixel space, or `null` before layout. */
    abstract val geometry: ParticleGeometry?

    /** What the outline layer traces. The element's own drawn shape unless overridden. */
    open val outlineGeometry: ParticleGeometry?
        get() = geometry

    /** What the fill layer spawns inside. The element's own drawn shape unless overridden. */
    open val fillGeometry: ParticleGeometry?
        get() = geometry
}

/**
 * A reusable rounded-rectangle element the host [set]s before pressing or holding it; null until
 * set, and after [clear].
 */
class RoundedRectElement : ParticleElement() {
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private var cornerRadius = 0f
    private var isSet = false

    fun set(left: Float, top: Float, right: Float, bottom: Float, cornerRadiusPx: Float = 0f) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        this.cornerRadius = cornerRadiusPx
        isSet = true
    }

    fun setCircle(centerX: Float, centerY: Float, radius: Float) =
        set(centerX - radius, centerY - radius, centerX + radius, centerY + radius, radius)

    fun clear() {
        isSet = false
    }

    override val geometry: ParticleGeometry?
        get() = if (isSet) ParticleGeometry.RoundedRect(left, top, right, bottom, cornerRadius) else null
}

/** A reusable annular-wedge element; see [ParticleGeometry.AnnularWedge]. */
class AnnularWedgeElement : ParticleElement() {
    private var centerX = 0f
    private var centerY = 0f
    private var innerRadius = 0f
    private var outerRadius = 0f
    private var startDeg = 0f
    private var sweepDeg = 0f
    private var isSet = false

    fun set(centerX: Float, centerY: Float, innerRadiusPx: Float, outerRadiusPx: Float, startDeg: Float, sweepDeg: Float) {
        this.centerX = centerX
        this.centerY = centerY
        this.innerRadius = innerRadiusPx
        this.outerRadius = outerRadiusPx
        this.startDeg = startDeg
        this.sweepDeg = sweepDeg
        isSet = true
    }

    fun clear() {
        isSet = false
    }

    override val geometry: ParticleGeometry?
        get() = if (isSet) {
            ParticleGeometry.AnnularWedge(centerX, centerY, innerRadius, outerRadius, startDeg, sweepDeg)
        } else {
            null
        }
}
