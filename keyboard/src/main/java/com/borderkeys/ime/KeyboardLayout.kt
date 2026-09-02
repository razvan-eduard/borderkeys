// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * A keyboard layout as described in an asset: rows of keys with relative widths, no pixels.
 *
 * Read once, at service start, off the UI thread. It allocates freely -- it is a parse result,
 * not something the draw path touches. [KeyboardCanvasView] compiles it into parallel arrays of
 * primitives the first time it knows its own size, and after that this object is only consulted
 * again if the layout itself changes.
 *
 * Deliberately not a `List<Key>` held by the view. The point of the compile step is that the
 * rendering and hit-testing paths never dereference an object per key.
 */
class KeyboardLayout(
    val id: String,
    val label: String,
    val languageTag: String,
    val rows: List<Row>,
) {
    class Row(
        /** Leading empty space, in key-width units. Half a unit is the classic QWERTY stagger. */
        val indent: Float,
        /** Row height as a multiple of the theme's row height. */
        val heightScale: Float,
        val keys: List<Key>,
    ) {
        /** Total width of the row in units, including the indent. Never zero. */
        val units: Float = indent + keys.sumOf { it.widthUnits.toDouble() }.toFloat()
    }

    class Key(
        val code: Int,
        /** What is drawn on the key. May differ from the code: "⌫" for delete. */
        val label: String,
        /** Characters reachable by long press, in order. Empty when there are none. */
        val alternatives: String,
        val widthUnits: Float,
        val flags: Int,
    )

    val keyCount: Int = rows.sumOf { it.keys.size }

    val totalHeightScale: Float = rows.sumOf { it.heightScale.toDouble() }.toFloat()

    /**
     * The same layout with a row of digits above it.
     *
     * A number row is a real trade, not a preference to be defaulted: it removes a keystroke
     * from every digit and takes about a fifth of the keyboard's height away from the letters,
     * on a surface where key size is accuracy. So it is offered as a setting, and the
     * alternative -- long-pressing the top letter row, where the digits also live -- costs
     * nothing to anyone who leaves it off.
     *
     * The row is shorter than a letter row: digits are hit less often and need less area, and
     * taking a full row would cost the letters more than the digits gain.
     */
    /**
     * The same layout without its emoji key, with the width handed back to the space bar.
     *
     * Removed rather than hidden: a key that is drawn and does nothing is worse than no key,
     * and the space bar is the one that lost the width when the emoji key took it, so it is
     * the one that gets it back.
     */
    fun withoutEmojiKey(): KeyboardLayout {
        if (rows.isEmpty()) {
            return this
        }
        var found = false
        val rewritten = rows.map { row ->
            if (row.keys.none { it.code == KeyCodes.EMOJI }) {
                row
            } else {
                found = true
                val width = row.keys.filter { it.code == KeyCodes.EMOJI }
                    .sumOf { it.widthUnits.toDouble() }.toFloat()
                Row(
                    row.indent, row.heightScale,
                    row.keys.filterNot { it.code == KeyCodes.EMOJI }.map { key ->
                        if (key.code == ' '.code) {
                            Key(key.code, key.label, key.alternatives,
                                key.widthUnits + width, key.flags)
                        } else {
                            key
                        }
                    },
                )
            }
        }
        if (!found) {
            return this
        }
        return KeyboardLayout(
            id = id + NO_EMOJI_SUFFIX,
            label = label,
            languageTag = languageTag,
            rows = rewritten,
        )
    }

    fun withNumberRow(): KeyboardLayout {
        if (rows.isEmpty() || id.contains(NUMBER_ROW_SUFFIX)) {
            return this
        }
        // Digit on the key, the symbol a physical keyboard puts above it on the long press.
        // That pairing is the one people already know, and it keeps the digits off the letter
        // row -- where the long press belongs to the diacritics, and where a digit competing
        // with "t" for "t" would be a collision rather than a convenience.
        val digits = DIGIT_ROW.map { (digit, shifted) ->
            Key(
                code = digit.code,
                label = digit.toString(),
                alternatives = shifted.toString(),
                widthUnits = 1f,
                // Not a LETTER: a swipe must not pass through a digit, and a digit is never a
                // substitution target when correcting a typo.
                flags = KeyFlags.PREVIEW or KeyFlags.HAS_ALTERNATIVES,
            )
        }
        return KeyboardLayout(
            id = id + NUMBER_ROW_SUFFIX,
            label = label,
            languageTag = languageTag,
            rows = listOf(Row(0f, NUMBER_ROW_HEIGHT, digits)) + rows,
        )
    }

    companion object {
        private const val NUMBER_ROW_SUFFIX = "+num"

        private const val NO_EMOJI_SUFFIX = "-noemoji"

        /** The top row of a physical keyboard, unshifted and shifted. */
        private val DIGIT_ROW = "1234567890".zip("!@#$%^&*()")
        private const val NUMBER_ROW_HEIGHT = 0.8f

        /**
         * A layout that needs no asset and no parsing.
         *
         * Not a placeholder: it is what the keyboard falls back to when an asset is missing or
         * malformed. An input method that fails to draw is an input method the user cannot
         * uninstall without another one already installed, so there is always something to show.
         */
        fun fallbackQwerty(): KeyboardLayout {
            fun letters(characters: String): List<Key> = characters.map { character ->
                Key(
                    code = character.code,
                    label = character.toString(),
                    alternatives = "",
                    widthUnits = 1f,
                    flags = KeyFlags.LETTER or KeyFlags.PREVIEW,
                )
            }
            return KeyboardLayout(
                id = "fallback_qwerty",
                label = "QWERTY",
                languageTag = "en-US",
                rows = listOf(
                    Row(0f, 1f, letters("qwertyuiop")),
                    Row(0.5f, 1f, letters("asdfghjkl")),
                    Row(
                        0f,
                        1f,
                        listOf(
                            Key(KeyCodes.SHIFT, "⇧", "", 1.5f, KeyFlags.MODIFIER),
                        ) + letters("zxcvbnm") + listOf(
                            Key(
                                KeyCodes.DELETE, "⌫", "", 1.5f,
                                KeyFlags.MODIFIER or KeyFlags.REPEATABLE,
                            ),
                        ),
                    ),
                    Row(
                        0f,
                        1f,
                        listOf(
                            Key(KeyCodes.SYMBOLS, "?123", "", 1.5f, KeyFlags.MODIFIER),
                            Key(','.code, ",", "", 1f, KeyFlags.PREVIEW),
                            Key(KeyCodes.SPACE, " ", "", 5f, KeyFlags.NONE),
                            Key('.'.code, ".", "", 1f, KeyFlags.PREVIEW),
                            Key(KeyCodes.ENTER, "⏎", "", 1.5f, KeyFlags.MODIFIER),
                        ),
                    ),
                ),
            )
        }
    }
}
