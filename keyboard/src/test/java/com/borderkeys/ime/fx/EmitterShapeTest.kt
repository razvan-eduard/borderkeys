// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
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
    fun `arc phases land on startDeg and startDeg plus sweepDeg`() {
        val radius = 10f
        assertEquals(radius, EmitterShape.arcX(0f, radius, 0f, 90f, 0f), 0.001f)
        assertEquals(0f, EmitterShape.arcY(0f, radius, 0f, 90f, 0f), 0.001f)

        assertEquals(0f, EmitterShape.arcX(0f, radius, 0f, 90f, 1f), 0.001f)
        assertEquals(radius, EmitterShape.arcY(0f, radius, 0f, 90f, 1f), 0.001f)
    }
}
