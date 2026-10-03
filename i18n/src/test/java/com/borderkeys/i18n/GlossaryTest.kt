// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One term per concept: every string whose English carries a concept's term carries one of the
 * allowed stems in each language and none of the forbidden ones, as `glossary.json` sets them.
 * A stem matches at the start of a word, or anywhere when it begins with `*`.
 */
class GlossaryTest {

    private val concepts = Catalogues.resource("glossary.json").getValue("concepts").jsonArray.map { it.jsonObject }

    @Test
    fun `every concept is held to in every language`() {
        val problems = mutableListOf<String>()
        val english = Catalogues.of(Strings.Languages.DEFAULT)
        for (concept in concepts) {
            val name = concept.getValue("name").jsonPrimitive.content
            val term = concept.getValue("english").jsonPrimitive.content
            val except = Catalogues.strings(concept["exceptKeys"]).map(Catalogues::regex)
            val keys = english.filter { (key, text) ->
                contains(Catalogues.folded(text), term) && except.none { it.matches(key) }
            }.keys
            assertTrue("$name matches no English string", keys.isNotEmpty())
            val locales = concept.getValue("locales").jsonObject
            for ((language, rule) in locales) {
                rule as JsonObject
                val allowed = Catalogues.strings(rule["allowed"])
                val forbidden = Catalogues.strings(rule["forbidden"])
                for (key in keys) {
                    if (!Catalogues.carries(language, key)) continue
                    val text = Catalogues.folded(Catalogues.of(language).getValue(key))
                    if (allowed.isNotEmpty() && allowed.none { contains(text, it) }) {
                        problems += "$language:$key has no ${allowed.joinToString("/")} for '$name'"
                    }
                    forbidden.filter { contains(text, it) }.forEach {
                        problems += "$language:$key uses '$it' for '$name'"
                    }
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `every concept covers every translated language`() {
        for (concept in concepts) {
            val locales = concept.getValue("locales").jsonObject.keys
            assertEquals(concept.getValue("name").jsonPrimitive.content, Catalogues.translated.toSet(), locales)
        }
    }

    @Test
    fun `a stem matches at the start of a word unless it is flagged`() {
        assertTrue(contains("die tastatur", "tastatur"))
        assertTrue(!contains("bildschirmtastatur", "tastatur"))
        assertTrue(contains("bildschirmtastatur", "*tastatur"))
    }

    private fun contains(text: String, stem: String): Boolean =
        if (stem.startsWith("*")) {
            stem.substring(1) in text
        } else {
            Catalogues.regex("(?<!\\w)" + Regex.escape(stem)).containsMatchIn(text)
        }
}
