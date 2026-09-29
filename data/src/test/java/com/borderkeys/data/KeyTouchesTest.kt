// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.KeyTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyTouchesTest {

    private fun touch(taps: Double, sumX: Double, lastUsedAt: Long) = KeyTouch(
        bucket = "portrait/0/qwerty", code = 'e'.code, taps = taps, sumX = sumX, sumY = sumX,
        sumXX = sumX, sumYY = sumX, sumXY = sumX, keyWidthPx = 108f, keyHeightPx = 160f,
        density = 2.75f, lastUsedAt = lastUsedAt,
    )

    private val halfLife = KeyTouches.halfLifeMillis(30)

    @Test
    fun `a half-life halves every total`() {
        val decayed = KeyTouches.decayed(touch(40.0, 8.0, 0L), halfLife, halfLife)
        assertEquals(20.0, decayed.taps, 1e-9)
        assertEquals(4.0, decayed.sumX, 1e-9)
        assertEquals(4.0, decayed.sumXY, 1e-9)
        assertEquals(halfLife, decayed.lastUsedAt)
    }

    @Test
    fun `no time passed leaves the totals as they are`() {
        val fresh = touch(40.0, 8.0, 1000L)
        assertSame(fresh, KeyTouches.decayed(fresh, 1000L, halfLife))
    }

    @Test
    fun `merging weighs the older totals down to the newer ones' tap and adds them`() {
        val older = touch(40.0, 8.0, 0L)
        val newer = touch(3.0, 1.0, 2 * halfLife)
        val merged = KeyTouches.merged(older, newer, halfLife)
        assertEquals(13.0, merged.taps, 1e-9)
        assertEquals(3.0, merged.sumX, 1e-9)
        assertEquals(2 * halfLife, merged.lastUsedAt)
        assertSame(newer, KeyTouches.merged(null, newer, halfLife))
    }
}
