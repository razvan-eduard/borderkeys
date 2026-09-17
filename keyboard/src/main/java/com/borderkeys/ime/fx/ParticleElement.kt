// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * A graphic element particles can affect -- a key, a suggestion chip, a quick-action button, a
 * panel row, the radial ring's highlighted wedge or its centre button, the settings screen's
 * preview chip. The one thing a subclass has to say is [geometry]: the shape it actually draws,
 * in its host's own pixel space. Everything about *where* particles then go is decided here and
 * in the engine, never by the element and never by the host that owns it:
 *
 * - [outlineGeometry] is what the outline layer traces -- dots walk its perimeter by arc length
 *   (rounded corners included, see [EmitterShape.roundedRectPerimeterX]) and a stroke style
 *   draws the same shape. By default the element's own [geometry].
 * - [fillGeometry] is what the fill layer fills -- bursts and ambient trickles spawn uniformly
 *   inside it. By default the element's own [geometry] too.
 *
 * Both are `open` only so an element whose fill region genuinely differs from its outline can
 * say so; nothing in this app needs that today. A host never hands the engine a rectangle, a
 * centre point, a radius or a path of its own -- it hands it an element, through
 * [ParticleSurface.press]/[ParticleSurface.hold]/[ParticleSurface.release] -- so the shape a
 * stroke traces, the perimeter dots sit on and the interior a burst spawns in are always the
 * one shape the element itself draws. That is the whole point of this class existing: the
 * earlier design let every host derive its own rectangle at the call site, and each one drifted
 * from what was on screen in its own way (a settings chip traced its 48dp touch target, not its
 * 32dp surface; a key burst from a point rather than its area; the ring's cancel button had no
 * geometry at all).
 *
 * `null` from [geometry] means "not laid out yet, nothing to affect" and is harmless everywhere.
 *
 * Deliberately an abstract class rather than an interface on the host `View`: a `View` is not
 * an element -- `KeyboardCanvasView` draws dozens of keys from parallel arrays, the strip draws
 * several chips -- so the element is its own small object the host points at the thing
 * currently pressed or highlighted. Hosts that have exactly one such thing keep one reusable
 * instance and set its index/rect before each call, so a press never allocates in the hot path
 * beyond the geometry value itself.
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
 * The one reusable element most hosts need: a rounded rectangle whose bounds the host [set]s
 * right before pressing or holding it -- a key read off the geometry arrays, the strip's applied
 * chip, a quick-action button's pressed surface, a panel row, the ring's centre button (a circle
 * is [ParticleGeometry.circle]). One instance per host, re-pointed per press, so the press path
 * allocates nothing but the geometry value handed to the engine. Unset ([set] never called, or
 * [clear]ed) reads as `null`: nothing to affect.
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

/** [RoundedRectElement]'s equivalent for the radial ring's wedge -- see
 *  [ParticleGeometry.AnnularWedge] for the parameters. */
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
