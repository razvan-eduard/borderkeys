// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * No string is left in English across the languages: each one differs from English in more than
 * twelve of every twenty-one translated languages. A string with no word of three letters beyond
 * its placeholders and the terms in `untranslated.json`, a language's own name, and the keys that
 * file lists are exempt.
 */
class UntranslatedShareTest {

    private val allowed = Catalogues.resource("untranslated.json")
    private val terms = Catalogues.strings(allowed["terms"]).toSet()
    private val keys = Catalogues.strings(allowed["keys"]).toSet()

    @Test
    fun `no string is left in english in too many languages`() {
        val english = Catalogues.of(Strings.Languages.DEFAULT)
        val limit = Catalogues.translated.size * MAX_SAME / OF
        val problems = english.filter { (key, text) -> translatable(key, text) }.mapNotNull { (key, text) ->
            val same = Catalogues.translated.filter { Catalogues.of(it)[key] == text }
            if (same.size > limit) "$key is English in ${same.joinToString()}" else null
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `every listed key exists`() {
        val english = Catalogues.of(Strings.Languages.DEFAULT)
        assertEquals(emptyList<String>(), keys.filterNot { it in english })
    }

    private fun translatable(key: String, text: String): Boolean {
        if (key in keys || key.startsWith(LANGUAGE_NAME)) return false
        return WORD.findAll(text.replace(PLACEHOLDER, " ")).any { it.value !in terms }
    }

    private companion object {
        const val MAX_SAME = 9
        const val OF = 21
        const val LANGUAGE_NAME = "language_name_"
        const val PLACEHOLDER = "%s"
        val WORD = Regex("""(?U)[^\W\d_]{3,}""")
    }
}
