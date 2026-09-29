// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.predict.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapAlignmentTest {

    private val geometry = harnessGeometry()

    /** [typed] tapped at each letter's key centre moved by [offsets], in key units. */
    private fun taps(typed: String, offsets: Map<Int, Pair<Float, Float>> = emptyMap()): TypedTaps {
        val xs = FloatArray(typed.length)
        val ys = FloatArray(typed.length)
        typed.forEachIndexed { i, letter ->
            val (x, y) = Pipeline.keyCentre(letter.lowercaseChar())
            val (dx, dy) = offsets[i] ?: (0f to 0f)
            xs[i] = x + dx * HARNESS_KEY_WIDTH
            ys[i] = y + dy * HARNESS_KEY_HEIGHT
        }
        return TypedTaps(typed, xs, ys)
    }

    private fun List<TouchSample>.described() =
        joinToString(" ") { "%c:%.2f,%.2f".format(it.code.toChar(), it.dx, it.dy) }

    @Test
    fun `each letter kept as typed gets its tap's offset from its key's centre`() {
        val samples = TapAlignment.samples(
            taps("the", mapOf(0 to (0.1f to 0.2f), 2 to (-0.1f to 0f))), "the", false, geometry,
        )
        assertEquals("t:0.10,0.20 h:0.00,0.00 e:-0.10,0.00", samples.described())
    }

    @Test
    fun `a tap on a ring neighbour counts for the letter kept, measured from that letter's key`() {
        val samples = TapAlignment.samples(taps("thw", mapOf(2 to (0.3f to 0f))), "the", false, geometry)
        assertEquals("t:0.00,0.00 h:0.00,0.00 e:-0.70,0.00", samples.described())
    }

    @Test
    fun `a letter far from the key hit gives nothing, and the others still count`() {
        val samples = TapAlignment.samples(taps("tpe"), "the", false, geometry)
        assertEquals("t:0.00,0.00 e:0.00,0.00", samples.described())
    }

    @Test
    fun `keys farther apart than the ring give nothing, even with the tap close to the key meant`() {
        // Two keys a key and a half apart: outside the ring, though a tap on b's near edge lies
        // 0.9 key widths from a's centre.
        val twoKeys = KeyGeometrySnapshot(
            HARNESS_BUCKET, 100f, 100f, 2.75f, intArrayOf('a'.code, 'b'.code),
            floatArrayOf(50f, 200f), floatArrayOf(50f, 50f),
        )
        val tapped = TypedTaps("b", floatArrayOf(140f), floatArrayOf(50f))
        assertTrue(TapAlignment.samples(tapped, "a", false, twoKeys).isEmpty())
    }

    @Test
    fun `a word with a letter that has no point gives nothing`() {
        val untapped = taps("the").also { it.xs[1] = Float.NaN; it.ys[1] = Float.NaN }
        assertTrue(TapAlignment.samples(untapped, "the", false, geometry).isEmpty())
    }

    @Test
    fun `lengths must match, except that a completion lines up its first letters`() {
        assertTrue(TapAlignment.samples(taps("keyb"), "keyboard", false, geometry).isEmpty())
        assertEquals(
            "k:0.00,0.00 e:0.00,0.00 y:0.00,0.00 b:0.00,0.00",
            TapAlignment.samples(taps("keyb"), "keyboard", true, geometry).described(),
        )
        assertTrue(TapAlignment.samples(taps("keyb"), "key", true, geometry).isEmpty())
    }

    @Test
    fun `a tap farther than a key from the key meant is dropped`() {
        val samples = TapAlignment.samples(taps("the", mapOf(2 to (0.6f to 0.9f))), "the", false, geometry)
        assertEquals("t:0.00,0.00 h:0.00,0.00", samples.described())
    }

    @Test
    fun `a capital counts for its key`() {
        assertEquals("t:0.00,0.00 h:0.00,0.00 e:0.00,0.00", TapAlignment.samples(taps("The"), "the", false, geometry).described())
    }
}
