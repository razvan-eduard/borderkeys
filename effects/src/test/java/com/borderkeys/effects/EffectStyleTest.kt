// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.effects

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectStyleTest {

    private val travel = 400f
    private val steps = (0..20).map { it / 20f }

    /**
     * The curve the accepted-word animation had before it became a style, kept to the letter:
     * this is the one effect that already shipped, and a refactor that quietly changed how it
     * looks would be a redesign nobody asked for.
     */
    @Test
    fun `rise and fade is what the accepted word always did`() {
        for (p in steps) {
            val eased = p * p * (3f - 2f * p)
            val transform = EffectStyle.RiseFade.at(p, travel)
            assertEquals("dy at $p", -travel * 0.5f * eased, transform.dy, 0.0001f)
            assertEquals("alpha at $p", sin(p * PI.toFloat()), transform.alpha, 0.0001f)
            assertEquals("scale at $p", 1f, transform.scale, 0.0001f)
        }
    }

    @Test
    fun `every style stays inside what a canvas can draw`() {
        for (style in EffectStyle.entries) {
            for (p in steps) {
                val t = style.at(p, travel)
                assertTrue("$style alpha $p = ${t.alpha}", t.alpha in 0f..1f)
                assertTrue("$style scale $p = ${t.scale}", t.scale >= 0f)
                assertTrue("$style dy $p", t.dy.isFinite() && t.dx.isFinite())
            }
        }
    }

    @Test
    fun `every style begins and ends without a jump`() {
        for (style in EffectStyle.entries) {
            val start = style.at(0f, travel)
            val end = style.at(1f, travel)
            assertTrue("$style starts visible or rising into view", start.alpha <= 1f)
            assertTrue("$style has faded out by the end: ${end.alpha}", end.alpha <= 0.02f)
        }
    }

    @Test
    fun `travel scales the movement rather than the opacity`() {
        for (style in EffectStyle.entries) {
            val small = style.at(0.5f, 100f)
            val large = style.at(0.5f, 1000f)
            assertEquals("$style opacity is independent of size", small.alpha, large.alpha, 0.0001f)
            assertTrue("$style moves further when there is further to move",
                       kotlin.math.abs(large.dy) >= kotlin.math.abs(small.dy))
        }
    }
}
