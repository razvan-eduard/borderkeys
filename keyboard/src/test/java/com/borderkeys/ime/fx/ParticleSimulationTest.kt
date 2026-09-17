// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            val r = simulation.radiusAt(slot)
            // Pushed out of the rectangle along the edge's normal by exactly its own radius: the
            // whole dot sits outside, tangent to the edge, never inside.
            val outsideByRadius = kotlin.math.abs(x + r) < 0.01f || kotlin.math.abs(x - 100f - r) < 0.01f ||
                kotlin.math.abs(y + r) < 0.01f || kotlin.math.abs(y - 50f - r) < 0.01f
            assertTrue("point ($x,$y) should sit just outside an edge, not on or inside it", outsideByRadius)
        }
    }

    @Test
    fun `annular-wedge-perimeter ambient never spawns strictly inside the wedge`() {
        val simulation = ParticleSimulation(capacity = 50)
        simulation.preset = testPreset(spawnRatePerSecond = 200f, maxParticles = 50, lifetimeSeconds = 100f)
            .copy(motion = ParticleMotionKind.STATIC_FLICKER)
        // startDeg=0/sweepDeg=90 makes both radial edges axis-aligned (dy=0 at 0 deg, dx=0 at 90
        // deg -- Canvas.drawArc's own convention, clockwise from +X), so this test can check
        // each of the wedge's four segments with a plain coordinate comparison rather than a
        // general angle one.
        simulation.setAmbientAnnularWedgePerimeter(
            centerX = 100f, centerY = 100f, innerRadius = 15f, outerRadius = 20f, startDeg = 0f, sweepDeg = 90f,
        )

        repeat(60) { simulation.advance(1f / 60f) }

        assertTrue(simulation.liveCount > 0)
        for (slot in 0 until 50) {
            if (!simulation.isLive(slot)) continue
            val dx = simulation.xAt(slot) - 100f
            val dy = simulation.yAt(slot) - 100f
            val radius = kotlin.math.sqrt(dx * dx + dy * dy)
            val r = simulation.radiusAt(slot)
            // Just outside the wedge, by exactly the dot's own radius: past the outer arc, inside
            // the inner arc (toward the centre), or beside a radial edge -- never in the wedge.
            val offOuterArc = kotlin.math.abs(radius - 20f - r) < 0.01f
            val offInnerArc = kotlin.math.abs(radius - 15f + r) < 0.01f
            val offRadialEdge = (kotlin.math.abs(dy + r) < 0.01f && dx in 14.99f..20.01f) ||
                (kotlin.math.abs(dx + r) < 0.01f && dy in 14.99f..20.01f)
            assertTrue(
                "point (dx=$dx, dy=$dy), radius=$radius should sit just outside the wedge's perimeter",
                offOuterArc || offInnerArc || offRadialEdge,
            )
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

    @Test
    fun `widthMultiplier at its minimum still floors the spawned radius to a legible size`() {
        val simulation = ParticleSimulation(capacity = 1)
        simulation.preset = testPreset(lifetimeSeconds = 100f).copy(minRadiusPx = 1.5f, maxRadiusPx = 1.5f)
        simulation.widthMultiplier = 0.5f
        simulation.spawnBurstAtPoint(0f, 0f, count = 1)
        assertEquals(ParticleSimulation.MIN_LEGIBLE_SIZE_PX, simulation.radiusAt(0), 0.01f)
    }

    @Test
    fun `currentAmbientOutlineShape reports a rectangle perimeter's own bounds and corner radius`() {
        val simulation = ParticleSimulation(capacity = 1)
        simulation.setAmbientRectanglePerimeter(1f, 2f, 3f, 4f, cornerRadius = 0.5f)
        val out = FloatArray(5)
        val shape = simulation.currentAmbientOutlineShape(out)
        assertEquals(ParticleSimulation.AmbientOutlineShape.RECTANGLE_PERIMETER, shape)
        assertEquals(1f, out[0], 0f)
        assertEquals(2f, out[1], 0f)
        assertEquals(3f, out[2], 0f)
        assertEquals(4f, out[3], 0f)
        assertEquals(0.5f, out[4], 0f)
    }

    @Test
    fun `an interior ambient spawns inside its shape, and a bigger shape keeps more particles alive`() {
        val small = ParticleSimulation(capacity = 200)
        small.preset = ParticleOutlineStylePresets.PULSE
        small.setAmbientRoundedRectInterior(0f, 0f, 60f, 60f, cornerRadius = 30f)
        val large = ParticleSimulation(capacity = 200)
        large.preset = ParticleOutlineStylePresets.PULSE
        large.setAmbientRoundedRectInterior(0f, 0f, 400f, 400f, cornerRadius = 40f)
        repeat(120) {
            small.advance(0.05f)
            large.advance(0.05f)
        }
        for (slot in 0 until small.capacity) {
            if (!small.isLive(slot)) continue
            val d = kotlin.math.hypot(small.xAt(slot) - 30f, small.yAt(slot) - 30f)
            assertTrue("slot $slot spawned outside the circle", d <= 30f + 0.01f)
        }
        assertTrue("nothing spawned", small.liveCount > 0)
        // The 400px square is well past REFERENCE_AREA_PX, so its cap scales up to the ceiling.
        assertTrue(
            "large ${large.liveCount} should hold more than small ${small.liveCount}",
            large.liveCount > small.liveCount,
        )
    }

    @Test
    fun `a wedge interior burst lands inside the wedge`() {
        val simulation = ParticleSimulation(capacity = 64)
        simulation.preset = ParticleOutlineStylePresets.PULSE
        simulation.spawnBurstInAnnularWedge(
            centerX = 0f, centerY = 0f, innerRadius = 50f, outerRadius = 120f, startDeg = 0f, sweepDeg = 90f, count = 20,
        )
        var seen = 0
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) continue
            seen++
            val x = simulation.xAt(slot)
            val y = simulation.yAt(slot)
            val radius = kotlin.math.hypot(x, y)
            assertTrue("slot $slot at radius $radius", radius >= 50f - 0.01f && radius <= 120f + 0.01f)
            assertTrue("slot $slot outside the quadrant", x >= -0.01f && y >= -0.01f)
        }
        assertTrue("burst spawned nothing", seen > 0)
    }

    @Test
    fun `a rounded perimeter ambient spawns just outside the rounded outline, never at a sharp corner`() {
        // A square with corners rounded to a full circle: every dot must sit tangent to that
        // circle from the outside -- its centre exactly one radius past the outline.
        val simulation = ParticleSimulation(capacity = 64)
        simulation.preset = ParticleOutlineStylePresets.PULSE
        simulation.densityMultiplier = 2f
        simulation.setAmbientRectanglePerimeter(0f, 0f, 100f, 100f, cornerRadius = 50f)
        repeat(60) { simulation.advance(0.1f) }
        var seen = 0
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) continue
            seen++
            val distance = kotlin.math.hypot(simulation.xAt(slot) - 50f, simulation.yAt(slot) - 50f)
            // Tangent to the circle at spawn, then drifting radially away -- never closer.
            assertTrue(
                "slot $slot at distance $distance is not outside the circle",
                distance >= 50f + simulation.radiusAt(slot) - 0.01f,
            )
        }
        assertTrue("nothing spawned", seen > 0)
    }

    @Test
    fun `an emission cone only spawns on the outline facing its direction, and outward motion never re-enters`() {
        val simulation = ParticleSimulation(capacity = 64)
        simulation.preset = ParticleOutlineStylePresets.FIRE
        simulation.densityMultiplier = 2f
        simulation.setAmbientRectanglePerimeter(0f, 0f, 100f, 50f, cornerRadius = 8f)
        repeat(40) { simulation.advance(0.05f) }
        var seen = 0
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) continue
            seen++
            val y = simulation.yAt(slot)
            val x = simulation.xAt(slot)
            // Fire faces up: embers come only off the top edge and the upper corner arcs (so
            // never lower than the corner radius), and every one of them sits outside the
            // rectangle as it rises -- never inside, never off the bottom edge.
            val inside = x > 0f && x < 100f && y > 0f && y < 50f
            assertTrue("slot $slot at ($x, $y) is inside the shape", !inside)
            assertTrue("slot $slot at ($x, $y) came off somewhere other than the top", y < 8f)
        }
        assertTrue("nothing spawned", seen > 0)
    }

    @Test
    fun `currentAmbientOutlineShape reports an annular wedge's own centre, radii, start and sweep`() {
        val simulation = ParticleSimulation(capacity = 1)
        simulation.setAmbientAnnularWedgePerimeter(
            centerX = 10f, centerY = 20f, innerRadius = 4f, outerRadius = 5f, startDeg = 30f, sweepDeg = 60f,
        )
        val out = FloatArray(6)
        val shape = simulation.currentAmbientOutlineShape(out)
        assertEquals(ParticleSimulation.AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER, shape)
        assertEquals(10f, out[0], 0f)
        assertEquals(20f, out[1], 0f)
        assertEquals(4f, out[2], 0f)
        assertEquals(5f, out[3], 0f)
        assertEquals(30f, out[4], 0f)
        assertEquals(60f, out[5], 0f)
    }

    @Test
    fun `currentAmbientOutlineShape is null while ambient is off, or is a point or plain-fill rectangle`() {
        val simulation = ParticleSimulation(capacity = 1)
        val out = FloatArray(5)
        assertNull(simulation.currentAmbientOutlineShape(out))

        simulation.setAmbientPoint(0f, 0f)
        assertNull(simulation.currentAmbientOutlineShape(out))

        simulation.setAmbientRectangle(0f, 0f, 10f, 10f)
        assertNull(simulation.currentAmbientOutlineShape(out))

        simulation.stopAmbient()
        simulation.setAmbientRectanglePerimeter(0f, 0f, 10f, 10f)
        assertNotNull(simulation.currentAmbientOutlineShape(out))
        simulation.stopAmbient()
        assertNull(simulation.currentAmbientOutlineShape(out))
    }
}
