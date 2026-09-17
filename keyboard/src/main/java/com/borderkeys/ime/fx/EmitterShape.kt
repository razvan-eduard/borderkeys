// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Resolves a fractional 0..1 position to a pixel offset within some shape, at spawn time only.
 *
 * Not called from the per-frame draw loop -- only once per spawned particle, a handful of times a
 * second at most, so unlike [ParticleMotion]/[ParticleColor] these do not need to justify every
 * cycle: a caller is free to pass in `kotlin.random.Random.nextFloat()` for [fraction01] without
 * it becoming a hot-path concern.
 */
object EmitterShape {

    fun rectangleX(left: Float, right: Float, fraction01: Float): Float = left + (right - left) * fraction01

    fun rectangleY(top: Float, bottom: Float, fraction01: Float): Float = top + (bottom - top) * fraction01

    /** A point on a circle's perimeter -- [angleFraction01] 0..1 is the whole turn, not degrees
     *  or radians, so a caller never has to know this shape's own angle convention. */
    fun ringX(centerX: Float, radius: Float, angleFraction01: Float): Float =
        centerX + radius * cos(angleFraction01 * TWO_PI)

    fun ringY(centerY: Float, radius: Float, angleFraction01: Float): Float =
        centerY + radius * sin(angleFraction01 * TWO_PI)

    fun lineX(x1: Float, x2: Float, fraction01: Float): Float = x1 + (x2 - x1) * fraction01

    fun lineY(y1: Float, y2: Float, fraction01: Float): Float = y1 + (y2 - y1) * fraction01

