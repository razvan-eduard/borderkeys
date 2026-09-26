// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.max
import kotlin.math.min

/**
 * Where a slide along the space bar puts the caret, sideways by characters or up and down by
 * lines, and what it selects while shift is held.
 *
 * Pure: the service hands in the field's text and selection and the last answer it applied,
 * and writes the answer back through the editor. A line is what a line break ends; a long
 * line the editor wraps on screen counts as one.
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
        val continuing = continuing(start, end, previous, selecting)
        val from = if (continuing) previous!!.caret else end
        return settle(start, previous, continuing, (from + steps).coerceIn(0, length), selecting)
    }

    /**
     * The selection after a slide of [lines] lines, positive downwards, through [text]: the
     * caret keeps its column where the line it lands on is long enough, and stops at the end
     * of a shorter one. The anchor follows the same rule as [slide].
     */
    fun slideLines(
        text: CharSequence,
        start: Int,
        end: Int,
        previous: Selection?,
        lines: Int,
        selecting: Boolean,
    ): Selection {
        val continuing = continuing(start, end, previous, selecting)
        val from = (if (continuing) previous!!.caret else end).coerceIn(0, text.length)
        return settle(start, previous, continuing, lineTarget(text, from, lines), selecting)
    }

    /** The offset [lines] lines from [offset] in [text], at the same column when there is one. */
    fun lineTarget(text: CharSequence, offset: Int, lines: Int): Int {
        var lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val column = offset - lineStart
        var remaining = lines
        while (remaining < 0 && lineStart > 0) {
            lineStart = text.lastIndexOf('\n', lineStart - 2) + 1
            remaining++
        }
        while (remaining > 0) {
            val nextBreak = text.indexOf('\n', lineStart)
            if (nextBreak < 0) {
                break
            }
            lineStart = nextBreak + 1
            remaining--
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        return min(lineStart + column, lineEnd)
    }

    /** Whether [previous] is still the field's selection, so this slide goes on from it. */
    private fun continuing(start: Int, end: Int, previous: Selection?, selecting: Boolean): Boolean =
        selecting && previous != null && previous.start == start && previous.end == end

    private fun settle(
        start: Int,
        previous: Selection?,
        continuing: Boolean,
        target: Int,
        selecting: Boolean,
    ): Selection {
        if (!selecting) {
            return Selection(target, target)
        }
        val anchor = if (continuing) previous!!.anchor else start
        return Selection(anchor, target)
    }
}
