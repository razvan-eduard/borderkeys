// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each language addresses the reader in one register, as `register.json` declares it: a form of
 * the other register is a failure, unless the word it matched is one the file allows.
 */
class RegisterTest {

    private val rules = Catalogues.resource("register.json")

    @Test
    fun `the registers are the ones chosen`() {
        val declared = rules.mapValues { (it.value as JsonObject).getValue("register").jsonPrimitive.content }
        assertEquals(PINNED, declared)
    }

    @Test
    fun `every language has a register`() {
        assertEquals(Catalogues.translated.toSet(), rules.keys)
    }

    @Test
    fun `no string slips into the other register`() {
        val problems = mutableListOf<String>()
        for ((language, rule) in rules) {
            rule as JsonObject
            val forbidden = Catalogues.strings(rule["forbidden"]).map(Catalogues::regex)
            val allowed = Catalogues.strings(rule["allowedWords"]).map { Regex("(?U)(?i)$it") }
            val except = Catalogues.strings(rule["exceptKeys"]).map(Catalogues::regex)
            for ((key, text) in Catalogues.of(language)) {
                if (except.any { it.matches(key) }) continue
                for (pattern in forbidden) {
                    pattern.findAll(text)
                        .filterNot { hit -> allowed.any { it.matches(hit.value) } }
                        .forEach { problems += "$language:$key '${it.value}' in: ${text.take(90)}" }
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    private companion object {
        val PINNED = mapOf(
            "cs" to "formal", "de" to "informal", "es" to "informal", "fa" to "formal", "fil" to "informal",
            "fr" to "formal", "hu" to "formal", "id" to "formal", "it" to "informal", "ja" to "formal",
            "ko" to "formal", "lv" to "formal", "nl" to "informal", "pl" to "informal", "pt" to "formal",
            "ro" to "informal", "ru" to "formal", "tr" to "formal", "uk" to "formal", "vi" to "neutral",
            "zh-cn" to "informal",
        )
    }
}
