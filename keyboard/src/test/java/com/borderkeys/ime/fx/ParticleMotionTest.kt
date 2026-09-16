// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain numbers in, one `Float` out -- no `View`/`Context`/Robolectric needed, the same
 *  convention [com.borderkeys.ime.RadialSuggestionMenuViewTest] already uses for its own pure
 *  functions. */
class ParticleMotionTest {

    @Test
    fun `rise-and-shrink rises -- y decreases with age`() {
        val y0 = ParticleMotion.riseAndShrinkY(100f, 0f, 90f)
        val y1 = ParticleMotion.riseAndShrinkY(100f, 1f, 90f)
        assertEquals(100f, y0, 0.001f)
        assertTrue(y1 < y0)
    }

    @Test
    fun `rise-and-shrink drift direction is deterministic from spawnX parity`() {
        val evenDrift = ParticleMotion.riseAndShrinkX(10f, 1f, 20f) - 10f
        val oddDrift = ParticleMotion.riseAndShrinkX(11f, 1f, 20f) - 11f
        assertTrue("even spawnX should drift one way, odd the other", evenDrift * oddDrift < 0f)
    }

    @Test
    fun `rise-and-shrink radius starts at the spawn radius and decays with age`() {
        val atSpawn = ParticleMotion.riseAndShrinkRadius(8f, 0f, 0.5f)
        val later = ParticleMotion.riseAndShrinkRadius(8f, 1f, 0.5f)
        val evenLater = ParticleMotion.riseAndShrinkRadius(8f, 2f, 0.5f)
        assertEquals(8f, atSpawn, 0.001f)
        assertTrue(later < atSpawn)
        assertTrue(evenLater < later)
        assertTrue("never negative", evenLater > 0f)
    }

    @Test
    fun `pulse-in-place starts exactly at the spawn point`() {
        assertEquals(50f, ParticleMotion.pulseInPlaceY(50f, 0f, 6f, 0.8f), 0.001f)
    }

    @Test
    fun `pulse-in-place reaches its peak a quarter period after spawning`() {
        val frequencyHz = 0.8f
        val amplitudePx = 6f
        val quarterPeriodSeconds = 1f / (4f * frequencyHz)
        val y = ParticleMotion.pulseInPlaceY(50f, quarterPeriodSeconds, amplitudePx, frequencyHz)
        assertEquals(50f + amplitudePx, y, 0.01f)
    }

    @Test
    fun `wave drift with zero wavelength is a no-op guard, not a division by zero`() {
        val y = ParticleMotion.waveDriftY(123f, 50f, 1f, 10f, 0f, 3f)
        assertEquals(50f, y, 0.001f)
    }

    @Test
    fun `wave drift phase depends on spawnX -- a field reads as one travelling wave`() {
        val y1 = ParticleMotion.waveDriftY(spawnX = 0f, spawnY = 50f, ageSeconds = 1f, amplitudePx = 10f, wavelengthPx = 20f, speedRadPerSecond = 0f)
        val y2 = ParticleMotion.waveDriftY(spawnX = 5f, spawnY = 50f, ageSeconds = 1f, amplitudePx = 10f, wavelengthPx = 20f, speedRadPerSecond = 0f)
        assertNotEquals(y1, y2, 0.001f)
    }
}
