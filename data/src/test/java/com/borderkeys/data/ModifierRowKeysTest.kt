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
    fun `the default is eight known keys`() {
        assertEquals(8, ModifierRowKeys.DEFAULT.size)
        assertEquals(ModifierRowKeys.DEFAULT, ModifierRowKeys.sanitised(ModifierRowKeys.DEFAULT))
    }
}
