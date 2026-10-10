// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cascade screenshots: which screenshot comes next, and what moves the series on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotSeriesTest {

    private fun shot(name: String, millis: Long) =
        ScreenshotFolder.Shot(Uri.parse("content://shots/$name"), "image/png", millis)

    /** Four screenshots, taken in this order. */
    private val one = shot("1", 1_000)
    private val two = shot("2", 2_000)
    private val three = shot("3", 3_000)
    private val four = shot("4", 4_000)
    private val taken = listOf(one, two, three, four)

    @Test
    fun `the oldest is offered first, and each paste puts the next one up`() {
        val series = ScreenshotSeries()
        val offered = mutableListOf<String>()
        while (true) {
            val next = series.next(taken) ?: break
            offered += next.uri.lastPathSegment!!
            series.used(next)
        }
        assertEquals(listOf("1", "2", "3", "4"), offered)
    }

    @Test
    fun `a screenshot taken during the series joins its end`() {
        val series = ScreenshotSeries()
        series.used(one)
        series.used(two)
        val five = shot("5", 5_000)
        assertEquals(three, series.next(taken + five))
        series.used(three)
        series.used(four)
        assertEquals(five, series.next(taken + five))
    }

    @Test
    fun `one older than the offer window is not offered, the series going on with the rest`() {
        val series = ScreenshotSeries()
        assertEquals("the window leaves 3 and 4", three, series.next(listOf(three, four)))
    }

    @Test
    fun `ending the series offers none of its screenshots again, only a newer one`() {
        val series = ScreenshotSeries()
        series.used(one)
        series.end(taken)
        assertNull(series.next(taken))
        val five = shot("5", 5_000)
        assertEquals(five, series.next(taken + five))
    }

    @Test
    fun `using an older screenshot again does not move the series back`() {
        val series = ScreenshotSeries()
        series.used(three)
        series.used(one)
        assertEquals(four, series.next(taken))
    }

    @Test
    fun `two screenshots of the same time are both offered, in the order of their URIs`() {
        val a = shot("a", 1_000)
        val b = shot("b", 1_000)
        val shots = listOf(b, a).sortedWith(ScreenshotFolder.Shot.ORDER)
        val series = ScreenshotSeries()
        assertEquals(a, series.next(shots))
        series.used(a)
        assertEquals(b, series.next(shots))
        series.used(b)
        assertNull(series.next(shots))
    }
}
