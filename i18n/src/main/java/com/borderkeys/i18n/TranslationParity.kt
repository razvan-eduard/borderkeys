// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * The rule the catalogues are held to: every language carries the key set English does, except
 * the forms of a counted key, which follow each language's [PluralRules]. A counted key is one
 * English carries with a `_one` form; every language carries its base key and one suffixed form
 * for every category it uses but "other", and no other suffixed form. Takes plain maps.
 */
object TranslationParity {

    /** The base keys English carries counted forms of. */
    fun countedKeys(english: Set<String>): Set<String> =
        english.filter { it.endsWith("_one") && it.removeSuffix("_one") in english }
            .map { it.removeSuffix("_one") }
            .toSet()

    /** The keys [language] must carry, given English's. */
    fun expectedKeys(english: Set<String>, language: String): Set<String> {
        val counted = countedKeys(english)
        val forms = PluralRules.categories(language).filter { it != PluralRules.OTHER }
        val plain = english.filterNot { key -> counted.any { base -> PluralRules.SUFFIXES.any { key == base + it } } }
        return plain.toSet() + counted.flatMap { base -> forms.map { "${base}_$it" } }
    }

    /** Returns every violation as a readable line; empty when the catalogues agree. */
    fun problems(
        keysByLanguage: Map<String, Set<String>>,
        reference: String = Strings.Languages.DEFAULT,
    ): List<String> {
        val referenceKeys = keysByLanguage[reference]
            ?: return listOf("reference language '$reference' missing from ${keysByLanguage.keys}")
        val out = mutableListOf<String>()
        for ((language, keys) in keysByLanguage) {
            if (language == reference) continue
            val expected = expectedKeys(referenceKeys, language)
            (keys - expected).forEach { out += "$language has '$it' which it should not" }
            (expected - keys).forEach { out += "$language is missing '$it'" }
        }
        return out
    }
}
