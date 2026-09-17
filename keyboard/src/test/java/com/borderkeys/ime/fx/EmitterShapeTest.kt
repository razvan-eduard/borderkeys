// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmitterShapeTest {

    @Test
    fun `rectangle fractions land exactly on its edges and midpoint`() {
        assertEquals(0f, EmitterShape.rectangleX(0f, 100f, 0f), 0.001f)
        assertEquals(100f, EmitterShape.rectangleX(0f, 100f, 1f), 0.001f)
        assertEquals(50f, EmitterShape.rectangleX(0f, 100f, 0.5f), 0.001f)
        assertEquals(20f, EmitterShape.rectangleY(20f, 220f, 0f), 0.001f)
        assertEquals(220f, EmitterShape.rectangleY(20f, 220f, 1f), 0.001f)
    }

    @Test
    fun `ring fractions land on the four compass points`() {
        val centerX = 100f
        val centerY = 50f
        val radius = 10f
        assertEquals(centerX + radius, EmitterShape.ringX(centerX, radius, 0f), 0.001f)
        assertEquals(centerY, EmitterShape.ringY(centerY, radius, 0f), 0.001f)

        assertEquals(centerX, EmitterShape.ringX(centerX, radius, 0.25f), 0.001f)
        assertEquals(centerY + radius, EmitterShape.ringY(centerY, radius, 0.25f), 0.001f)

        assertEquals(centerX - radius, EmitterShape.ringX(centerX, radius, 0.5f), 0.001f)
        assertEquals(centerY, EmitterShape.ringY(centerY, radius, 0.5f), 0.001f)

        assertEquals(centerX, EmitterShape.ringX(centerX, radius, 0.75f), 0.001f)
        assertEquals(centerY - radius, EmitterShape.ringY(centerY, radius, 0.75f), 0.001f)
    }

    @Test
    fun `line fractions land exactly on its endpoints and midpoint`() {
        assertEquals(10f, EmitterShape.lineX(10f, 90f, 0f), 0.001f)
        assertEquals(90f, EmitterShape.lineX(10f, 90f, 1f), 0.001f)
        assertEquals(50f, EmitterShape.lineX(10f, 90f, 0.5f), 0.001f)
        assertEquals(50f, EmitterShape.lineY(10f, 90f, 0.5f), 0.001f)
    }

    @Test
    fun `rectangle perimeter phases land on the four corners in clockwise order`() {
        val left = 0f
        val top = 0f
        val right = 100f
        val bottom = 50f
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)

        // Phase 0: top-left.
        assertEquals(left, EmitterShape.rectanglePerimeterX(left, top, right, bottom, 0f), 0.001f)
        assertEquals(top, EmitterShape.rectanglePerimeterY(left, top, right, bottom, 0f), 0.001f)

        // End of the top edge: top-right.
        val topRightPhase = width / perimeter
        assertEquals(right, EmitterShape.rectanglePerimeterX(left, top, right, bottom, topRightPhase), 0.001f)
        assertEquals(top, EmitterShape.rectanglePerimeterY(left, top, right, bottom, topRightPhase), 0.001f)

        // End of the right edge: bottom-right.
        val bottomRightPhase = (width + height) / perimeter
        assertEquals(right, EmitterShape.rectanglePerimeterX(left, top, right, bottom, bottomRightPhase), 0.001f)
        assertEquals(bottom, EmitterShape.rectanglePerimeterY(left, top, right, bottom, bottomRightPhase), 0.001f)

        // End of the bottom edge: bottom-left.
        val bottomLeftPhase = (2f * width + height) / perimeter
        assertEquals(left, EmitterShape.rectanglePerimeterX(left, top, right, bottom, bottomLeftPhase), 0.001f)
        assertEquals(bottom, EmitterShape.rectanglePerimeterY(left, top, right, bottom, bottomLeftPhase), 0.001f)

        // Phase 1: back to top-left -- a closed loop.
        assertEquals(left, EmitterShape.rectanglePerimeterX(left, top, right, bottom, 1f), 0.001f)
        assertEquals(top, EmitterShape.rectanglePerimeterY(left, top, right, bottom, 1f), 0.001f)
    }

    @Test
    fun `rectangle perimeter on a zero-area rect does not divide by zero`() {
        assertEquals(5f, EmitterShape.rectanglePerimeterX(5f, 5f, 5f, 5f, 0.5f), 0.001f)
        assertEquals(5f, EmitterShape.rectanglePerimeterY(5f, 5f, 5f, 5f, 0.5f), 0.001f)
    }

    @Test
    fun `rounded rect perimeter with a zero radius is the sharp rectangle walk`() {
        for (phase in listOf(0f, 0.1f, 0.33f, 0.5f, 0.75f, 0.9f, 1f)) {
            assertEquals(
                EmitterShape.rectanglePerimeterX(0f, 0f, 100f, 50f, phase),
                EmitterShape.roundedRectPerimeterX(0f, 0f, 100f, 50f, 0f, phase),
                0.001f,
            )
            assertEquals(
                EmitterShape.rectanglePerimeterY(0f, 0f, 100f, 50f, phase),
                EmitterShape.roundedRectPerimeterY(0f, 0f, 100f, 50f, 0f, phase),
                0.001f,
            )
        }
    }

    /**
     * The property that matters for "dots sit exactly on the outline": every sampled point lies
     * on the rounded rectangle's own boundary -- on a straight edge between the corner arcs, or
     * at exactly the corner radius from that corner's arc centre. A sharp-box walk fails this at
     * every corner, which is the whole reason the rounded walk exists.
     */
    @Test
    fun `rounded rect perimeter points all lie on the rounded outline`() {
        val left = 10f
        val top = 20f
        val right = 110f
        val bottom = 70f
        val r = 12f
        for (i in 0..200) {
            val phase = i / 200f
            val x = EmitterShape.roundedRectPerimeterX(left, top, right, bottom, r, phase)
            val y = EmitterShape.roundedRectPerimeterY(left, top, right, bottom, r, phase)
            val onStraightEdge =
                ((kotlin.math.abs(y - top) < 0.001f || kotlin.math.abs(y - bottom) < 0.001f) &&
                    x >= left + r - 0.001f && x <= right - r + 0.001f) ||
                    ((kotlin.math.abs(x - left) < 0.001f || kotlin.math.abs(x - right) < 0.001f) &&
                        y >= top + r - 0.001f && y <= bottom - r + 0.001f)
            val cornerCentres = listOf(
                left + r to top + r, right - r to top + r, right - r to bottom - r, left + r to bottom - r,
            )
            val onArc = cornerCentres.any { (cx, cy) ->
                kotlin.math.abs(kotlin.math.hypot(x - cx, y - cy) - r) < 0.001f &&
                    // On the outward-facing quarter of its own corner, not the inner one.
                    (x - cx) * (if (cx < (left + right) / 2f) -1f else 1f) >= -0.001f &&
                    (y - cy) * (if (cy < (top + bottom) / 2f) -1f else 1f) >= -0.001f
            }
            assertTrue("phase $phase -> ($x, $y) is off the rounded outline", onStraightEdge || onArc)
        }
    }

    @Test
    fun `rounded rect perimeter walks the corner arcs clockwise between the straight edges`() {
        val r = 10f
        val width = 100f
        val height = 50f
        val arc = (Math.PI / 2.0).toFloat() * r
        val perimeter = 2f * (width - 2f * r + height - 2f * r) + 4f * arc
        // Phase 0: where the top edge starts, just past the top-left arc.
        assertEquals(r, EmitterShape.roundedRectPerimeterX(0f, 0f, width, height, r, 0f), 0.001f)
        assertEquals(0f, EmitterShape.roundedRectPerimeterY(0f, 0f, width, height, r, 0f), 0.001f)
        // Halfway through the top-right arc: 45 degrees off that corner's centre.
        val midArcPhase = (width - 2f * r + arc / 2f) / perimeter
        val d = r * (Math.sqrt(0.5)).toFloat()
        assertEquals(width - r + d, EmitterShape.roundedRectPerimeterX(0f, 0f, width, height, r, midArcPhase), 0.01f)
        assertEquals(r - d, EmitterShape.roundedRectPerimeterY(0f, 0f, width, height, r, midArcPhase), 0.01f)
        // End of that arc: the right edge starts.
        val rightEdgePhase = (width - 2f * r + arc) / perimeter
        assertEquals(width, EmitterShape.roundedRectPerimeterX(0f, 0f, width, height, r, rightEdgePhase), 0.01f)
        assertEquals(r, EmitterShape.roundedRectPerimeterY(0f, 0f, width, height, r, rightEdgePhase), 0.01f)
        // Phase 1: the loop closes back where it started.
        assertEquals(r, EmitterShape.roundedRectPerimeterX(0f, 0f, width, height, r, 1f), 0.01f)
        assertEquals(0f, EmitterShape.roundedRectPerimeterY(0f, 0f, width, height, r, 1f), 0.01f)
    }

    @Test
    fun `rounded rect interior points all lie inside the rounded shape, never in a cut corner`() {
        val left = 10f
        val top = 20f
        val right = 110f
        val bottom = 70f
        val r = 15f
        val cornerCentres = listOf(
            left + r to top + r, right - r to top + r, right - r to bottom - r, left + r to bottom - r,
        )
        for (i in 0..40) for (j in 0..40) {
            val u = i / 40f
            val v = j / 40f
            val x = EmitterShape.roundedRectInteriorX(left, top, right, bottom, r, u, v)
            val y = EmitterShape.roundedRectInteriorY(left, top, right, bottom, r, u, v)
            assertTrue("($x, $y) left the box", x >= left - 0.001f && x <= right + 0.001f && y >= top - 0.001f && y <= bottom + 0.001f)
            val inCornerBand = (x < left + r || x > right - r) && (y < top + r || y > bottom - r)
            if (inCornerBand) {
                val inside = cornerCentres.any { (cx, cy) -> kotlin.math.hypot(x - cx, y - cy) <= r + 0.001f }
                assertTrue("($x, $y) sits in a corner the rounding cut away", inside)
            }
        }
        // A radius of zero is the plain box, corners included.
        assertEquals(left, EmitterShape.roundedRectInteriorX(left, top, right, bottom, 0f, 0f, 0f), 0.001f)
        assertEquals(top, EmitterShape.roundedRectInteriorY(left, top, right, bottom, 0f, 0f, 0f), 0.001f)
    }

    @Test
    fun `annular wedge interior points all lie between the two arcs and within the sweep`() {
        val inner = 40f
        val outer = 100f
        for (i in 0..30) for (j in 0..30) {
            val u = i / 30f
            val v = j / 30f
            val x = EmitterShape.annularWedgeInteriorX(0f, inner, outer, 30f, 60f, u, v)
            val y = EmitterShape.annularWedgeInteriorY(0f, inner, outer, 30f, 60f, u, v)
            val radius = kotlin.math.hypot(x, y)
            assertTrue("radius $radius outside the band", radius >= inner - 0.01f && radius <= outer + 0.01f)
            val angle = Math.toDegrees(kotlin.math.atan2(y, x).toDouble())
            assertTrue("angle $angle outside the sweep", angle >= 30.0 - 0.01 && angle <= 90.0 + 0.01)
        }
        // Uniform by area: the radial midpoint fraction lands past the arithmetic middle radius.
        val midX = EmitterShape.annularWedgeInteriorX(0f, inner, outer, 0f, 0f, 0.5f, 0f)
        assertTrue("$midX should be biased outward for an area-uniform sample", midX > (inner + outer) / 2f)
    }

    @Test
    fun `perimeter lengths and areas match the closed forms`() {
        // A 100x50 box with 10px corners: straight runs plus a full circle of corners.
        val expectedLength = 2f * (80f + 30f) + 2f * Math.PI.toFloat() * 10f
        assertEquals(expectedLength, EmitterShape.roundedRectPerimeterLength(0f, 0f, 100f, 50f, 10f), 0.01f)
        val expectedArea = 100f * 50f - (4f - Math.PI.toFloat()) * 100f
        assertEquals(expectedArea, EmitterShape.roundedRectArea(0f, 0f, 100f, 50f, 10f), 0.01f)
        // A quarter annulus between radii 40 and 100.
        val quarter = (Math.PI / 2.0).toFloat()
        assertEquals(100f * quarter + 40f * quarter + 120f, EmitterShape.annularWedgePerimeterLength(40f, 100f, 90f), 0.01f)
        assertEquals(0.5f * quarter * (10_000f - 1_600f), EmitterShape.annularWedgeArea(40f, 100f, 90f), 0.01f)
    }

    @Test
    fun `rounded rect perimeter clamps an oversized radius to a pill and never leaves the shape`() {
        // Radius bigger than half the height: drawRoundRect clamps it to a pill, so must this.
        val left = 0f
        val top = 0f
        val right = 100f
        val bottom = 40f
        for (i in 0..100) {
            val phase = i / 100f
            val x = EmitterShape.roundedRectPerimeterX(left, top, right, bottom, 500f, phase)
            val y = EmitterShape.roundedRectPerimeterY(left, top, right, bottom, 500f, phase)
            assertTrue("($x, $y) escaped the bounds", x >= left - 0.001f && x <= right + 0.001f)
            assertTrue("($x, $y) escaped the bounds", y >= top - 0.001f && y <= bottom + 0.001f)
        }
        // A zero-area rect still does not divide by zero.
        assertEquals(5f, EmitterShape.roundedRectPerimeterX(5f, 5f, 5f, 5f, 3f, 0.5f), 0.001f)
        assertEquals(5f, EmitterShape.roundedRectPerimeterY(5f, 5f, 5f, 5f, 3f, 0.5f), 0.001f)
    }

    @Test
    fun `annular wedge perimeter phases land on all four segment boundaries in order`() {
        val innerRadius = 4f
        val outerRadius = 5f
        val startDeg = 0f
        val sweepDeg = 90f
        val sweepRad = (sweepDeg * Math.PI / 180.0).toFloat()
        val outerArcLen = outerRadius * sweepRad
        val innerArcLen = innerRadius * sweepRad
        val edgeLen = outerRadius - innerRadius
        val perimeter = outerArcLen + edgeLen + innerArcLen + edgeLen

        // Phase 0: start of the outer arc.
        assertEquals(outerRadius, EmitterShape.annularWedgePerimeterX(0f, innerRadius, outerRadius, startDeg, sweepDeg, 0f), 0.001f)
        assertEquals(0f, EmitterShape.annularWedgePerimeterY(0f, innerRadius, outerRadius, startDeg, sweepDeg, 0f), 0.001f)

        // End of the outer arc: outer radius, at startDeg + sweepDeg (90 deg -- straight up).
        val outerArcEndPhase = outerArcLen / perimeter
        assertEquals(0f, EmitterShape.annularWedgePerimeterX(0f, innerRadius, outerRadius, startDeg, sweepDeg, outerArcEndPhase), 0.001f)
        assertEquals(outerRadius, EmitterShape.annularWedgePerimeterY(0f, innerRadius, outerRadius, startDeg, sweepDeg, outerArcEndPhase), 0.001f)

        // End of the first radial edge: inner radius, same angle (90 deg).
        val firstEdgeEndPhase = (outerArcLen + edgeLen) / perimeter
        assertEquals(0f, EmitterShape.annularWedgePerimeterX(0f, innerRadius, outerRadius, startDeg, sweepDeg, firstEdgeEndPhase), 0.001f)
        assertEquals(innerRadius, EmitterShape.annularWedgePerimeterY(0f, innerRadius, outerRadius, startDeg, sweepDeg, firstEdgeEndPhase), 0.001f)

        // End of the inner arc (walked backward): inner radius, back at startDeg (0 deg).
        val innerArcEndPhase = (outerArcLen + edgeLen + innerArcLen) / perimeter
        assertEquals(innerRadius, EmitterShape.annularWedgePerimeterX(0f, innerRadius, outerRadius, startDeg, sweepDeg, innerArcEndPhase), 0.001f)
        assertEquals(0f, EmitterShape.annularWedgePerimeterY(0f, innerRadius, outerRadius, startDeg, sweepDeg, innerArcEndPhase), 0.001f)

        // Phase 1: back to the outer radius at startDeg -- a closed loop.
        assertEquals(outerRadius, EmitterShape.annularWedgePerimeterX(0f, innerRadius, outerRadius, startDeg, sweepDeg, 1f), 0.001f)
        assertEquals(0f, EmitterShape.annularWedgePerimeterY(0f, innerRadius, outerRadius, startDeg, sweepDeg, 1f), 0.001f)
    }

    @Test
    fun `annular wedge perimeter on a zero-sweep wedge does not divide by zero`() {
        // sweepDeg=0 collapses both arcs to zero length while the two radial edges stay
        // positive (innerRadius 3, outerRadius 5) -- perimeter as a whole is not zero, but the
        // very first segment phase 0 lands in (the outer arc) has zero length of its own, which
        // is exactly the 0f/0f a naive "distance / arcLen" would produce.
        val x = EmitterShape.annularWedgePerimeterX(0f, 3f, 5f, 0f, 0f, 0f)
        val y = EmitterShape.annularWedgePerimeterY(0f, 3f, 5f, 0f, 0f, 0f)
        assertTrue("expected a finite point, got ($x, $y)", x.isFinite() && y.isFinite())
        assertEquals(5f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }
}
