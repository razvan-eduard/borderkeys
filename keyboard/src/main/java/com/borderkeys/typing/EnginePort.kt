// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedWord

/**
 * The prediction engine as the typing flow uses it. Every call returns at once; an answer to
 * [requestSuggestions] reaches [TypingOrchestrator.onSuggestions] through whoever owns the engine,
 * and the callbacks run on the typing flow's thread.
 */
interface EnginePort {
    fun requestSuggestions(composing: String, previous1: String?, previous2: String?)

    fun learn(updates: List<LearnedWord>, previous1: String?, previous2: String?)

    fun setPersonalModelEnabled(enabled: Boolean)

    fun dominantLanguageTag(onResult: (String?) -> Unit)

    fun dominantPack(onResult: (Int) -> Unit)

    fun candidatesForPack(dominantPack: Int, words: List<String>, onResult: (List<String?>) -> Unit)

    fun cancelPending()
}
