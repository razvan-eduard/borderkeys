// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.theme.ModifierRowKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierRowKeysTest {

    @Test
    fun `unknown names and repeats are dropped, order kept`() {
        assertEquals(
            listOf("home", "escape", "left"),
            ModifierRowKeys.sanitised(listOf("home", "escape", "fn", "home", "left")),
        )
    }

    @Test
    fun `the row takes at most twelve keys`() {
        assertEquals(ModifierRowKeys.MAX, ModifierRowKeys.sanitised(ModifierRowKeys.ALL).size)
    }

    @Test
    fun `the picker and voice keys are known to the row and not in the default`() {
        assertEquals(
            listOf(ModifierRowKeys.KEYBOARD_PICKER, ModifierRowKeys.VOICE),
            ModifierRowKeys.sanitised(listOf("ime_picker", "voice")),
        )
        assertEquals(false, ModifierRowKeys.KEYBOARD_PICKER in ModifierRowKeys.DEFAULT)
    }

    @Test
    fun `the accent modifiers and compose are drawn and offered only while their switch is on`() {
        val row = listOf(ModifierRowKeys.ESCAPE, ModifierRowKeys.DEAD_ACUTE, ModifierRowKeys.COMPOSE, ModifierRowKeys.VOICE)
        assertEquals(listOf(ModifierRowKeys.ESCAPE, ModifierRowKeys.VOICE), ModifierRowKeys.drawn(row, accentKeys = false, voice = true))
        assertEquals(row, ModifierRowKeys.drawn(row, accentKeys = true, voice = true))
        assertEquals(listOf(ModifierRowKeys.ESCAPE, ModifierRowKeys.DEAD_ACUTE, ModifierRowKeys.COMPOSE), ModifierRowKeys.drawn(row, accentKeys = true, voice = false))
        assertEquals(false, ModifierRowKeys.addable(emptyList(), accentKeys = false).any { it in ModifierRowKeys.ACCENT_KEYS })
        assertEquals(ModifierRowKeys.ACCENT_KEYS, ModifierRowKeys.addable(emptyList(), accentKeys = true).filter { it in ModifierRowKeys.ACCENT_KEYS })
        assertEquals(false, ModifierRowKeys.ESCAPE in ModifierRowKeys.addable(listOf(ModifierRowKeys.ESCAPE), accentKeys = true))
    }

    @Test
    fun `the default is eight known keys`() {
        assertEquals(8, ModifierRowKeys.DEFAULT.size)
        assertEquals(ModifierRowKeys.DEFAULT, ModifierRowKeys.sanitised(ModifierRowKeys.DEFAULT))
    }
}
