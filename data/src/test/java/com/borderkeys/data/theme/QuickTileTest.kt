// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTileTest {

    @Test
    fun `the default panel holds the resize and placement tiles and the number row, in that order`() {
        assertEquals(
            listOf(QuickTile.RESIZE, QuickTile.DOCK, QuickTile.LEFT, QuickTile.RIGHT, QuickTile.FLOAT, QuickTile.NUMBER_ROW),
            QuickTile.DEFAULT,
        )
        assertEquals(QuickTile.DEFAULT, KeyboardPreferences().let { QuickTile.fromIds(it.quickTiles) })
    }

    @Test
    fun `ids are stable, unknown ones are dropped and a repeat counts once`() {
        assertEquals(QuickTile.NUMBER_ROW, QuickTile.fromId(6))
        assertEquals(QuickTile.FEATURES, QuickTile.fromId(21))
        assertNull(QuickTile.fromId(99))
        assertEquals(
            listOf(QuickTile.SWIPE, QuickTile.RESIZE),
            QuickTile.fromIds(listOf(8, 99, 1, 8)),
        )
    }

    @Test
    fun `a tile that switches a feature is gated by it, and Enabled or Disabled as the bar's button`() {
        assertEquals(Feature.CLIPBOARD_OFFER, QuickTile.CLIPBOARD_OFFER.feature)
        assertEquals(Feature.SWIPE, QuickTile.SWIPE.feature)
        assertEquals(Feature.STRIP, QuickTile.SUGGESTION_STRIP.feature)
        assertEquals(QuickAction.FEATURES_SWITCH.requires, QuickTile.FEATURES.requires)
        val gated = setOf(QuickTile.CLIPBOARD_OFFER, QuickTile.SWIPE, QuickTile.SUGGESTION_STRIP, QuickTile.FEATURES)
        assertTrue((QuickTile.entries - gated).all { it.feature == null && it.requires == FieldRequirement.NONE })
    }

    @Test
    fun `every id is unique`() {
        assertEquals(QuickTile.entries.size, QuickTile.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `the preferences keep the tiles sanitised and in order`() {
        val stored = KeyboardPreferences(quickTiles = listOf(21, 99, 6, 6, 1)).sanitised()
        assertEquals(listOf(21, 6, 1), stored.quickTiles)
    }
}
