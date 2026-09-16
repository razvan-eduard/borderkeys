// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pool's state and physics, exercised without any real frame clock -- [ParticleField] is
 * what owns a `Choreographer`, deliberately kept out of this class entirely (see
 * [ParticleSimulation]'s own doc), so `advance(rawDeltaSeconds)` here stands in for a real frame
 * exactly the way a synthetic value would.
 */
class ParticleSimulationTest {

    private fun testPreset(
        motion: ParticleMotionKind = ParticleMotionKind.PULSE_IN_PLACE,
        color: ParticleColorKind = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond: Float = 0f,
        maxParticles: Int = 100,
        burstCount: Int = 1,
        lifetimeSeconds: Float = 1f,
    ): ParticleEffectPreset = ParticleEffectPreset(
        motion = motion,
        color = color,
        spawnRatePerSecond = spawnRatePerSecond,
        maxParticles = maxParticles,
        burstCount = burstCount,
        minRadiusPx = 4f,
        maxRadiusPx = 4f,
        lifetimeSeconds = lifetimeSeconds,
    )

    @Test
    fun `a burst spawns exactly the requested count when capacity allows`() {
        val simulation = ParticleSimulation(capacity = 20)
        simulation.preset = testPreset(lifetimeSeconds = 100f)
        simulation.spawnBurstAtPoint(0f, 0f, count = 6)
        assertEquals(6, simulation.liveCount)
    }

    @Test
    fun `spawning past capacity drops the newest rather than crashing`() {
        val simulation = ParticleSimulation(capacity = 3)
        simulation.preset = testPreset(lifetimeSeconds = 100f)
        simulation.spawnBurstAtPoint(0f, 0f, count = 10)
        assertEquals(3, simulation.liveCount)
    }

    @Test
    fun `ambient spawn accumulator converges to the configured rate over many small steps`() {
        val simulation = ParticleSimulation(capacity = 100)
        simulation.preset = testPreset(spawnRatePerSecond = 10f, maxParticles = 100, lifetimeSeconds = 100f)
        simulation.setAmbientPoint(0f, 0f)

        val stepSeconds = 1f / 60f
        var elapsed = 0f
        while (elapsed < 5f) {
            simulation.advance(stepSeconds)
            elapsed += stepSeconds
        }

        // 10/sec for 5 seconds = 50, +-1 for accumulator quantization at the boundary.
        assertTrue("expected ~50 spawned, got ${simulation.liveCount}", simulation.liveCount in 49..51)
    }

    @Test
    fun `a single huge delta is clamped so one stalled frame does not kill a live particle`() {
        val simulation = ParticleSimulation(capacity = 4)
        simulation.preset = testPreset(lifetimeSeconds = 1f)
        simulation.spawnBurstAtPoint(0f, 0f, count = 1)
        assertEquals(1, simulation.liveCount)

        // Unclamped, 5 seconds of age would blow straight past the 1-second lifetime in one
        // step. Clamped to 0.1s, the particle is still well within it.
        simulation.advance(5f)

        assertEquals(1, simulation.liveCount)
    }

    @Test
    fun `speedMultiplier makes particles age and die faster for the same real time`() {
        val slow = ParticleSimulation(capacity = 4)
        slow.preset = testPreset(lifetimeSeconds = 0.2f)
        slow.speedMultiplier = 1f
        slow.spawnBurstAtPoint(0f, 0f, count = 1)

        val fast = ParticleSimulation(capacity = 4)
        fast.preset = testPreset(lifetimeSeconds = 0.2f)
        fast.speedMultiplier = 4f
        fast.spawnBurstAtPoint(0f, 0f, count = 1)

        // A raw delta under the 0.1s clamp, so only speedMultiplier is under test here.
        // slow: 0.09 * 1 = 0.09s of age, under the 0.2s lifetime -- survives.
        // fast: 0.09 * 4 = 0.36s of age, past the 0.2s lifetime -- dies.
        slow.advance(0.09f)
        fast.advance(0.09f)

        assertEquals(1, slow.liveCount)
        assertEquals(0, fast.liveCount)
    }

    @Test
    fun `speedMultiplier does not change the ambient spawn rate itself`() {
        val slow = ParticleSimulation(capacity = 100)
        slow.preset = testPreset(spawnRatePerSecond = 10f, maxParticles = 100, lifetimeSeconds = 100f)
        slow.speedMultiplier = 1f
        slow.setAmbientPoint(0f, 0f)

        val fast = ParticleSimulation(capacity = 100)
        fast.preset = testPreset(spawnRatePerSecond = 10f, maxParticles = 100, lifetimeSeconds = 100f)
        fast.speedMultiplier = 3f
        fast.setAmbientPoint(0f, 0f)

        repeat(60) {
            slow.advance(1f / 60f)
            fast.advance(1f / 60f)
        }

        assertEquals(slow.liveCount, fast.liveCount)
    }

    @Test
    fun `clear removes every live particle and stops ambient emission immediately`() {
        val simulation = ParticleSimulation(capacity = 4)
        simulation.preset = testPreset(lifetimeSeconds = 100f, spawnRatePerSecond = 10f, maxParticles = 4)
        simulation.spawnBurstAtPoint(0f, 0f, count = 3)
        simulation.setAmbientPoint(0f, 0f)
        assertEquals(3, simulation.liveCount)
        assertTrue(simulation.ambientActive)

        simulation.clear()

        assertEquals(0, simulation.liveCount)
        assertTrue(!simulation.ambientActive)
    }

    @Test
    fun `rectangle-perimeter ambient never spawns strictly inside the rectangle`() {
        val simulation = ParticleSimulation(capacity = 50)
        simulation.preset = testPreset(spawnRatePerSecond = 200f, maxParticles = 50, lifetimeSeconds = 100f)
            .copy(motion = ParticleMotionKind.STATIC_FLICKER)
        simulation.setAmbientRectanglePerimeter(0f, 0f, 100f, 50f)

        repeat(60) { simulation.advance(1f / 60f) }

        assertTrue(simulation.liveCount > 0)
        for (slot in 0 until 50) {
            if (!simulation.isLive(slot)) continue
            val x = simulation.xAt(slot)
            val y = simulation.yAt(slot)
            val onEdge = x == 0f || x == 100f || y == 0f || y == 50f
            assertTrue("point ($x,$y) should land on an edge, not strictly inside", onEdge)
        }
    }

    @Test
    fun `arc ambient spawns land on the specified radius`() {
        val simulation = ParticleSimulation(capacity = 50)
        simulation.preset = testPreset(spawnRatePerSecond = 200f, maxParticles = 50, lifetimeSeconds = 100f)
            .copy(motion = ParticleMotionKind.STATIC_FLICKER)
        simulation.setAmbientArc(centerX = 100f, centerY = 100f, radius = 20f, startDeg = 0f, sweepDeg = 90f)

        repeat(60) { simulation.advance(1f / 60f) }

        assertTrue(simulation.liveCount > 0)
        for (slot in 0 until 50) {
            if (!simulation.isLive(slot)) continue
            val dx = simulation.xAt(slot) - 100f
            val dy = simulation.yAt(slot) - 100f
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            assertEquals(20f, distance, 0.01f)
        }
    }

    @Test
    fun `a traveling ambient clusters spawns near one moving point rather than scattering randomly`() {
        val simulation = ParticleSimulation(capacity = 50)
        simulation.preset = testPreset(spawnRatePerSecond = 500f, maxParticles = 50, lifetimeSeconds = 100f)
            .copy(motion = ParticleMotionKind.STATIC_FLICKER, travelLoopsPerSecond = 0.05f)
        simulation.setAmbientRectanglePerimeter(0f, 0f, 100f, 50f)

        // A short burst of frames: the phase barely advances (0.05 loops/sec over a fraction of
        // a second), but the high spawn rate fills the pool -- every live particle should land
        // within a small neighbourhood of the first one, not scattered across the whole
        // perimeter the way a non-traveling (randomly re-rolled) ambient already is.
        repeat(5) { simulation.advance(1f / 60f) }

        val liveSlots = (0 until 50).filter { simulation.isLive(it) }
        assertTrue(liveSlots.isNotEmpty())
        val firstX = simulation.xAt(liveSlots.first())
        val firstY = simulation.yAt(liveSlots.first())
        for (slot in liveSlots) {
            assertEquals(firstX, simulation.xAt(slot), 5f)
            assertEquals(firstY, simulation.yAt(slot), 5f)
        }
    }

    @Test
    fun `densityMultiplier scales the ambient live count`() {
        val base = ParticleSimulation(capacity = 200)
        base.preset = testPreset(spawnRatePerSecond = 10f, maxParticles = 100, lifetimeSeconds = 100f)
        base.setAmbientPoint(0f, 0f)

        val doubled = ParticleSimulation(capacity = 200)
        doubled.preset = testPreset(spawnRatePerSecond = 10f, maxParticles = 100, lifetimeSeconds = 100f)
        doubled.densityMultiplier = 2f
        doubled.setAmbientPoint(0f, 0f)

        val stepSeconds = 1f / 60f
        var elapsed = 0f
        while (elapsed < 5f) {
            base.advance(stepSeconds)
            doubled.advance(stepSeconds)
            elapsed += stepSeconds
        }

        assertTrue(
            "expected roughly double, got base=${base.liveCount} doubled=${doubled.liveCount}",
            doubled.liveCount > base.liveCount * 3 / 2,
        )
    }

    @Test
    fun `densityMultiplier scales a burst's spawned count`() {
        val simulation = ParticleSimulation(capacity = 50)
        simulation.preset = testPreset(lifetimeSeconds = 100f)
        simulation.densityMultiplier = 2f
        simulation.spawnBurstAtPoint(0f, 0f, count = 6)
        assertEquals(12, simulation.liveCount)
    }

    @Test
    fun `widthMultiplier scales the spawned radius`() {
        val simulation = ParticleSimulation(capacity = 1)
        simulation.preset = testPreset(lifetimeSeconds = 100f).copy(minRadiusPx = 4f, maxRadiusPx = 4f)
        simulation.widthMultiplier = 2.5f
        simulation.spawnBurstAtPoint(0f, 0f, count = 1)
        assertEquals(10f, simulation.radiusAt(0), 0.01f)
    }
}