    /**
     * A point walking a rectangle's perimeter by arc length -- clockwise from the top-left
     * corner (top edge left to right, right edge top to bottom, bottom edge right to left, left
     * edge bottom to top) -- so a uniform [phase01] gives a uniform *distance* around the
     * perimeter, not a distribution skewed toward whichever edge a naive per-edge phase split
     * would hand more phase-space to. Unlike [rectangleX]/[rectangleY], both functions need all
     * four bounds: which edge a given distance falls on depends on the *other* axis's extent
     * too, not just this axis's own two bounds.
     */
    fun rectanglePerimeterX(left: Float, top: Float, right: Float, bottom: Float, phase01: Float): Float {
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)
        if (perimeter <= 0f) {
            return left
        }
        val distance = phase01.coerceIn(0f, 1f) * perimeter
        return when {
            distance <= width -> left + distance
            distance <= width + height -> right
            distance <= 2f * width + height -> right - (distance - width - height)
            else -> left
        }
    }

    fun rectanglePerimeterY(left: Float, top: Float, right: Float, bottom: Float, phase01: Float): Float {
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)
        if (perimeter <= 0f) {
            return top
        }
        val distance = phase01.coerceIn(0f, 1f) * perimeter
        return when {
            distance <= width -> top
            distance <= width + height -> top + (distance - width)
            distance <= 2f * width + height -> bottom
            else -> bottom - (distance - 2f * width - height)
        }
    }

    /**
     * A point walking a *rounded* rectangle's perimeter by arc length -- the exact outline every
     * key, chip and button in this app actually draws (`canvas.drawRoundRect` with the same
     * [cornerRadius]), not the sharp bounding box [rectanglePerimeterX] walks. The difference is
     * the whole reason this exists: a spawn point resolved on the sharp box lands *outside* the
     * drawn shape at every corner, by up to `0.29 * cornerRadius`, and with a theme's rounder
     * keys that is a visible ring of dots hovering off each corner rather than sitting on the
     * outline. Same clockwise walk, same uniform-distance guarantee: top edge, top-right arc,
     * right edge, bottom-right arc, bottom edge, bottom-left arc, left edge, top-left arc.
     * [cornerRadius] is clamped to half the shorter side, exactly as `drawRoundRect` clamps it,
     * so a radius bigger than the shape (a pill) walks the pill and never a self-intersecting
     * path; `0f` reproduces [rectanglePerimeterX]/[rectanglePerimeterY] to the pixel.
     */
    fun roundedRectPerimeterX(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        phase01: Float,
    ): Float = roundedRectPerimeterPoint(left, top, right, bottom, cornerRadius, phase01).x

    fun roundedRectPerimeterY(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        phase01: Float,
    ): Float = roundedRectPerimeterPoint(left, top, right, bottom, cornerRadius, phase01).y

    /**
     * A perimeter point *with its outward unit normal* -- what the outline layer needs to push a
     * particle away from the element rather than merely place it on the edge. Fills [out] with
     * x, y, normalX, normalY (four floats) and allocates nothing; the X/Y accessors above are
     * the same walk read one coordinate at a time.
     */
    fun roundedRectPerimeterSample(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        phase01: Float,
        out: FloatArray,
    ) {
        val p = roundedRectPerimeterPoint(left, top, right, bottom, cornerRadius, phase01)
        out[0] = p.x
        out[1] = p.y
        out[2] = p.nx
        out[3] = p.ny
    }

    /** [roundedRectPerimeterSample]'s equivalent for the wedge. On the inner arc the outward
     *  normal points toward the centre -- away from the wedge, into the hole -- and on each
     *  radial edge it points away from the wedge's own sweep. */
    fun annularWedgePerimeterSample(
        centerX: Float,
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        phase01: Float,
        out: FloatArray,
    ) {
        val point = annularWedgePerimeterPoint(innerRadius, outerRadius, startDeg, sweepDeg, phase01)
        val angleRad = point.angleDeg * DEG_TO_RAD
        out[0] = centerX + point.radius * cos(angleRad)
        out[1] = centerY + point.radius * sin(angleRad)
        out[2] = point.nx
        out[3] = point.ny
    }

    /** Resolved once for both axes, for the same reason [WedgePoint] is: an arc segment's X and
     *  Y come from one angle, and resolving them separately could round a segment boundary
     *  differently per axis and report a point off the perimeter. [nx]/[ny] is the outward unit
     *  normal at that point -- straight out of a straight edge, radial on a corner arc. */
    private data class PlanePoint(val x: Float, val y: Float, val nx: Float = 0f, val ny: Float = 0f)

    private fun roundedRectPerimeterPoint(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        phase01: Float,
    ): PlanePoint {
        val width = right - left
        val height = bottom - top
        val r = cornerRadius.coerceIn(0f, (kotlin.math.min(width, height) / 2f).coerceAtLeast(0f))
        val straightW = width - 2f * r
        val straightH = height - 2f * r
        val arcLen = (PI / 2.0).toFloat() * r
        val perimeter = 2f * (straightW + straightH) + 4f * arcLen
        if (perimeter <= 0f) {
            return PlanePoint(left, top, 0f, -1f)
        }
        var d = phase01.coerceIn(0f, 1f) * perimeter
        // Top edge, left to right.
        if (d <= straightW) {
            return PlanePoint(left + r + d, top, 0f, -1f)
        }
        d -= straightW
        // Top-right arc: -90 deg (straight up) sweeping clockwise to 0 deg (straight right).
        if (d <= arcLen) {
            return arcPoint(right - r, top + r, r, -90f, d, arcLen)
        }
        d -= arcLen
        // Right edge, top to bottom.
        if (d <= straightH) {
            return PlanePoint(right, top + r + d, 1f, 0f)
        }
        d -= straightH
        // Bottom-right arc: 0 deg to 90 deg (straight down).
        if (d <= arcLen) {
            return arcPoint(right - r, bottom - r, r, 0f, d, arcLen)
        }
        d -= arcLen
        // Bottom edge, right to left.
        if (d <= straightW) {
            return PlanePoint(right - r - d, bottom, 0f, 1f)
        }
        d -= straightW
        // Bottom-left arc: 90 deg to 180 deg (straight left).
        if (d <= arcLen) {
            return arcPoint(left + r, bottom - r, r, 90f, d, arcLen)
        }
        d -= arcLen
        // Left edge, bottom to top.
        if (d <= straightH) {
            return PlanePoint(left, bottom - r - d, -1f, 0f)
        }
        d -= straightH
        // Top-left arc: 180 deg to 270 deg, closing the loop back at the top edge's start.
        return arcPoint(left + r, top + r, r, 180f, d, arcLen)
    }

    /** The length of the outline [roundedRectPerimeterX] walks -- what the engine scales an
     *  outline's spawn rate by, so a long edge is not starved of dots relative to a short one. */
    fun roundedRectPerimeterLength(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float): Float {
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val r = cornerRadius.coerceIn(0f, kotlin.math.min(width, height) / 2f)
        return 2f * (width - 2f * r + height - 2f * r) + 2f * PI.toFloat() * r
    }

    /** The area [roundedRectInteriorX] fills -- the fill layer's own equivalent of
     *  [roundedRectPerimeterLength]. */
    fun roundedRectArea(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float): Float {
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val r = cornerRadius.coerceIn(0f, kotlin.math.min(width, height) / 2f)
        // The full box minus the four corner squares' own uncovered corners.
        return width * height - (4f - PI.toFloat()) * r * r
    }

    /**
     * A point *inside* a rounded rectangle, from two independent 0..1 fractions -- uniform over
     * the box, with the sliver outside each corner arc folded back onto the arc rather than
     * rejected, so a caller never loops and never allocates. Every point returned lies inside
     * or on the drawn shape; none lands in the corner the rounding cut away. This is what a
     * fill layer spawns from: the element's own interior, not a centre point and not its sharp
     * bounding box.
     */
    fun roundedRectInteriorX(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        u01: Float,
        v01: Float,
    ): Float = roundedRectInteriorPoint(left, top, right, bottom, cornerRadius, u01, v01).x

    fun roundedRectInteriorY(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        u01: Float,
        v01: Float,
    ): Float = roundedRectInteriorPoint(left, top, right, bottom, cornerRadius, u01, v01).y

    private fun roundedRectInteriorPoint(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        u01: Float,
        v01: Float,
    ): PlanePoint {
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val r = cornerRadius.coerceIn(0f, kotlin.math.min(width, height) / 2f)
        val x = left + width * u01.coerceIn(0f, 1f)
        val y = top + height * v01.coerceIn(0f, 1f)
        if (r <= 0f) {
            return PlanePoint(x, y)
        }
        val cx = when {
            x < left + r -> left + r
            x > right - r -> right - r
            else -> return PlanePoint(x, y)
        }
        val cy = when {
            y < top + r -> top + r
            y > bottom - r -> bottom - r
            else -> return PlanePoint(x, y)
        }
        // In one of the four corner squares: keep the point if it is within the arc, otherwise
        // pull it radially in onto the arc itself.
        val dx = x - cx
        val dy = y - cy
        val d = kotlin.math.hypot(dx, dy)
        if (d <= r) {
            return PlanePoint(x, y)
        }
        return PlanePoint(cx + dx / d * r, cy + dy / d * r)
    }

    /** The perimeter length [annularWedgePerimeterX] walks -- see [roundedRectPerimeterLength]. */
    fun annularWedgePerimeterLength(innerRadius: Float, outerRadius: Float, sweepDeg: Float): Float {
        val sweepRad = kotlin.math.abs(sweepDeg) * DEG_TO_RAD
        val edges = if (kotlin.math.abs(sweepDeg) >= 360f) 0f else 2f * (outerRadius - innerRadius).coerceAtLeast(0f)
        return outerRadius * sweepRad + innerRadius * sweepRad + edges
    }

    /** The area [annularWedgeInteriorX] fills -- see [roundedRectArea]. */
    fun annularWedgeArea(innerRadius: Float, outerRadius: Float, sweepDeg: Float): Float {
        val sweepRad = kotlin.math.abs(sweepDeg) * DEG_TO_RAD
        return 0.5f * sweepRad * (outerRadius * outerRadius - innerRadius * innerRadius).coerceAtLeast(0f)
    }

    /**
     * A point *inside* an annular wedge, from two independent 0..1 fractions -- uniform by area,
     * not by radius: [radiusFraction01] is mapped through the square root of the squared radii
     * so the wider outer band gets its fair share of points rather than the same count as the
     * narrow inner one. [angleFraction01] sweeps [startDeg] to [startDeg] + [sweepDeg].
     */
    fun annularWedgeInteriorX(
        centerX: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        radiusFraction01: Float,
        angleFraction01: Float,
    ): Float {
        val radius = wedgeInteriorRadius(innerRadius, outerRadius, radiusFraction01)
        return centerX + radius * cos((startDeg + sweepDeg * angleFraction01.coerceIn(0f, 1f)) * DEG_TO_RAD)
    }

    fun annularWedgeInteriorY(
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        radiusFraction01: Float,
        angleFraction01: Float,
    ): Float {
        val radius = wedgeInteriorRadius(innerRadius, outerRadius, radiusFraction01)
        return centerY + radius * sin((startDeg + sweepDeg * angleFraction01.coerceIn(0f, 1f)) * DEG_TO_RAD)
    }

    private fun wedgeInteriorRadius(innerRadius: Float, outerRadius: Float, fraction01: Float): Float {
        val inner2 = innerRadius * innerRadius
        val outer2 = outerRadius * outerRadius
        return kotlin.math.sqrt(inner2 + (outer2 - inner2) * fraction01.coerceIn(0f, 1f))
    }

    /** A point [distance] along a quarter arc of [radius] around ([cx], [cy]) that starts at
     *  [startDeg] and sweeps 90 degrees clockwise -- [android.graphics.Canvas.drawArc]'s own
     *  convention, the same one the wedge walk below uses. */
    private fun arcPoint(cx: Float, cy: Float, radius: Float, startDeg: Float, distance: Float, arcLen: Float): PlanePoint {
        val frac = if (arcLen > 0f) (distance / arcLen).coerceIn(0f, 1f) else 0f
        val angleRad = (startDeg + 90f * frac) * DEG_TO_RAD
        val nx = cos(angleRad)
        val ny = sin(angleRad)
        // On a convex corner arc the outward normal is simply the radial direction.
        return PlanePoint(cx + radius * nx, cy + radius * ny, nx, ny)
    }

    /**
     * A point walking an annular wedge's perimeter by arc length -- the radial suggestion ring's
     * own highlighted wedge, bounded by two arcs (at [innerRadius] and [outerRadius]) and the
     * two straight edges connecting them at [startDeg] and [startDeg] + [sweepDeg]. Walks the
     * same four segments in the same order [com.borderkeys.ime.RadialSuggestionMenuView] used to
     * build this shape by hand as a `Path` before it became [ParticleGeometry.AnnularWedge] --
     * outer arc, the far radial edge, inner arc backward, the near radial edge closing the loop
     * -- so a uniform [phase01] gives a uniform *distance* around the perimeter, the same
     * reasoning [rectanglePerimeterX] already gives for a rectangle. [startDeg]/[sweepDeg] use
     * [android.graphics.Canvas.drawArc]'s own degree convention, the same one
     * [com.borderkeys.ime.RadialSuggestionMenuView.wedgeCentreDegrees] already uses.
     */
    fun annularWedgePerimeterX(
        centerX: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        phase01: Float,
    ): Float {
        val point = annularWedgePerimeterPoint(innerRadius, outerRadius, startDeg, sweepDeg, phase01)
        return centerX + point.radius * cos(point.angleDeg * DEG_TO_RAD)
    }

    fun annularWedgePerimeterY(
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        phase01: Float,
    ): Float {
        val point = annularWedgePerimeterPoint(innerRadius, outerRadius, startDeg, sweepDeg, phase01)
        return centerY + point.radius * sin(point.angleDeg * DEG_TO_RAD)
    }

    /** The (angle, radius) pair [annularWedgePerimeterX]/[annularWedgePerimeterY] each resolve
     *  [phase01] to -- shared so the two never independently round a segment boundary
     *  differently and report a point that is not actually on the perimeter, the way the
     *  simpler [rectanglePerimeterX]/[rectanglePerimeterY] (four straight edges, no rounding
     *  risk) can safely resolve X and Y independently. */
    private data class WedgePoint(val angleDeg: Float, val radius: Float, val nx: Float = 0f, val ny: Float = 0f)

    private fun annularWedgePerimeterPoint(
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        phase01: Float,
    ): WedgePoint {
        val sweepRad = kotlin.math.abs(sweepDeg) * DEG_TO_RAD
        val outerArcLen = outerRadius * sweepRad
        val innerArcLen = innerRadius * sweepRad
        // A full turn is a complete annulus: its two radial edges coincide and are not an edge
        // at all, so they get no length -- otherwise a seam of dots would sit at startDeg.
        val edgeLen = if (kotlin.math.abs(sweepDeg) >= 360f) 0f else (outerRadius - innerRadius).coerceAtLeast(0f)
        val perimeter = outerArcLen + edgeLen + innerArcLen + edgeLen
        if (perimeter <= 0f) {
            return WedgePoint(startDeg, outerRadius, cos(startDeg * DEG_TO_RAD), sin(startDeg * DEG_TO_RAD))
        }
        val distance = phase01.coerceIn(0f, 1f) * perimeter
        val endDeg = startDeg + sweepDeg
        // The direction of increasing angle at an angle a is (-sin a, cos a); a radial edge's
        // outward normal is that tangent pointing away from the wedge's own sweep.
        val sign = if (sweepDeg >= 0f) 1f else -1f
        return when {
            distance <= outerArcLen -> {
                val frac = if (outerArcLen > 0f) distance / outerArcLen else 0f
                val a = startDeg + sweepDeg * frac
                WedgePoint(a, outerRadius, cos(a * DEG_TO_RAD), sin(a * DEG_TO_RAD))
            }
            distance <= outerArcLen + edgeLen -> {
                val e = endDeg * DEG_TO_RAD
                WedgePoint(endDeg, outerRadius - (distance - outerArcLen), -sin(e) * sign, cos(e) * sign)
            }
            distance <= outerArcLen + edgeLen + innerArcLen -> {
                val frac = if (innerArcLen > 0f) (distance - outerArcLen - edgeLen) / innerArcLen else 0f
                val a = startDeg + sweepDeg * (1f - frac)
                WedgePoint(a, innerRadius, -cos(a * DEG_TO_RAD), -sin(a * DEG_TO_RAD))
            }
            else -> {
                val s = startDeg * DEG_TO_RAD
                WedgePoint(startDeg, innerRadius + (distance - outerArcLen - edgeLen - innerArcLen), sin(s) * sign, -cos(s) * sign)
            }
        }
    }

    private val TWO_PI = (2.0 * PI).toFloat()
    private val DEG_TO_RAD = (PI / 180.0).toFloat()
}
