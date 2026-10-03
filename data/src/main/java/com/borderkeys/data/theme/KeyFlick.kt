// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * What a short drag off a key in one of eight directions does: writes [value] as text, runs the
 * quick action whose id [value] holds, or presses the key [value] names. One per key and
 * direction; [label] is what the key's edge shows, at most [MAX_LABEL_CHARS] characters, and
 * defaults to the text, the action's name or the key's name when empty.
 */
@Serializable
data class KeyFlick(
    /** The key's code: a code point, or a named key's negative code. */
    val keyCode: Int,
    /** [NORTH] to [NORTH_WEST], clockwise. */
    val direction: Int,
    /** [TEXT], [COMMAND] or [KEY]. */
    val kind: Int,
    val value: String,
    val label: String = "",
) {
    /** Whether the fields are within their bounds and [value] fits [kind]. */
    val isValid: Boolean
        get() = direction in NORTH..NORTH_WEST && label.length <= MAX_LABEL_CHARS && when (kind) {
            TEXT -> value.isNotEmpty() && value.length <= MAX_TEXT_CHARS
            COMMAND -> value.toIntOrNull()?.let { QuickAction.fromId(it) } != null
            KEY -> value.isNotEmpty() && value.length <= MAX_KEY_NAME_CHARS && value.all { it.isLetterOrDigit() || it == '_' }
            else -> false
        }

    companion object {
        const val NORTH = 0
        const val NORTH_EAST = 1
        const val EAST = 2
        const val SOUTH_EAST = 3
        const val SOUTH = 4
        const val SOUTH_WEST = 5
        const val WEST = 6
        const val NORTH_WEST = 7
        const val DIRECTIONS = 8

        const val TEXT = 0
        const val COMMAND = 1
        const val KEY = 2

        const val MAX_LABEL_CHARS = 4
        const val MAX_TEXT_CHARS = 200
        const val MAX_KEY_NAME_CHARS = 24

        /** Eight per key, for as many keys as a layout has. */
        const val MAX_FLICKS = 800

        /** The valid flicks, one per key and direction, the later one winning, bounded. */
        fun sanitised(flicks: List<KeyFlick>): List<KeyFlick> {
            val bySlot = LinkedHashMap<Long, KeyFlick>()
            for (flick in flicks) {
                if (!flick.isValid) {
                    continue
                }
                bySlot[slot(flick.keyCode, flick.direction)] = flick.copy(
                    value = if (flick.kind == TEXT) flick.value else flick.value.trim(),
                    label = flick.label.trim(),
                )
            }
            return bySlot.values.toList().take(MAX_FLICKS)
        }

        /** One number per key and direction. */
        fun slot(keyCode: Int, direction: Int): Long = keyCode.toLong() * DIRECTIONS + direction

        /** The label shown on the key: [label], or the first characters of a text, or nothing for the rest. */
        fun shownLabel(flick: KeyFlick): String = when {
            flick.label.isNotEmpty() -> flick.label
            flick.kind == TEXT -> flick.value.take(MAX_LABEL_CHARS).trimEnd()
            else -> ""
        }
    }
}
