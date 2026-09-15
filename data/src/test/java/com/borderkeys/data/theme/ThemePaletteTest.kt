// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {

    @Test
    fun `indexOf matches exactly without preserveAlpha`() {
        val colours = listOf(0xFF112233.toInt(), 0xFF445566.toInt())
        assertEquals(1, ThemePalette.indexOf(colours, 0xFF445566.toInt()))
        assertEquals(-1, ThemePalette.indexOf(colours, 0xCC445566.toInt()))
    }

    @Test
    fun `indexOf matches on RGB only with preserveAlpha`() {
        val colours = listOf(0xFF112233.toInt(), 0xFF445566.toInt())
        assertEquals(1, ThemePalette.indexOf(colours, 0xCC445566.toInt(), preserveAlpha = true))
    }

    @Test
    fun `contains is indexOf as a boolean`() {
        val colours = listOf(0xFF112233.toInt())
        assertTrue(ThemePalette.contains(colours, 0xFF112233.toInt()))
        assertFalse(ThemePalette.contains(colours, 0xFF445566.toInt()))
    }

    @Test
    fun `appendIndex names the position after the last entry`() {
        assertEquals(0, ThemePalette.appendIndex(emptyList()))
        assertEquals(2, ThemePalette.appendIndex(listOf(1, 2)))
    }

    @Test
    fun `inserted appends a brand new colour at the given index`() {
        val colours = listOf(0xFF112233.toInt())
        val updated = ThemePalette.inserted(colours, 0xFF445566.toInt(), ThemePalette.appendIndex(colours))
        assertEquals(listOf(0xFF112233.toInt(), 0xFF445566.toInt()), updated)
    }

    @Test
    fun `inserted is a no-op for a colour already in the standard palette`() {
        val colours = emptyList<Int>()
        val updated = ThemePalette.inserted(colours, ThemePalette.COLOURS.first(), 0)
        assertEquals(colours, updated)
    }

    @Test
    fun `inserted never duplicates or reorders an already-present custom colour`() {
        val colours = listOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val updated = ThemePalette.inserted(colours, 0xFF112233.toInt(), ThemePalette.appendIndex(colours))
        assertEquals(colours, updated)
    }

    @Test
    fun `inserted respects preserveAlpha when checking for duplicates`() {
        val colours = listOf(0xFF112233.toInt())
        val updated = ThemePalette.inserted(
            colours,
            0xCC112233.toInt(),
            ThemePalette.appendIndex(colours),
            preserveAlpha = true,
        )
        assertEquals(colours, updated)
    }

    @Test
    fun `inserted drops the oldest entry once past the cap`() {
        val full = (0 until ThemePalette.MAX_CUSTOM_COLOURS_PER_FIELD).toList()
        val updated = ThemePalette.inserted(full, 999, ThemePalette.appendIndex(full))
        assertEquals(ThemePalette.MAX_CUSTOM_COLOURS_PER_FIELD, updated.size)
        assertEquals(1, updated.first())
        assertEquals(999, updated.last())
    }

    @Test
    fun `removedAt drops exactly the entry at that index`() {
        val colours = listOf(1, 2, 3)
        assertEquals(listOf(1, 3), ThemePalette.removedAt(colours, 1))
    }
}
