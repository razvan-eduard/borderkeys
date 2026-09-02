// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeyboardGeometryTest {

    private val width = 1080f
    private val height = 640f

    private fun compiled(gap: Float = 8f): KeyboardGeometry =
        KeyboardGeometry().apply {
            compile(KeyboardLayout.fallbackQwerty(), width, height, gap)
        }

    @Test
    fun `every key gets a rectangle with positive area`() {
        val geometry = compiled()
        assertEquals(KeyboardLayout.fallbackQwerty().keyCount, geometry.keyCount)
        for (index in 0 until geometry.keyCount) {
            assertTrue(
                "key $index has no width",
                geometry.keyRight[index] > geometry.keyLeft[index],
            )
            assertTrue(
                "key $index has no height",
                geometry.keyBottom[index] > geometry.keyTop[index],
            )
        }
    }

    @Test
    fun `no two keys overlap`() {
        val geometry = compiled()
        for (a in 0 until geometry.keyCount) {
            for (b in a + 1 until geometry.keyCount) {
                val separated = geometry.keyRight[a] <= geometry.keyLeft[b] ||
                    geometry.keyRight[b] <= geometry.keyLeft[a] ||
                    geometry.keyBottom[a] <= geometry.keyTop[b] ||
                    geometry.keyBottom[b] <= geometry.keyTop[a]
                assertTrue("keys $a and $b overlap", separated)
            }
        }
    }

    @Test
    fun `rows fill the width exactly`() {
        val geometry = compiled(gap = 0f)
        val layout = KeyboardLayout.fallbackQwerty()
        var index = 0
        for (row in layout.rows) {
            val first = index
            val last = index + row.keys.size - 1
            // The indent is real empty space, so only the right edge has to reach the boundary.
            assertEquals(
                "row starting at $first does not reach the right edge",
                width, geometry.keyRight[last], 0.01f,
            )
            assertEquals(
                "row starting at $first does not start at its indent",
                row.indent * (width / row.units), geometry.keyLeft[first], 0.01f,
            )
            index += row.keys.size
        }
    }

    @Test
    fun `rows stack without gaps and fill the height`() {
        val geometry = compiled(gap = 0f)
        val layout = KeyboardLayout.fallbackQwerty()
        var index = 0
        var expectedTop = 0f
        for (row in layout.rows) {
            assertEquals(expectedTop, geometry.keyTop[index], 0.01f)
            expectedTop = geometry.keyBottom[index]
            index += row.keys.size
        }
        assertEquals(height, expectedTop, 0.01f)
    }

    @Test
    fun `the centre of every key hits that key`() {
        val geometry = compiled()
        for (index in 0 until geometry.keyCount) {
            assertEquals(
                "the centre of key $index resolves elsewhere",
                index,
                geometry.findKeyAt(geometry.centerX[index], geometry.centerY[index]),
            )
        }
    }

    @Test
    fun `there is no dead pixel anywhere on the keyboard`() {
        // A touch in the gap between two keys must still type. Sampling the whole surface is
        // the only way to be sure the grid has no cell resolving to nothing -- and a gap that
        // does nothing is invisible in a screenshot and infuriating in use.
        val geometry = compiled()
        var x = 0.5f
        while (x < width) {
            var y = 0.5f
            while (y < height) {
                assertNotEquals(
                    "no key at ($x, $y)",
                    KeyboardGeometry.NO_KEY,
                    geometry.findKeyAt(x, y),
                )
                y += 3f
            }
            x += 3f
        }
    }

    @Test
    fun `a point in the gap resolves to one of the keys touching it`() {
        val geometry = compiled(gap = 12f)
        // Midway between the first two keys of the top row, vertically centred on them.
        val gapX = (geometry.keyRight[0] + geometry.keyLeft[1]) / 2f
        val gapY = geometry.centerY[0]
        val hit = geometry.findKeyAt(gapX, gapY)
        assertTrue("the gap resolved to $hit", hit == 0 || hit == 1)
    }

    @Test
    fun `touches outside the surface are clamped rather than lost`() {
        val geometry = compiled()
        // A finger that slides off the edge keeps typing the edge key instead of nothing.
        assertNotEquals(KeyboardGeometry.NO_KEY, geometry.findKeyAt(-50f, 10f))
        assertNotEquals(KeyboardGeometry.NO_KEY, geometry.findKeyAt(width + 50f, height - 10f))
    }

    @Test
    fun `the grid agrees with an exhaustive nearest-key search`() {
        // The grid is a precomputed cache of nearestKey. If they ever disagree, the cache is
        // wrong -- which would show up as a key that is hard to hit near its edge.
        val geometry = compiled()
        var mismatches = 0
        var samples = 0
        var x = 2f
        while (x < width) {
            var y = 2f
            while (y < height) {
                samples++
                val exact = geometry.nearestKey(x, y)
                val grid = geometry.findKeyAt(x, y)
                if (exact != grid) {
                    // Disagreement is only acceptable in a gap, where both answers are a
                    // nearest key rather than a containing one.
                    val insideExact = x >= geometry.keyLeft[exact] && x < geometry.keyRight[exact] &&
                        y >= geometry.keyTop[exact] && y < geometry.keyBottom[exact]
                    if (insideExact) {
                        mismatches++
                    }
                }
                y += 7f
            }
            x += 7f
        }
        assertTrue("$mismatches of $samples samples hit the wrong key", mismatches == 0)
    }

    @Test
    fun `only letters are exported to the engine`() {
        val geometry = compiled()
        val codes = IntArray(64)
        val xs = FloatArray(64)
        val ys = FloatArray(64)
        val written = geometry.exportGeometry(codes, xs, ys)

        assertEquals(26, written)
        for (index in 0 until written) {
            assertTrue(
                "exported a non-letter: ${codes[index]}",
                Character.isLetter(codes[index]),
            )
            assertTrue(xs[index] in 0f..width)
            assertTrue(ys[index] in 0f..height)
        }
        // Shift, delete, space and enter must not be there: a swipe through shift means nothing,
        // and offering it as a substitution target would corrupt every correction near it.
        assertTrue(codes.take(written).none { it == KeyCodes.SPACE })
    }

    @Test
    fun `recompiling at a new size reuses the arrays`() {
        val geometry = compiled()
        val before = geometry.keyLeft
        geometry.compile(KeyboardLayout.fallbackQwerty(), 720f, 480f, 0f)
        assertTrue("a rotation reallocated the geometry", before === geometry.keyLeft)
        assertEquals(720f, geometry.keyRight[9], 0.01f)
    }

    @Test
    fun `labels are packed into one shared buffer`() {
        val geometry = compiled()
        // No String per key: the draw path reads characters out of one array.
        var total = 0
        for (index in 0 until geometry.keyCount) {
            total += geometry.labelLength[index]
        }
        assertEquals(total, geometry.labelChars.size)
        val first = String(geometry.labelChars, geometry.labelOffset[0], geometry.labelLength[0])
        assertEquals("q", first)
    }

    @Test
    fun `key centres are the midpoints of their rectangles`() {
        val geometry = compiled()
        for (index in 0 until geometry.keyCount) {
            assertTrue(
                abs(geometry.centerX[index] -
                    (geometry.keyLeft[index] + geometry.keyRight[index]) / 2f) < 0.001f,
            )
        }
    }
}

