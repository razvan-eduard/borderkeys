// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * The rule the catalogues are held to: every language carries exactly the key set English does.
 *
 * The one sanctioned exception is Romanian's `_many` forms, which [LanguageManager.counted] looks
 * up only for languages that distinguish large numbers -- so they exist only in Romanian, and
 * only alongside the base key they are a form of.
 *
 * Lives in main rather than test sources, and takes plain maps rather than files, so the same
 * rule can be checked by a unit test over the shipped assets and by anything else that needs it.
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
