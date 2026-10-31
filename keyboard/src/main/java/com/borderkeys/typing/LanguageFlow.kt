// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * The language being written as the keyboard names it to the engine: each field starts with the
 * settings' language lock and preferred language, and every request reads, and every commit is
 * learned under, [language].
 */
class LanguageFlow(
    private val engine: EnginePort,
    private val host: TypingHost,
) : TypingFlow() {

    /** The tag of the pack the last answer in this field and subtype leaned on, or null before one. */
    private var answerPack: String? = null

    /** The answer's pack's language, else the subtype's ([CommitLanguage]). */
    val language: String
        get() = CommitLanguage.decide(answerPack, host.subtypeTag())

    /** An answer leaned on the pack tagged [pack]; one that names none leaves the language. */
    fun answered(pack: String?) {
        if (!pack.isNullOrEmpty()) {
            answerPack = pack
        }
    }

    /** A field starts under the subtype's language, with the settings' lock and preference. */
    override fun onFieldStarted(field: FieldSession) {
        answerPack = null
        engine.setLanguageLock(
            KeyboardPreferences.languageLockEvidence(settings.languageLock),
            KeyboardPreferences.languageLockStrict(settings.languageLock),
        )
        engine.setPreferredLanguage(settings.preferredLanguageTag)
    }

    /** A new subtype's language holds until an answer names a pack. */
    override fun onSubtypeChanged() {
        answerPack = null
    }
}
