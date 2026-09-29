// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.entity.KeyTouch
import org.junit.Assert.assertEquals
import org.junit.Test

class HeatmapGlowsTest {

    private val halfLife = KeyTouches.halfLifeMillis(30)

    private fun row(bucket: String, letter: Char, taps: Double, meanX: Double, lastUsedAt: Long = 0L) =
        KeyTouch(bucket, letter.code, taps, taps * meanX, 0.0, taps * meanX * meanX + taps * 0.04, taps * 0.09, 0.0, 108f, 160f, 2.75f, lastUsedAt)

    @Test
    fun `only the chosen bucket is drawn, each key at its mean and spread`() {
        val glows = HeatmapGlows.of(
            listOf(row("portrait/0/qwerty", 'e', 60.0, 0.1), row("landscape/0/qwerty", 't', 60.0, 0.0)),
            "portrait/0/qwerty", 0L, halfLife, 30, 0,
        )
        assertEquals(listOf('e'.code), glows.codes.toList())
        assertEquals(0.1f, glows.meanX[0], 1e-6f)
        assertEquals(0.04f, glows.varianceX[0], 1e-6f)
        assertEquals(0.09f, glows.varianceY[0], 1e-6f)
    }

    @Test
    fun `a key below the minimum is the default circle, and the glow grows to full at four times it`() {
        assertEquals(0f, HeatmapGlows.strength(29.0, 30), 0f)
        assertEquals(0.35f, HeatmapGlows.strength(30.0, 30), 1e-6f)
        assertEquals(1f, HeatmapGlows.strength(120.0, 30), 1e-6f)
        assertEquals(1f, HeatmapGlows.strength(500.0, 30), 1e-6f)
    }

    @Test
    fun `old taps count for less`() {
        val glows = HeatmapGlows.of(
            listOf(row("portrait/0/qwerty", 'e', 60.0, 0.1, lastUsedAt = 0L)),
            "portrait/0/qwerty", halfLife, halfLife, 30, 0,
        )
        assertEquals(0.35f, glows.strength[0], 1e-6f)
    }

    @Test
    fun `a bucket key reads back its orientation and layout`() {
        val bucket = HeatmapGlows.Bucket.parse("landscape/0/azerty+dig-noglobe")
        assertEquals(true, bucket.landscape)
        assertEquals("azerty", bucket.baseLayoutId)
    }
}
