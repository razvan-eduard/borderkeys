// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStateTest {

    @Test
    fun `the tile is lit only while this build is the keyboard in use`() {
        assertTrue(TileState.of(enabled = true, inUse = true).active)
        assertFalse(TileState.of(enabled = true, inUse = false).active)
        assertFalse(TileState.of(enabled = false, inUse = false).active)
    }

    @Test
    fun `the line under the name says what is still to do`() {
        assertEquals(Keys.TILE_IN_USE, TileState.of(enabled = true, inUse = true).subtitleKey)
        assertEquals(Keys.TILE_NOT_IN_USE, TileState.of(enabled = true, inUse = false).subtitleKey)
        assertEquals(Keys.TILE_NOT_ENABLED, TileState.of(enabled = false, inUse = false).subtitleKey)
    }

    @Test
    fun `a keyboard in use counts as enabled whatever the list says`() {
        assertEquals(TileState.InUse, TileState.of(enabled = false, inUse = true))
    }
}
