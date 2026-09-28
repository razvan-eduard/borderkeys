// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * The rule the catalogues are held to: every language carries exactly the key set English does,
 * except Romanian's `_many` forms, which exist only in Romanian and only beside their base key.
 * Takes plain maps.
 */
object TranslationParity {

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
            val sanctioned = { key: String ->
                language == LanguageManager.LARGE_NUMBER_LANGUAGE &&
                    key.endsWith(MANY_SUFFIX) &&
                    key.removeSuffix(MANY_SUFFIX) in keys
            }
            (keys - referenceKeys).filterNot(sanctioned).forEach {
                out += "$language has '$it' which $reference does not"
            }
            (referenceKeys - keys).forEach { out += "$language is missing '$it'" }
        }
        return out
    }

    private const val MANY_SUFFIX = "_many"
}
