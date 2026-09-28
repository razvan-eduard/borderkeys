// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No screen may write a sentence into the source. Fails on a call that takes text and is given a
 * string literal at its top level, in any positional argument or a named text argument, wherever
 * the argument wraps. A literal nested inside another call or a lambda is not flagged.
 */
class NoHardcodedTextTest {

    /** Function names whose call is a violation if any top-level argument is a literal. */
    private val positional = listOf(
        "SectionHeader", "Explanation", "Text", "SettingRow", "ColourRow", "ThemeSlider",
        "ModeChip", "SpeedChip",
    )
    private val named = listOf("title", "subtitle", "label", "text", "description", "summary")

    @Test
    fun `no user-facing literal is left in a settings source`() {
        val offences = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            // Comment-only lines are blanked, not dropped, so offsets keep their line numbers.
            val text = file.readLines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) "" else line
            }

            for (name in positional) {
                for (match in Regex("""\b$name\(""").findAll(text)) {
                    topLevelLiteral(text, match.range.last + 1)?.let { literalStart ->
                        offences += offence(file, text, literalStart)
                    }
                }
            }
            for (name in named) {
                // \s* spans newlines, so `title =\n    "..."` is caught too.
                for (match in Regex("""\b$name\s*=\s*"[^"]{3,}""").findAll(text)) {
                    offences += offence(file, text, match.range.first)
                }
            }
        }
        assertEquals(
            "put the text in i18n/src/main/assets/translations/en.json and use strings[Keys.…]",
            emptyList<String>(),
            offences,
        )
    }

    /**
     * The position of the first literal at the call's own top level, outside every nested
     * `(...)`, `{...}` and `[...]`, or null if there is none before the call's matching close
     * paren. One depth counter finds both: depth going negative at a `)` is that close paren.
     */
    private fun topLevelLiteral(text: String, openParenEnd: Int): Int? {
        var depth = 0
        var index = openParenEnd
        var inString = false
        while (index < text.length) {
            val c = text[index]
            if (inString) {
                when (c) {
                    '\\' -> index++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> {
                        if (depth == 0 && isLiteralHere(text, index)) {
                            return index
                        }
                        inString = true
                    }
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> {
                        if (depth == 0) {
                            return null
                        }
                        depth--
                    }
                }
            }
            index++
        }
        return null
    }

    /** Whether the `"` at [start] opens a literal at least [MIN_LITERAL_CHARS] characters long. */
    private fun isLiteralHere(text: String, start: Int): Boolean {
        val end = text.indexOf('"', start + 1)
        return end != -1 && end - start - 1 >= MIN_LITERAL_CHARS
    }

    private fun offence(file: File, text: String, matchStart: Int): String {
        val lineNumber = text.substring(0, matchStart).count { it == '\n' } + 1
        val lineText = text.lineSequence().elementAt(lineNumber - 1)
        return "${file.name}:$lineNumber  ${lineText.trim().take(90)}"
    }

    private companion object {
        /** A shorter literal is not counted as text. */
        const val MIN_LITERAL_CHARS = 3
    }
}
