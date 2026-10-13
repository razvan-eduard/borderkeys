// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.geometry.bothSides
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a card's top outline is left open around its title. */
class SettingsSectionCardTest {

    private val width = 1000f
    private val inset = 50f
    private val margin = 10f
    private val titleWidth = 300f

    @Test
    fun `left to right, the cut spans the title at the start, a margin past each end`() {
        val cut = titleCut(width, inset, titleWidth, lineLeft = 0f, lineRight = titleWidth, margin, rtl = false)
        assertEquals(inset - margin, cut.start, 0f)
        assertEquals(inset + titleWidth + margin, cut.endInclusive, 0f)
    }

    @Test
    fun `right to left, the cut sits at the right`() {
        val cut = titleCut(width, inset, titleWidth, lineLeft = 0f, lineRight = titleWidth, margin, rtl = true)
        assertEquals(width - inset - titleWidth - margin, cut.start, 0f)
        assertEquals(width - inset + margin, cut.endInclusive, 0f)
    }

    @Test
    fun `a wrapped title is cut over its first line only`() {
        val wrapped = width - bothSides(inset)
        val firstLine = 600f
        val ltr = titleCut(width, inset, wrapped, lineLeft = 0f, lineRight = firstLine, margin, rtl = false)
        assertEquals(inset - margin, ltr.start, 0f)
        assertEquals(inset + firstLine + margin, ltr.endInclusive, 0f)
        val rtl = titleCut(width, inset, wrapped, lineLeft = wrapped - firstLine, lineRight = wrapped, margin, rtl = true)
        assertEquals(width - inset - firstLine - margin, rtl.start, 0f)
        assertEquals(width - inset + margin, rtl.endInclusive, 0f)
    }
}
