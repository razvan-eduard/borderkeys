// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the width of a dropped optional key goes, and what that does to the keys beside it. */
class LayoutAbsorberTest {

    private fun key(code: Int, width: Float, flags: Int = KeyFlags.NONE) =
        KeyboardLayout.Key(code, "", "", width, flags)

    /** A numpad bottom row: a toggle, an optional key, the absorber, the digit, the space bar. */
    private fun row(absorbFlag: Int) = KeyboardLayout.Row(
        indent = 0f,
        heightScale = 1f,
        keys = listOf(
            key(KeyCodes.SYMBOLS, 1.75f),
            key(KeyCodes.EMOJI, 1.75f),
            key(','.code, 1.75f, absorbFlag),
            key('0'.code, 1f),
            key(KeyCodes.SPACE, 3f),
        ),
    )

    private fun layout(absorbFlag: Int) =
        KeyboardLayout("test", "und", listOf(row(absorbFlag)))

    /** Where a key's left edge lands, in width units. */
    private fun leftEdgeOf(code: Int, layout: KeyboardLayout): Float {
        var x = 0f
        for (k in layout.rows[0].keys) {
            if (k.code == code) return x
            x += k.widthUnits
        }
        throw AssertionError("no key $code")
    }

    @Test
    fun `without an absorber the space bar takes the width and the digit moves`() {
        val before = layout(KeyFlags.NONE)
        val after = before.withoutEmojiKey()
        assertEquals("the row keeps its total width", 9.25f, after.rows[0].units, 0.001f)
        assertTrue(
            "this is the old behaviour, kept as the thing the flag exists to avoid",
            leftEdgeOf('0'.code, after) < leftEdgeOf('0'.code, before),
        )
    }

    @Test
    fun `the named absorber takes the width and every key past it stays put`() {
        val before = layout(KeyFlags.ABSORBS_FREED_WIDTH)
        val after = before.withoutEmojiKey()
        assertEquals("the row keeps its total width", 9.25f, after.rows[0].units, 0.001f)
        assertEquals(
            "the digit is still under its column",
            leftEdgeOf('0'.code, before), leftEdgeOf('0'.code, after), 0.001f,
        )
        assertEquals(
            "and so is the space bar after it",
            leftEdgeOf(KeyCodes.SPACE, before), leftEdgeOf(KeyCodes.SPACE, after), 0.001f,
        )
        assertEquals("the absorber is the key that grew", 3.5f,
            after.rows[0].keys.first { it.code == ','.code }.widthUnits, 0.001f)
    }

    @Test
    fun `a parsed layout of the user's own gives the dropped key's width to its absorber`() {
        val before = LayoutLoader.parse(
            """
            {"id": "custom-1", "languageTag": "und", "rows": [
              {"keys": [{"code": "symbols", "w": 1.75}, {"code": "emoji", "w": 1.75},
                        {"c": ",", "w": 1.75, "absorb": true}, {"c": "0"}, {"c": " ", "w": 3}]}
            ]}
            """.trimIndent(),
        )
        val after = before.withoutEmojiKey()
        assertEquals(9.25f, after.rows[0].units, 0.001f)
        assertEquals(leftEdgeOf('0'.code, before), leftEdgeOf('0'.code, after), 0.001f)
        assertEquals(3.5f, after.rows[0].keys.first { it.code == ','.code }.widthUnits, 0.001f)
    }

    @Test
    fun `a row with no absorber and no space bar drops the width rather than crashing`() {
        val narrow = KeyboardLayout(
            "test", "und",
            listOf(
                KeyboardLayout.Row(
                    0f, 1f,
                    listOf(key(KeyCodes.EMOJI, 1.75f), key('0'.code, 1f)),
                ),
            ),
        )
        val after = narrow.withoutEmojiKey()
        assertEquals(1, after.rows[0].keys.size)
        assertEquals(1f, after.rows[0].units, 0.001f)
    }
}