class KeyboardLayoutTest {

    @Test
    fun `the number row is prepended and the letters keep their order`() {
        val base = KeyboardLayout.fallbackQwerty()
        val withDigits = base.withNumberRow()

        assertEquals(base.rows.size + 1, withDigits.rows.size)
        assertEquals(base.keyCount + 10, withDigits.keyCount)
        assertEquals("1", withDigits.rows[0].keys[0].label)
        assertEquals("0", withDigits.rows[0].keys[9].label)
        // Everything below the digits is untouched.
        assertEquals(base.rows[0].keys.map { it.code }, withDigits.rows[1].keys.map { it.code })
    }

    @Test
    fun `a digit is not a swipe letter`() {
        // A gesture must not be able to pass through the number row, and a digit must never be
        // offered as a substitution when correcting a typo.
        val digits = KeyboardLayout.fallbackQwerty().withNumberRow().rows[0].keys
        for (key in digits) {
            assertTrue(
                "digit ${key.label} is marked as a swipe letter",
                !KeyFlags.has(key.flags, KeyFlags.LETTER),
            )
        }
    }

    @Test
    fun `the number row is shorter than a letter row`() {
        val withDigits = KeyboardLayout.fallbackQwerty().withNumberRow()
        assertTrue(withDigits.rows[0].heightScale < withDigits.rows[1].heightScale)
    }

    @Test
    fun `applying the number row twice changes nothing`() {
        // The service reapplies the layout whenever a setting changes, so this has to be
        // idempotent or the keyboard grows a row on every emission.
        val once = KeyboardLayout.fallbackQwerty().withNumberRow()
        val twice = once.withNumberRow()
        assertEquals(once.keyCount, twice.keyCount)
        assertEquals(once.rows.size, twice.rows.size)
    }

