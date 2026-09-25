// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardStatsTest {

    @Test
    fun `a series keeps the last value, the count, the mean and the maximum`() {
        val series = KeyboardStats.Series()
        series.add(2.0)
        series.add(4.0)
        series.add(9.0)
        val snapshot = series.snapshot()
        assertEquals(9.0, snapshot.last, 1e-9)
        assertEquals(3, snapshot.count)
        assertEquals(5.0, snapshot.mean, 1e-9)
        assertEquals(9.0, snapshot.max, 1e-9)
    }

    @Test
    fun `reset empties a series`() {
        val series = KeyboardStats.Series()
        series.add(7.0)
        series.reset()
        val snapshot = series.snapshot()
        assertEquals(0, snapshot.count)
        assertEquals(0.0, snapshot.mean, 1e-9)
        assertEquals(0.0, snapshot.max, 1e-9)
    }

    @Test
    fun `a figure is rated against its baseline in either direction`() {
        assertEquals(KeyboardStats.Rating.GOOD, KeyboardStats.rate(30.0, good = 40.0, fair = 100.0))
        assertEquals(KeyboardStats.Rating.FAIR, KeyboardStats.rate(70.0, good = 40.0, fair = 100.0))
        assertEquals(KeyboardStats.Rating.POOR, KeyboardStats.rate(140.0, good = 40.0, fair = 100.0))
        assertEquals(KeyboardStats.Rating.GOOD, KeyboardStats.rate(20.0, good = 12.0, fair = 6.0, higherIsBetter = true))
        assertEquals(KeyboardStats.Rating.FAIR, KeyboardStats.rate(8.0, good = 12.0, fair = 6.0, higherIsBetter = true))
        assertEquals(KeyboardStats.Rating.POOR, KeyboardStats.rate(3.0, good = 12.0, fair = 6.0, higherIsBetter = true))
    }

    @Test
    fun `resetting the stats empties every series`() {
        KeyboardStats.decodeMillis.add(3.0)
        KeyboardStats.suggestionMillis.add(3.0)
        KeyboardStats.reset()
        assertEquals(0, KeyboardStats.decodeMillis.snapshot().count)
        assertEquals(0, KeyboardStats.suggestionMillis.snapshot().count)
    }
}
