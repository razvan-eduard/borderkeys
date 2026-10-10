// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where [NodeSlider]'s nodes sit, and which one a touch lands on. */
class NodeSliderTest {

    private val width = 1000f
    private val inset = 20f

    @Test
    fun `the nodes spread evenly between the insets, the first at the start`() {
        assertEquals(20f, nodeX(0, 5, width, inset, rtl = false), 0.01f)
        assertEquals(500f, nodeX(2, 5, width, inset, rtl = false), 0.01f)
        assertEquals(980f, nodeX(4, 5, width, inset, rtl = false), 0.01f)
    }

    @Test
    fun `right to left, the first node sits at the right`() {
        assertEquals(980f, nodeX(0, 5, width, inset, rtl = true), 0.01f)
        assertEquals(20f, nodeX(4, 5, width, inset, rtl = true), 0.01f)
    }

    @Test
    fun `a touch lands on the nearest node, and beyond the ends on the end ones`() {
        assertEquals(0, nodeAt(0f, 5, width, inset, rtl = false))
        assertEquals(1, nodeAt(300f, 5, width, inset, rtl = false))
        assertEquals(2, nodeAt(500f, 5, width, inset, rtl = false))
        assertEquals(4, nodeAt(width, 5, width, inset, rtl = false))
        assertEquals(4, nodeAt(0f, 5, width, inset, rtl = true))
    }

    @Test
    fun `a touch lands where the node it means is drawn`() {
        for (count in 2..7) {
            for (index in 0 until count) {
                for (rtl in listOf(false, true)) {
                    val x = nodeX(index, count, width, inset, rtl)
                    assertEquals("node $index of $count, rtl $rtl", index, nodeAt(x, count, width, inset, rtl))
                }
            }
        }
    }

    @Test
    fun `a single node is always the one`() {
        assertEquals(0, nodeAt(700f, 1, width, inset, rtl = false))
    }
}
