// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * A keyboard layout as described in an asset: rows of keys with relative widths, no pixels.
 * [KeyboardCanvasView] compiles it into arrays once it knows its size.
 */
class KeyboardLayout(
    val id: String,
    val languageTag: String,
    val rows: List<Row>,
) {
    /** Whether the layout's language is written right to left; the strip and the ring follow it. */
    val rightToLeft: Boolean = languageTag.substringBefore('-').lowercase() in RIGHT_TO_LEFT_LANGUAGES

    class Row(
        /** Leading empty space, in key-width units. */
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

    /** The same layout without its emoji key, its width given to the space bar. */
    fun withoutEmojiKey(): KeyboardLayout = without(KeyCodes.EMOJI, NO_EMOJI_SUFFIX)

    /** The same layout without its globe key, its width given to the space bar. */
    fun withoutLanguageKey(): KeyboardLayout = without(KeyCodes.LANGUAGE, NO_LANGUAGE_SUFFIX)

    /**
     * Drops every key with [code] and gives their width to the row's
     * [KeyFlags.ABSORBS_FREED_WIDTH] key, or else to its space bar. The id gains [suffix].
     */
    private fun without(code: Int, suffix: String): KeyboardLayout {
        if (rows.isEmpty() || id.contains(suffix)) {
            return this
        }
        var found = false
        val rewritten = rows.map { row ->
            if (row.keys.none { it.code == code }) {
                row
            } else {
                found = true
                val width = row.keys.filter { it.code == code }
                    .sumOf { it.widthUnits.toDouble() }.toFloat()
                val remaining = row.keys.filterNot { it.code == code }
                // The row's own absorber if it names one, else the space bar.
                val absorber = remaining.firstOrNull {
                    KeyFlags.has(it.flags, KeyFlags.ABSORBS_FREED_WIDTH)
                } ?: remaining.firstOrNull { it.code == ' '.code }
                Row(
                    row.indent, row.heightScale,
                    remaining.map { key ->
                        if (key === absorber) {
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
            id = id + suffix,
            languageTag = languageTag,
            rows = rewritten,
        )
    }

    /**
     * The same layout with [overlays], lowercase letter to accented forms, appended to the letter
     * keys' long press without repeats. The id gains [signature].
     */
    fun withAccents(overlays: Map<Char, String>, signature: String): KeyboardLayout {
        if (rows.isEmpty() || overlays.isEmpty() || id.contains(ACCENTS_SUFFIX)) {
            return this
        }
        if (rows.none { row -> row.keys.any { KeyFlags.has(it.flags, KeyFlags.LETTER) } }) {
            return this
        }
        val rewritten = rows.map { row ->
            Row(
                row.indent, row.heightScale,
                row.keys.map { key ->
                    val extra = if (KeyFlags.has(key.flags, KeyFlags.LETTER) && key.label.length == 1) {
                        overlays[key.label[0].lowercaseChar()].orEmpty()
                    } else {
                        ""
                    }
                    if (extra.isEmpty()) key else key.withAlternatives(merge(key.alternatives, extra))
                },
            )
        }
        return KeyboardLayout("$id$ACCENTS_SUFFIX$signature", languageTag, rewritten)
    }

    /** The same layout with a digit first on each top-row letter's long press, q 1 to p 0. */
    fun withTopRowDigits(): KeyboardLayout =
        withTopRow(TOP_ROW_DIGITS, TOP_ROW_DIGITS_SUFFIX)

    /** The same layout with a symbol first on each top-row letter's long press, q % to p }. */
    fun withTopRowSymbols(): KeyboardLayout =
        withTopRow(TOP_ROW_SYMBOLS, TOP_ROW_SYMBOLS_SUFFIX)

    private fun withTopRow(hints: String, suffix: String): KeyboardLayout {
        if (rows.isEmpty() || id.contains(suffix)) {
            return this
        }
        val firstLetterRow = rows.indexOfFirst { row -> row.keys.any { KeyFlags.has(it.flags, KeyFlags.LETTER) } }
        if (firstLetterRow < 0) {
            return this
        }
        var next = 0
        val rewritten = rows.mapIndexed { index, row ->
            if (index != firstLetterRow) {
                return@mapIndexed row
            }
            Row(
                row.indent, row.heightScale,
                row.keys.map { key ->
                    if (!KeyFlags.has(key.flags, KeyFlags.LETTER) || next >= hints.length) {
                        key
                    } else {
                        val hint = hints[next]
                        next++
                        key.withAlternatives(merge(hint.toString(), key.alternatives))
                    }
                },
            )
        }
        return KeyboardLayout("$id$suffix", languageTag, rewritten)
    }

    private fun Key.withAlternatives(alternatives: String): Key =
        Key(code, label, alternatives, widthUnits, flags or KeyFlags.HAS_ALTERNATIVES)

    /** The same layout with a shorter row of the ten digits above it. */
    fun withNumberRow(): KeyboardLayout {
        if (rows.isEmpty() || id.contains(NUMBER_ROW_SUFFIX)) {
            return this
        }
        val digits = DIGIT_ROW.map { digit ->
            Key(
                code = digit.code,
                label = digit.toString(),
                alternatives = "",
                widthUnits = 1f,
                // Not a LETTER: swipes and corrections skip digits.
                flags = KeyFlags.PREVIEW or KeyFlags.SECONDARY_ROW,
            )
        }
        return KeyboardLayout(
            id = id + NUMBER_ROW_SUFFIX,
            languageTag = languageTag,
            rows = listOf(Row(0f, NUMBER_ROW_HEIGHT, digits)) + rows,
        )
    }

    /**
     * The same layout with a shorter row of hardware keys: [keys] in order, or the default eight
     * when none can go on the row, sharing its width. At the top, or under the space row when
     * [atBottom].
     */
    fun withModifierRow(
        keys: List<Int> = DEFAULT_MODIFIER_KEYS,
        atBottom: Boolean = false,
    ): KeyboardLayout {
        if (rows.isEmpty() || id.contains(MODIFIER_ROW_SUFFIX) || id.contains(MODIFIER_ROW_BOTTOM_SUFFIX)) {
            return this
        }
        val chosen = keys.filter { MODIFIER_CAPS.containsKey(it) }.distinct()
            .take(MAX_MODIFIER_KEYS)
            .ifEmpty { DEFAULT_MODIFIER_KEYS }
        val width = LETTER_ROW_UNITS / chosen.size
        val row = Row(
            0f, MODIFIER_ROW_HEIGHT,
            chosen.map { code ->
                Key(
                    code = code,
                    label = MODIFIER_CAPS.getValue(code),
                    alternatives = "",
                    widthUnits = width,
                    flags = if (KeyCodes.repeatsOnModifierRow(code)) {
                        KeyFlags.MODIFIER or KeyFlags.REPEATABLE
                    } else {
                        KeyFlags.MODIFIER
                    },
                )
            },
        )
        val suffix = (if (atBottom) MODIFIER_ROW_BOTTOM_SUFFIX else MODIFIER_ROW_SUFFIX) +
            chosen.joinToString(".") { (-it).toString() }
        return KeyboardLayout(
            id = id + suffix,
            languageTag = languageTag,
            rows = if (atBottom) rows + row else listOf(row) + rows,
        )
    }

    companion object {
        /** The languages written right to left, by the language part of their tag. */
        private val RIGHT_TO_LEFT_LANGUAGES =
            setOf("he", "iw", "ar", "fa", "ur", "yi", "ji", "ps", "ug", "sd", "ckb", "dv")

        private const val NUMBER_ROW_SUFFIX = "+num"
        private const val MODIFIER_ROW_SUFFIX = "+mod"
        private const val MODIFIER_ROW_BOTTOM_SUFFIX = "+modb"

        /** The caps of every key the modifier row can carry. */
        private val MODIFIER_CAPS: Map<Int, String> = mapOf(
            KeyCodes.ESCAPE to "esc",
            KeyCodes.TAB to "tab",
            KeyCodes.CONTROL to "ctrl",
            KeyCodes.ALT to "alt",
            KeyCodes.ARROW_LEFT to "\u2190",
            KeyCodes.ARROW_DOWN to "\u2193",
            KeyCodes.ARROW_UP to "\u2191",
            KeyCodes.ARROW_RIGHT to "\u2192",
            KeyCodes.HOME to "home",
            KeyCodes.END to "end",
            KeyCodes.PAGE_UP to "pgup",
            KeyCodes.PAGE_DOWN to "pgdn",
            KeyCodes.FORWARD_DELETE to "del",
            KeyCodes.INSERT to "ins",
        )

        /** The row as shipped, left to right. */
        val DEFAULT_MODIFIER_KEYS: List<Int> = listOf(
            KeyCodes.ESCAPE, KeyCodes.TAB, KeyCodes.CONTROL, KeyCodes.ALT,
            KeyCodes.ARROW_LEFT, KeyCodes.ARROW_DOWN, KeyCodes.ARROW_UP, KeyCodes.ARROW_RIGHT,
        )

        /** The cap of a modifier-row key that a layout asset names without a label. */
        internal fun modifierCap(code: Int): String? = MODIFIER_CAPS[code]

        /** The most keys the modifier row takes. */
        const val MAX_MODIFIER_KEYS = 12

        /** The width the keys share: that of a ten-key letter row. */
        private const val LETTER_ROW_UNITS = 10f
        private const val MODIFIER_ROW_HEIGHT = 0.8f
        private const val ACCENTS_SUFFIX = "+acc"
        private const val TOP_ROW_DIGITS_SUFFIX = "+dig"
        private const val TOP_ROW_SYMBOLS_SUFFIX = "+sym"

        /** q..p when there is no number row. */
        private const val TOP_ROW_DIGITS = "1234567890"

        /** q..p when the number row has the digits. */
        private const val TOP_ROW_SYMBOLS = "%^~|[]<>{}"

        private const val NO_EMOJI_SUFFIX = "-noemoji"
        private const val NO_LANGUAGE_SUFFIX = "-noglobe"

        /** [prefix] then [rest], dropping any character already present earlier in the result. */
        private fun merge(prefix: String, rest: String): String {
            val seen = StringBuilder(prefix.length + rest.length)
            for (character in prefix + rest) {
                if (seen.indexOf(character.toString()) < 0) {
                    seen.append(character)
                }
            }
            return seen.toString()
        }

        /** The number row: the ten digits, nothing more. */
        private const val DIGIT_ROW = "1234567890"
        private const val NUMBER_ROW_HEIGHT = 0.8f

        /** A built-in QWERTY, used when a layout asset is missing or malformed. */
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
