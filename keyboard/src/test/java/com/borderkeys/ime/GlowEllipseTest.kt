// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class GlowEllipseTest {

    @Test
    fun `equal variances and no covariance make a circle`() {
        val circle = GlowEllipse.of(0.09f, 0.09f, 0f, 0f)
        assertEquals(0.3f, circle.major, 1e-6f)
        assertEquals(0.3f, circle.minor, 1e-6f)
    }

    @Test
    fun `a wider spread across than down lies flat, and a taller one upright`() {
        val flat = GlowEllipse.of(0.16f, 0.04f, 0f, 0f)
        assertEquals(0.4f, flat.major, 1e-6f)
        assertEquals(0.2f, flat.minor, 1e-6f)
        assertEquals(0f, flat.degrees, 1e-4f)
        assertEquals(90f, GlowEllipse.of(0.04f, 0.16f, 0f, 0f).degrees, 1e-4f)
    }

    @Test
    fun `offsets that grow together tilt it forty-five degrees`() {
        val tilted = GlowEllipse.of(0.09f, 0.09f, 0.05f, 0f)
        assertEquals(45f, tilted.degrees, 1e-4f)
        assertEquals(kotlin.math.sqrt(0.14f), tilted.major, 1e-6f)
        assertEquals(kotlin.math.sqrt(0.04f), tilted.minor, 1e-6f)
    }

    @Test
    fun `no spread at all keeps the floor`() {
        val dot = GlowEllipse.of(0f, 0f, 0f, 2f)
        assertEquals(2f, dot.major, 0f)
        assertEquals(2f, dot.minor, 0f)
    }
}
