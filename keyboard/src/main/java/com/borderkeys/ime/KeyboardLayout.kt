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
    fun withoutEmojiKey(): KeyboardLayout = without(KeyCodes.EMOJI, NO_EMOJI_SUFFIX)

    /**
     * The same layout without its globe key.
     *
     * The globe cycles this keyboard's layouts, which most people do never and some do daily,
     * so it is worth a key to them and worth nothing to everyone else. Off, the space bar takes
     * the width and holding the space bar cycles the layouts instead.
     */
    fun withoutLanguageKey(): KeyboardLayout = without(KeyCodes.LANGUAGE, NO_LANGUAGE_SUFFIX)

    /**
     * Drops every key with the given code and gives their width to the space bar.
     *
     * The id gains a suffix because the keyboard caches compiled geometry by it: two layouts
     * that differ by a key must not be able to answer to the same name.
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
                Row(
                    row.indent, row.heightScale,
                    row.keys.filterNot { it.code == code }.map { key ->
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
            id = id + suffix,
            label = label,
            languageTag = languageTag,
            rows = rewritten,
        )
    }

    /**
     * The same layout with diacritics merged onto the letter keys' long press.
     *
     * [overlays] maps a lowercase letter to the accented forms of it that an enabled language
     * pack contributes, already concatenated in enabled order. They go *after* whatever the base
     * layout put on the key, so the corner hint stays the symbol -- the diacritics are behind
     * it in the long-press strip -- and a character already reachable is not added twice.
     *
     * [signature] distinguishes one merged result from another in the id, because the compiled
     * geometry is cached by id and two accent sets must not answer to the same name.
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
        return KeyboardLayout("$id$ACCENTS_SUFFIX$signature", label, languageTag, rewritten)
    }

    /**
     * The same layout with a digit at the front of each key of the top letter row's long press.
     *
     * q holds 1, w holds 2, on to p holds 0 -- the digit ahead of any diacritic, so the corner
     * hint is always the digit and never an accent. Applied whether or not the number row is
     * shown: the hint stays a plain digit either way, and holding for it costs nothing.
     */
    fun withTopRowDigits(): KeyboardLayout {
        if (rows.isEmpty() || id.contains(TOP_ROW_DIGITS_SUFFIX)) {
            return this
        }
        val firstLetterRow = rows.indexOfFirst { row -> row.keys.any { KeyFlags.has(it.flags, KeyFlags.LETTER) } }
        if (firstLetterRow < 0) {
            return this
        }
        var digit = 0
        val rewritten = rows.mapIndexed { index, row ->
            if (index != firstLetterRow) {
                return@mapIndexed row
            }
            Row(
                row.indent, row.heightScale,
                row.keys.map { key ->
                    if (!KeyFlags.has(key.flags, KeyFlags.LETTER)) {
                        key
                    } else {
                        val character = ('0' + (digit + 1) % 10)
                        digit++
                        key.withAlternatives(merge(character.toString(), key.alternatives))
                    }
                },
            )
        }
        return KeyboardLayout("$id$TOP_ROW_DIGITS_SUFFIX", label, languageTag, rewritten)
    }

    private fun Key.withAlternatives(alternatives: String): Key =
        Key(code, label, alternatives, widthUnits, flags or KeyFlags.HAS_ALTERNATIVES)

    fun withNumberRow(): KeyboardLayout {
        if (rows.isEmpty() || id.contains(NUMBER_ROW_SUFFIX)) {
            return this
        }
        // Just the ten digits, nothing on the long press: a physical keyboard's number row is
        // digits, and every symbol worth shifting to is already on a letter's long press or a
        // ?123 page. SECONDARY_ROW sets it apart from the letters visually, the way that row is
        // set apart on a hardware keyboard.
        val digits = DIGIT_ROW.map { digit ->
            Key(
                code = digit.code,
                label = digit.toString(),
                alternatives = "",
                widthUnits = 1f,
                // Not a LETTER: a swipe must not pass through a digit, and a digit is never a
                // substitution target when correcting a typo.
                flags = KeyFlags.PREVIEW or KeyFlags.SECONDARY_ROW,
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
        private const val ACCENTS_SUFFIX = "+acc"
        private const val TOP_ROW_DIGITS_SUFFIX = "+dig"

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
                            // REPEATABLE -- see LayoutLoader's identical note.
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
