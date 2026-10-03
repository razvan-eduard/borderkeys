// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No keyboard source may show a sentence written in the code. Fails on a prose-shaped literal
 * given to a named text argument or to a call that shows or announces text, with comments
 * blanked and the brand names allowed.
 */
class NoHardcodedTextTest {

    @Test
    fun `no user-facing literal is left in a keyboard source`() {
        val offences = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> offences(blankComments(file.readText())).map { "${file.name}:$it" } }
            .toList()
        assertEquals(
            "put the text in i18n/src/main/assets/translations/en.json and use strings[Keys.…]",
            emptyList<String>(),
            offences,
        )
    }

    @Test
    fun `the scan finds a literal in each position and passes the rest`() {
        val source = """
            view.contentDescription = "Delete the word"
            toast(text = "Copied to the clipboard")
            host.showNotice("Nothing to paste")
            node.setText("Hold for more")
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT)
            // label = "a comment, not code"
            /* hint = "nor this" */
            title = "BorderKeys"
            val tag = "BorderKeysService"
            Log.w(TAG, "a log line is not shown")
            key = "ab"
        """.trimIndent()
        assertEquals(listOf(1, 2, 3, 4, 5), offences(blankComments(source)).map { it.substringBefore(' ').toInt() })
    }

    /** `line  text` for each offence in [text]. */
    private fun offences(text: String): List<String> {
        val found = sortedMapOf<Int, String>()
        for (pattern in listOf(NAMED, CALLED)) {
            for (match in pattern.findAll(text)) {
                val literal = match.groupValues[1]
                if (!isProse(literal)) continue
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                found[line] = text.lineSequence().elementAt(line - 1).trim().take(90)
            }
        }
        return found.map { (line, code) -> "$line  $code" }
    }

    /** Whether [literal] reads as text a person sees: letters, not only a brand or an identifier. */
    private fun isProse(literal: String): Boolean {
        if (literal.length < MIN_LITERAL_CHARS || literal in BRANDS) return false
        if (literal.none { it.isLetter() }) return false
        return literal.contains(' ') || literal.first().isUpperCase() && literal.drop(1).any { it.isLowerCase() }
    }

    /** [text] with every comment replaced by spaces, so offsets keep their line numbers. */
    private fun blankComments(text: String): String {
        val out = StringBuilder(text)
        var index = 0
        var inString = false
        while (index < text.length) {
            val c = text[index]
            when {
                inString && c == '\\' -> index++
                !inString && c == '\'' -> {
                    index += if (text.getOrNull(index + 1) == '\\') 4 else 3
                    continue
                }
                c == '"' -> inString = !inString
                !inString && text.startsWith("//", index) -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    for (i in index until end) out[i] = ' '
                    index = end
                    continue
                }
                !inString && text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                    for (i in index until end) if (out[i] != '\n') out[i] = ' '
                    index = end
                    continue
                }
            }
            index++
        }
        return out.toString()
    }

    private companion object {
        const val MIN_LITERAL_CHARS = 3
        val BRANDS = setOf("BorderKeys", "BorderKeys Plus")
        val NAMED = Regex(
            """\b(?:text|title|subtitle|label|hint|placeholder|contentDescription|stateDescription|""" +
                """tooltipText|summary|description)\s*=\s*"([^"\\]*)"""",
        )
        val CALLED = Regex(
            """\b(?:setText|setTitle|setHint|setContentDescription|setStateDescription|setTooltipText|""" +
                """announceForAccessibility|showNotice|showAccent|makeText)\((?:[^()"]*,\s*)?"([^"\\]*)"""",
        )
    }
}
