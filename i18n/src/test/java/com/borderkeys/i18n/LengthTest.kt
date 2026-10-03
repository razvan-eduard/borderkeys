// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No translation runs far past the English it stands for: 2.0 times for a title, 1.75 times for
 * the rest, measured in code points with a placeholder counted as four, for English longer than
 * forty. Keys listed in `length.json` are exempt.
 */
class LengthTest {

    private val exceptions = Catalogues.strings(Catalogues.resource("length.json")["exceptions"]).toSet()

    @Test
    fun `no translation is too long`() {
        val english = Catalogues.of(Strings.Languages.DEFAULT)
        val problems = mutableListOf<String>()
        for ((key, text) in english) {
            val base = length(text)
            if (base <= MIN_ENGLISH || key in exceptions) continue
            val limit = if (isTitle(key)) TITLE_RATIO else TEXT_RATIO
            for (language in Catalogues.translated) {
                if (!Catalogues.carries(language, key)) continue
                val ratio = length(Catalogues.of(language).getValue(key)).toDouble() / base
                if (ratio > limit) problems += "$language:$key is ${"%.2f".format(ratio)}× English"
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `a placeholder counts as four`() {
        assertEquals(7, length("%s ok"))
        assertEquals(3, length("née"))
    }

    @Test
    fun `every listed key exists`() {
        val english = Catalogues.of(Strings.Languages.DEFAULT)
        assertEquals(emptyList<String>(), exceptions.filterNot { it in english })
    }

    private fun length(text: String): Int {
        val placeholders = text.windowed(PLACEHOLDER.length).count { it == PLACEHOLDER }
        val rest = text.replace(PLACEHOLDER, "")
        return rest.codePointCount(0, rest.length) + placeholders * PLACEHOLDER_LENGTH
    }

    private fun isTitle(key: String): Boolean = key.endsWith("_title") || key.startsWith("screen_")

    private companion object {
        const val MIN_ENGLISH = 40
        const val TITLE_RATIO = 2.0
        const val TEXT_RATIO = 1.75
        const val PLACEHOLDER = "%s"
        const val PLACEHOLDER_LENGTH = 4
    }
}
