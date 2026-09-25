// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The modifier row: where it goes, what it carries, and which hardware key a character is. */
class ModifierRowTest {

    private val base = KeyboardLayout.fallbackQwerty()

    @Test
    fun `the row goes above the letters and carries the eight keys across the full width`() {
        val layout = base.withModifierRow()
        assertEquals(base.rows.size + 1, layout.rows.size)
        assertEquals(
            listOf(
                KeyCodes.ESCAPE, KeyCodes.TAB, KeyCodes.CONTROL, KeyCodes.ALT,
                KeyCodes.ARROW_LEFT, KeyCodes.ARROW_DOWN, KeyCodes.ARROW_UP, KeyCodes.ARROW_RIGHT,
            ),
            layout.rows[0].keys.map { it.code },
        )
        assertEquals(10f, layout.rows[0].units, 0.001f)
        assertTrue(layout.rows[0].heightScale < 1f)
        assertNotEquals(base.id, layout.id)
    }

    @Test
    fun `the row is added once`() {
        val once = base.withModifierRow()
        assertSame(once, once.withModifierRow())
    }

    @Test
    fun `below the keyboard it is the last row, under the space row`() {
        val layout = base.withNumberRow().withModifierRow(atBottom = true)
        assertEquals('1'.code, layout.rows[0].keys[0].code)
        assertEquals(KeyCodes.ESCAPE, layout.rows.last().keys[0].code)
        assertTrue(layout.rows[layout.rows.size - 2].keys.any { it.code == KeyCodes.SPACE })
        assertNotEquals(base.withModifierRow().id, layout.id)
        assertSame(layout, layout.withModifierRow(atBottom = true))
        assertSame(layout, layout.withModifierRow())
    }

    @Test
    fun `it sits above the number row`() {
        val layout = base.withNumberRow().withModifierRow()
        assertEquals(KeyCodes.ESCAPE, layout.rows[0].keys[0].code)
        assertEquals('1'.code, layout.rows[1].keys[0].code)
    }

    @Test
    fun `the arrows repeat and nothing on the row is a letter`() {
        for (key in base.withModifierRow().rows[0].keys) {
            assertFalse(key.label, KeyFlags.has(key.flags, KeyFlags.LETTER))
            assertTrue(key.label, KeyFlags.has(key.flags, KeyFlags.MODIFIER))
            assertEquals(key.label, KeyCodes.isArrow(key.code), KeyFlags.has(key.flags, KeyFlags.REPEATABLE))
        }
    }

    @Test
    fun `a chosen list sets the keys, their order and their width`() {
        val layout = base.withModifierRow(
            keys = listOf(KeyCodes.HOME, KeyCodes.END, KeyCodes.NONE, KeyCodes.HOME, KeyCodes.FORWARD_DELETE),
        )
        assertEquals(
            listOf(KeyCodes.HOME, KeyCodes.END, KeyCodes.FORWARD_DELETE),
            layout.rows[0].keys.map { it.code },
        )
        assertEquals(10f, layout.rows[0].units, 0.001f)
        assertTrue(KeyFlags.has(layout.rows[0].keys[2].flags, KeyFlags.REPEATABLE))
        assertNotEquals(base.withModifierRow().id, layout.id)
    }

    @Test
    fun `an empty or unknown list shows the shipped row, and the row caps at twelve`() {
        assertEquals(8, base.withModifierRow(keys = listOf(KeyCodes.NONE)).rows[0].keys.size)
        val all = (-9 downTo -22).toList()
        assertEquals(KeyboardLayout.MAX_MODIFIER_KEYS, base.withModifierRow(keys = all).rows[0].keys.size)
    }

    @Test
    fun `a layout asset can name the keys`() {
        assertEquals(KeyCodes.ESCAPE, KeyCodes.named("escape"))
        assertEquals(KeyCodes.TAB, KeyCodes.named("tab"))
        assertEquals(KeyCodes.CONTROL, KeyCodes.named("control"))
        assertEquals(KeyCodes.ALT, KeyCodes.named("alt"))
        assertEquals(KeyCodes.ARROW_LEFT, KeyCodes.named("left"))
        assertEquals(KeyCodes.ARROW_RIGHT, KeyCodes.named("right"))
        assertEquals(KeyCodes.ARROW_UP, KeyCodes.named("up"))
        assertEquals(KeyCodes.ARROW_DOWN, KeyCodes.named("down"))
        assertEquals(KeyCodes.HOME, KeyCodes.named("home"))
        assertEquals(KeyCodes.END, KeyCodes.named("end"))
        assertEquals(KeyCodes.PAGE_UP, KeyCodes.named("page_up"))
        assertEquals(KeyCodes.PAGE_DOWN, KeyCodes.named("page_down"))
        assertEquals(KeyCodes.FORWARD_DELETE, KeyCodes.named("forward_delete"))
        assertEquals(KeyCodes.INSERT, KeyCodes.named("insert"))
        assertTrue(KeyCodes.isNavigation(KeyCodes.HOME))
        assertTrue(KeyCodes.repeatsOnModifierRow(KeyCodes.FORWARD_DELETE))
        assertFalse(KeyCodes.isCharacter(KeyCodes.ARROW_DOWN))
        assertTrue(KeyCodes.isHeldModifier(KeyCodes.ALT))
        assertFalse(KeyCodes.isHeldModifier(KeyCodes.TAB))
    }

    @Test
    fun `a character maps to the plain key that carries it`() {
        assertEquals(KeyEvent.KEYCODE_A, PhysicalKeys.keyCodeFor('a'.code))
        assertEquals(KeyEvent.KEYCODE_Z, PhysicalKeys.keyCodeFor('Z'.code))
        assertEquals(KeyEvent.KEYCODE_7, PhysicalKeys.keyCodeFor('7'.code))
        assertEquals(KeyEvent.KEYCODE_SPACE, PhysicalKeys.keyCodeFor(' '.code))
        assertEquals(KeyEvent.KEYCODE_SLASH, PhysicalKeys.keyCodeFor('/'.code))
        assertEquals(0, PhysicalKeys.keyCodeFor('\u00e9'.code))
        assertEquals(0, PhysicalKeys.keyCodeFor('\u0219'.code))
    }
}
