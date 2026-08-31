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

    companion object {
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
