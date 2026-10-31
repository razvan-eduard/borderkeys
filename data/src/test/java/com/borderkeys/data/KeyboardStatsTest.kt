// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
    fun `a series reads its median and its 95th percentile to within a twentieth above them`() {
        val series = KeyboardStats.Series()
        // 1 to 100, shuffled: by nearest rank the median is 50 and the 95th percentile 95.
        for (value in (1..100).shuffled(Random(1))) {
            series.add(value.toDouble())
        }
        val snapshot = series.snapshot()
        assertTrue("p50 ${snapshot.p50}", snapshot.p50 >= 50.0 && snapshot.p50 <= 52.5)
        assertTrue("p95 ${snapshot.p95}", snapshot.p95 >= 95.0 && snapshot.p95 <= 99.75)
    }

    @Test
    fun `the 95th percentile is the 95th value of a hundred, not the slow tail after it`() {
        val series = KeyboardStats.Series()
        repeat(95) { series.add(2.0) }
        repeat(5) { series.add(400.0) }
        val snapshot = series.snapshot()
        assertEquals(2.0, snapshot.p50, 1e-9)
        assertEquals(2.0, snapshot.p95, 1e-9)
        series.add(400.0)
        assertEquals(400.0, series.snapshot().p95, 1e-9)
    }

    @Test
    fun `values a twentieth apart are told apart`() {
        val series = KeyboardStats.Series()
        repeat(50) { series.add(100.0) }
        repeat(50) { series.add(105.0) }
        assertEquals(100.0, series.snapshot().p50, 1e-9)
        assertEquals(105.0, series.snapshot().p95, 1e-9)
    }

    @Test
    fun `equal values read as themselves, a value under the first bucket included`() {
        val series = KeyboardStats.Series()
        repeat(10) { series.add(3.0) }
        assertEquals(3.0, series.snapshot().p50, 1e-9)
        assertEquals(3.0, series.snapshot().p95, 1e-9)
        val small = KeyboardStats.Series()
        repeat(4) { small.add(0.001) }
        assertEquals(0.001, small.snapshot().p95, 1e-12)
    }

    @Test
    fun `a value past the last bucket, infinity included, goes in the last`() {
        val series = KeyboardStats.Series()
        repeat(2) { series.add(1.0) }
        series.add(1e307)
        series.add(Double.POSITIVE_INFINITY)
        val snapshot = series.snapshot()
        assertEquals(4, snapshot.count)
        assertEquals(1.0, snapshot.p50, 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, snapshot.p95, 0.0)
    }

    @Test
    fun `a value that is not a number is not counted`() {
        val series = KeyboardStats.Series()
        series.add(4.0)
        series.add(Double.NaN)
        val snapshot = series.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(4.0, snapshot.last, 1e-9)
        assertEquals(4.0, snapshot.mean, 1e-9)
        assertEquals(4.0, snapshot.p95, 1e-9)
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
        assertEquals(0.0, snapshot.p50, 1e-9)
        assertEquals(0.0, snapshot.p95, 1e-9)
        series.add(5.0)
        assertEquals(5.0, series.snapshot().p95, 1e-9)
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
