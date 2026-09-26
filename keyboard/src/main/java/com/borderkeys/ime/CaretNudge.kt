// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.max
import kotlin.math.min

/**
 * Where a slide along the space bar puts the caret, and what it selects while shift is held.
 *
 * Pure: the service hands in the field's selection and the last answer it applied, and writes
 * the answer back through the editor.
 */
object CaretNudge {

    /** A selection as the editor takes it: the end that stays, and the end the finger moves. */
    class Selection(val anchor: Int, val caret: Int) {
        val start: Int get() = min(anchor, caret)
        val end: Int get() = max(anchor, caret)
        val empty: Boolean get() = anchor == caret

        override fun equals(other: Any?): Boolean =
            other is Selection && other.anchor == anchor && other.caret == caret

        override fun hashCode(): Int = anchor * 31 + caret
    }

    /**
     * The selection after a slide of [steps] characters, positive to the right, in a field of
     * [length] characters whose selection runs from [start] to [end].
     *
     * Not [selecting]: the caret goes [steps] from [end] and nothing stays selected. Selecting:
     * the caret goes on from where the last slide left it when [previous] is still the field's
     * selection, so one drag keeps one anchor; otherwise the anchor is [start] and the caret
     * moves from [end], which extends a selection made some other way from its end.
     */
    fun slide(
        start: Int,
        end: Int,
        previous: Selection?,
        steps: Int,
        length: Int,
        selecting: Boolean,
    ): Selection {
        if (!selecting) {
            val target = (end + steps).coerceIn(0, length)
            return Selection(target, target)
        }
        val continuing = previous != null && previous.start == start && previous.end == end
        val anchor = if (continuing) previous.anchor else start
        val from = if (continuing) previous.caret else end
        return Selection(anchor, (from + steps).coerceIn(0, length))
    }
}
