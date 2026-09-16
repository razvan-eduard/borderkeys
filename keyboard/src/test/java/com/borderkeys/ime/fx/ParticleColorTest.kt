// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-rolled colour arithmetic, checked against known values -- deliberately never calling
 * `android.graphics.Color`, so these stay meaningful under `:keyboard`'s own
 * `unitTests.isReturnDefaultValues` (see [ParticleColor]'s own doc for why that matters: an
 * unmocked `Color.*` call would silently return `0` here instead of a real answer).
 */
class ParticleColorTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun `lerpArgb at the midpoint is mid-grey between black and white`() {
        assertEquals(0xFF7F7F7F.toInt(), ParticleColor.lerpArgb(black, white, 0.5f))
    }

    @Test
    fun `lerpArgb clamps t below zero and above one`() {
        assertEquals(black, ParticleColor.lerpArgb(black, white, -5f))
        assertEquals(white, ParticleColor.lerpArgb(black, white, 5f))
    }

    @Test
    fun `crossfade at full life is the primary colour, at zero life the secondary`() {
        assertEquals(red, ParticleColor.crossfade(1f, red, blue))
        assertEquals(blue, ParticleColor.crossfade(0f, red, blue))
    }

    @Test
    fun `thermal gradient is hot at full life and cold at zero life`() {
        assertEquals(red, ParticleColor.thermalGradient(1f, red, blue))
        assertEquals(blue, ParticleColor.thermalGradient(0f, red, blue))
    }

    @Test
    fun `hsvToRgb matches the primary and secondary hues exactly`() {
        assertEquals(red, ParticleColor.hsvToRgb(0f, 1f, 1f))
        assertEquals(green, ParticleColor.hsvToRgb(120f, 1f, 1f))
        assertEquals(blue, ParticleColor.hsvToRgb(240f, 1f, 1f))
    }

    @Test
    fun `hsvToRgb wraps hue the same way past 360 degrees`() {
        assertEquals(ParticleColor.hsvToRgb(10f, 1f, 1f), ParticleColor.hsvToRgb(370f, 1f, 1f))
        assertEquals(ParticleColor.hsvToRgb(10f, 1f, 1f), ParticleColor.hsvToRgb(-350f, 1f, 1f))
    }

    @Test
    fun `hue cycle is offset by spawnX -- a field reads as one moving band, not one flash`() {
        val hueAtZero = ParticleColor.hueCycle(spawnX = 0f, ageSeconds = 0f, hueOffsetDegPerPx = 1.5f, degPerSecond = 90f)
        val hueAtFifty = ParticleColor.hueCycle(spawnX = 50f, ageSeconds = 0f, hueOffsetDegPerPx = 1.5f, degPerSecond = 90f)
        assertNotEquals(hueAtZero, hueAtFifty)
    }

    @Test
    fun `single-colour pulse only ever changes alpha, never the colour underneath`() {
        val pulsed = ParticleColor.singleColorPulse(red, ageSeconds = 0.37f, frequencyHz = 2.5f)
        assertEquals(red and 0x00FFFFFF, pulsed and 0x00FFFFFF)
        val alpha = (pulsed ushr 24) and 0xFF
        assertTrue("alpha stays a valid byte", alpha in 0..255)
    }
}