    @Test
    fun `the number row lays out without overlaps`() {
        val geometry = KeyboardGeometry()
        geometry.compile(KeyboardLayout.fallbackQwerty().withNumberRow(), 1080f, 720f, 8f)
        for (index in 0 until geometry.keyCount) {
            assertTrue(geometry.keyRight[index] > geometry.keyLeft[index])
            assertTrue(geometry.keyBottom[index] > geometry.keyTop[index])
        }
        // Every point still resolves to a key, including inside the new row.
        var x = 2f
        while (x < 1080f) {
            var y = 2f
            while (y < 720f) {
                assertNotEquals(KeyboardGeometry.NO_KEY, geometry.findKeyAt(x, y))
                y += 11f
            }
            x += 11f
        }
    }
}

class NumberRowSymbolsTest {

    @Test
    fun `each digit carries the symbol a physical keyboard puts above it`() {
        val digits = KeyboardLayout.fallbackQwerty().withNumberRow().rows[0].keys
        val expected = "1234567890".zip("!@#$%^&*()")
        assertEquals(expected.size, digits.size)
        for ((index, pair) in expected.withIndex()) {
            val (digit, shifted) = pair
            assertEquals(digit.toString(), digits[index].label)
            assertEquals(shifted.toString(), digits[index].alternatives)
            assertTrue(KeyFlags.has(digits[index].flags, KeyFlags.HAS_ALTERNATIVES))
        }
    }

    @Test
    fun `no shipped letter layout puts a digit under a letter`() {
        // The whole point of moving the digits into their own row: a long press on "t" belongs
        // to the Romanian comma, not to a five. Asserted against the asset files themselves,
        // read as text -- org.json is stubbed in a JVM unit test, and this invariant is about
        // what ships rather than about the parser.
        for (name in listOf("qwerty_ro", "qwerty_en")) {
            val file = java.io.File("src/main/assets/layouts/$name.json")
            assertTrue("$name.json is missing", file.isFile)
            val text = file.readText()
            val alternates = Regex("\"alt\"\\s*:\\s*\"([^\"]*)\"").findAll(text)
                .map { it.groupValues[1] }.toList()
            assertTrue("$name has no long-press alternates at all", alternates.isNotEmpty())
            for (value in alternates) {
                assertTrue(
                    "$name puts a digit in a long press: \"$value\"",
                    value.none { it.isDigit() },
                )
            }
        }
        // And the Romanian diacritics are still where they belong.
        val romanian = java.io.File("src/main/assets/layouts/qwerty_ro.json").readText()
        for (pair in listOf("\"t\", \"alt\": \"ț", "\"s\", \"alt\": \"ș")) {
            assertTrue("qwerty_ro lost $pair", romanian.contains(pair))
        }
        assertTrue(romanian.contains("\"a\", \"alt\": \"ă"))
        assertTrue(romanian.contains("\"i\", \"alt\": \"î"))
    }
    /**
     * Turning the emoji key off gives its width back rather than leaving a gap.
     *
     * A row that loses a key and keeps its total width is a row where every remaining key
     * moves, which is worse than the key being there.
     */
    @Test
    fun `removing the emoji key widens the space bar`() {
        val withEmoji = KeyboardLayout(
            id = "t", label = "t", languageTag = "und",
            rows = listOf(
                KeyboardLayout.Row(
                    0f, 1f,
                    listOf(
                        KeyboardLayout.Key(KeyCodes.EMOJI, "\u263A", "", 1f, 0),
                        KeyboardLayout.Key(' '.code, "", "", 3f, 0),
                    ),
                ),
            ),
        )
        val without = withEmoji.withoutEmojiKey()
        assertEquals("the key should be gone", 1, without.rows[0].keys.size)
        assertEquals("the width should have moved", 4f, without.rows[0].keys[0].widthUnits)
        assertEquals("the row is the same width", withEmoji.rows[0].units, without.rows[0].units)
    }

    /** A layout with no emoji key is returned unchanged, not rebuilt. */
    @Test
    fun `a layout without an emoji key is left alone`() {
        val plain = KeyboardLayout(
            id = "t", label = "t", languageTag = "und",
            rows = listOf(
                KeyboardLayout.Row(0f, 1f, listOf(
                    KeyboardLayout.Key(' '.code, "", "", 4f, 0),
                )),
            ),
        )
        assertSame(plain, plain.withoutEmojiKey())
    }

}
