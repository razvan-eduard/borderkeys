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
 */
class NoHardcodedTextTest {

    /** Argument positions whose value is read by a person. */
    private val positional = listOf(
        "SectionHeader", "Explanation", "Text", "SettingRow", "ColourRow", "ThemeSlider",
        "ModeChip", "SpeedChip",
    )
    private val named = listOf("title", "subtitle", "label", "text", "description", "summary")

    @Test
    fun `no user-facing literal is left in a settings source`() {
        val offences = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*")) return@forEachIndexed
                val patterns = positional.map { Regex("""\b$it\(\s*"[^"]{3,}""") } +
                    named.map { Regex("""\b$it\s*=\s*"[^"]{3,}""") }
                if (patterns.any { it.containsMatchIn(line) }) {
                    offences += "${file.name}:${index + 1}  ${code.take(90)}"
                }
            }
        }
        assertEquals(
            "put the text in i18n/src/main/assets/translations/en.json and use strings[Keys.…]",
            emptyList<String>(),
            offences,
        )
    }
}
