// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No screen may write a sentence into the source.
 *
 * The catalogue only works if everything goes through it, and the way that stops being true is
 * one more `Text("Done")` added in a hurry -- which nobody notices until someone reads the app in
 * a language where that one word is English. So this reads the sources back and fails on the
 * call sites that take text, which is cheaper than a review that has to catch it every time.
 *
 * It checks the shape of the call, not the words: a literal passed as a title is a violation
 * whatever it says. Identifiers, MIME types and log messages are not passed as titles, so they
 * do not come up.
 *
 * Scans whole files rather than one line at a time, and every top-level argument of a positional
 * call rather than only the first -- a literal unit string as `ThemeSlider`'s third argument, or
 * one that simply wraps onto the line after `Text(`, is just as much a sentence left out of the
 * catalogue as one caught by the narrower check this replaced. "Top-level" matters: a literal
 * nested a call or a lambda deeper -- `" · "` joining two already-translated pieces inside a
 * `buildString { }` passed as a `subtitle`, `"%.2f"` formatting a number before it becomes a
 * `label` -- is a separator or a format pattern computing an argument, not prose written as one,
 * and flagging it would make this test fail on code that already goes through the catalogue for
 * everything a person actually reads.
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
            // Comment-only lines are blanked rather than dropped, so every match's character
            // offset still lands on the right original line number when reported below.
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
                // \s* already spans newlines, which is what catches `title =\n    "..."` too --
                // the same class of gap as a positional argument on the line after the call.
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
     * The position of the first literal found at the call's own top level -- outside every
     * nested `(...)`, `{...}` and `[...]` an argument's own value might be computed through --
     * or null if there is none before the call's matching close paren.
     *
     * One combined depth counter does both jobs a naive split-on-commas would need two passes
     * for: it finds where the call itself ends (depth going negative at a `)` is that close
     * paren, found without needing to look past it), and, since a comma can only ever appear
     * between arguments while depth is already back at 0, a literal seen at depth 0 is
     * automatically one no argument's own nested call or lambda is standing between it and this
     * call's own parameter list -- nothing here has to locate the commas at all to know that.
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
        /** The same three-character floor the original per-line regexes both used. */
        const val MIN_LITERAL_CHARS = 3
    }
}
