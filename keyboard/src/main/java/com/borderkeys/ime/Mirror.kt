// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Positions along a row that fills from its start edge: the left one, or the right one when the
 * layout's language reads right to left. Pure, for the panels that draw such rows.
 */
internal object Mirror {

    /** Where item [index] of [count] sits along the row, counted from the left edge. */
    fun slot(index: Int, count: Int, rightToLeft: Boolean): Int =
        if (rightToLeft) count - 1 - index else index

    /** The left edge of cell [index], each [cell] wide, in a row [width] wide. */
    fun cellLeft(index: Int, cell: Float, width: Float, rightToLeft: Boolean): Float =
        if (rightToLeft) width - (index + 1) * cell else index * cell

    /**
     * The cell under [x], each [cell] wide, counted from the row's start edge; -1 for an [x]
     * outside the row. A caller with fewer cells than fit checks the result against its count.
     */
    fun cellAt(x: Float, cell: Float, width: Float, rightToLeft: Boolean): Int {
        val fromStart = if (rightToLeft) width - x else x
        return if (fromStart < 0f || fromStart >= width) -1 else (fromStart / cell).toInt()
    }
}
