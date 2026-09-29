// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.entity.KeyTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLearningTest {

    private val geometry = harnessGeometry()
    private val bucket = geometry.bucket.key
    private val halfLife = KeyTouches.halfLifeMillis(30)

    private fun TouchPatterns.of(letter: Char): Int = codes.indexOf(letter.code)

    @Test
    fun `samples become a pattern, with their weight, mean offset and covariance`() {
        val learning = TouchLearning()
        learning.load(bucket, emptyList(), 0L, halfLife)
        learning.add(listOf(TouchSample('e'.code, 0.1f, 0.2f), TouchSample('e'.code, 0.3f, 0f)), geometry, 0L)
        val patterns = learning.patterns(halfLife)
        val e = patterns.of('e')
        assertEquals(2f, patterns.taps[e], 1e-6f)
        assertEquals(0.2f, patterns.meanX[e], 1e-6f)
        assertEquals(0.1f, patterns.meanY[e], 1e-6f)
        assertEquals(0.01f, patterns.varianceX[e], 1e-6f)
        assertEquals(0.01f, patterns.varianceY[e], 1e-6f)
        assertEquals(-0.01f, patterns.covariance[e], 1e-6f)
    }

    @Test
    fun `stored totals are weighed down by their age when loaded`() {
        val learning = TouchLearning()
        val stored = KeyTouch(bucket, 'h'.code, 40.0, 4.0, 0.0, 1.0, 1.0, 0.0, 108f, 160f, 2.75f, 0L)
        learning.load(bucket, listOf(stored), halfLife, halfLife)
        val patterns = learning.patterns(halfLife)
        assertEquals(20f, patterns.taps[patterns.of('h')], 1e-4f)
        assertEquals(0.1f, patterns.meanX[patterns.of('h')], 1e-6f)
    }

    @Test
    fun `a drain hands over the new totals and keeps them in the patterns`() {
        val learning = TouchLearning()
        learning.load(bucket, emptyList(), 0L, halfLife)
        learning.add(listOf(TouchSample('t'.code, 0.1f, 0f)), geometry, 0L)
        val drained = learning.drain(halfLife)
        assertEquals(listOf('t'.code), drained.map { it.code })
        assertEquals(1.0, drained.single().taps, 1e-9)
        assertTrue(learning.isEmpty())
        assertEquals(1f, learning.patterns(halfLife).taps.single(), 1e-6f)
    }

    @Test
    fun `samples from another bucket are written but stay out of this one's patterns`() {
        val learning = TouchLearning()
        learning.load(bucket, emptyList(), 0L, halfLife)
        val landscape = harnessGeometry(HARNESS_BUCKET.copy(landscape = true))
        learning.add(listOf(TouchSample('t'.code, 0.1f, 0f)), landscape, 0L)
        assertEquals(0, learning.patterns(halfLife).size)
        assertEquals(listOf(landscape.bucket.key), learning.drain(halfLife).map { it.bucket })
    }

    @Test
    fun `a discard forgets what is stored and what is pending`() {
        val learning = TouchLearning()
        val stored = KeyTouch(bucket, 'h'.code, 4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 108f, 160f, 2.75f, 0L)
        learning.load(bucket, listOf(stored), 0L, halfLife)
        learning.add(listOf(TouchSample('t'.code, 0.1f, 0f)), geometry, 0L)
        learning.discard()
        assertTrue(learning.isBlank)
        assertEquals(0, learning.patterns(halfLife).size)
    }
}
