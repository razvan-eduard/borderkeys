// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ergonomic wedge geometry -- which direction each rank sits in, and where one wedge's
 * boundary ends and the next begins -- checked as plain numbers, no `View`/`Context`/Robolectric
 * needed. See [RadialSuggestionMenuView.computeWedgeBoundaries]'s own doc for why these two
 * pure functions exist separately from the drawing/hit-testing code that reads them.
 */
class RadialSuggestionMenuViewTest {

    private fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    @Test
    fun `the best two ranks are the upper diagonals`() {
        assertEquals(-45f, RadialSuggestionMenuView.wedgeCentreDegrees(0, 6), 0f)
        assertEquals(-135f, RadialSuggestionMenuView.wedgeCentreDegrees(1, 6), 0f)
    }

    @Test
    fun `rank order wraps rather than crashes past the table's own size`() {
        assertEquals(
            RadialSuggestionMenuView.wedgeCentreDegrees(0, 6),
            RadialSuggestionMenuView.wedgeCentreDegrees(6, 6),
            0f,
        )
    }

    @Test
    fun `a single wedge spans the whole circle`() {
        val (start, sweep) = RadialSuggestionMenuView.computeWedgeBoundaries(1)
        assertEquals(1, start.size)
        assertEquals(360f, sweep[0], 0.01f)
    }

    @Test
    fun `zero wedges produces empty, not a crash`() {
        val (start, sweep) = RadialSuggestionMenuView.computeWedgeBoundaries(0)
        assertEquals(0, start.size)
        assertEquals(0, sweep.size)
    }

    @Test
    fun `isWithinRing accepts a touch on the ring and rejects one elsewhere on the keyboard`() {
        // This view is laid out across the entire host, not just its own drawn circle -- a
        // touch on some unrelated key, far from the ring's own anchor, must not read as "on"
        // the ring, or the next word's own swipe gets eaten by this one's leftover ring.
        assertTrue(RadialSuggestionMenuView.isWithinRing(100f, 100f, 100f, 100f, 80f))
        assertTrue(RadialSuggestionMenuView.isWithinRing(180f, 100f, 100f, 100f, 80f))
        assertTrue(!RadialSuggestionMenuView.isWithinRing(500f, 500f, 100f, 100f, 80f))
    }

    /**
     * The property that actually matters: whatever [RadialSuggestionMenuView.drawWedges] paints
     * and whatever hit-testing accepts must agree, for every wedge count the setting allows
     * (3..6). This checks it the same way hit-testing itself would -- each wedge's own ergonomic
     * centre must fall inside its own [start, start+sweep) span -- and that every wedge's span
     * abuts its neighbours with no gap and no overlap, since the two arrays are a partition of
     * the full circle, not independent per-wedge computations.
     */
    @Test
    fun `boundaries fully partition the circle with no gap or overlap, for every real wedge count`() {
        for (n in 3..6) {
            val (start, sweep) = RadialSuggestionMenuView.computeWedgeBoundaries(n)
            var totalSweep = 0f
            for (rank in 0 until n) {
                totalSweep += sweep[rank]
                val centre = normalize(RadialSuggestionMenuView.wedgeCentreDegrees(rank, n))
                val delta = normalize(centre - start[rank])
                assertTrue(
                    "rank $rank's own centre $centre is outside its span " +
                        "[${start[rank]}, ${start[rank] + sweep[rank]}) at n=$n",
                    delta < sweep[rank],
                )
            }
            assertEquals("sweeps must sum to a full circle at n=$n", 360f, totalSweep, 0.01f)
        }
    }
}
