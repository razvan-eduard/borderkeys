// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyboardStats
import com.borderkeys.ime.SuggestionRow
import com.borderkeys.predict.Candidate

/**
 * What the strip asks the engine and what it is told: the word last asked about, the engine's
 * last answer, which a delimiter applies, and the row the strip shows.
 */
class SuggestionFlow(
    private val engine: EnginePort,
    private val host: TypingHost,
    private val clock: TypingClock,
) : TypingFlow() {

    /** The word the engine was last asked about. */
    var lastQuery: String = ""
        private set

    /** The engine's last answer, kept so the delimiter path can apply it. */
    var answer = SearchAnswer.NONE
        private set

    /** Uptime of the keystroke the engine was last asked about, for the strip latency figure. */
    private var requestedAt = 0L

    /** Where the typed word and the word a delimiter would apply end up on the strip. */
    private val row = SuggestionRow()

    /** The orchestrator clears the answer and asks afresh as each field starts. */
    override fun onFieldStarted(field: FieldSession) = Unit

    // ---- asking --------------------------------------------------------------------------------

    /**
     * Asks the engine about [query] after [context]. A private field's text is re-read into the
     * strip first, and nothing is asked where the field allows no suggestions.
     */
    fun request(query: String, context: WordContext) {
        lastQuery = query
        if (session.policy.privateField) {
            host.refreshPrivateReveal()
        }
        if (!session.policy.suggestionsAllowed) {
            return
        }
        requestedAt = clock.uptimeMillis()
        engine.requestSuggestions(query, context.previous1, context.previous2)
    }

    /** Asks about a terminal's [word], with no words before it. */
    fun requestForTerminal(word: String) {
        lastQuery = word
        if (!session.policy.suggestionsAllowed) {
            return
        }
        requestedAt = clock.uptimeMillis()
        engine.requestSuggestions(word, null, null)
    }

    /**
     * Asks about [word], the word the caret sits in, after [context]. The request is not timed,
     * and a private field's text is not re-read.
     */
    fun requestAdopted(word: String, context: WordContext) {
        lastQuery = word
        if (session.policy.suggestionsAllowed) {
            engine.requestSuggestions(word, context.previous1, context.previous2)
        }
    }

    // ---- the answer ----------------------------------------------------------------------------

    /**
     * Whether an answer about [query] is about the word last asked about; when it is, the time it
     * took is recorded.
     */
    fun accept(query: String): Boolean {
        if (query != lastQuery) {
            return false
        }
        if (requestedAt != 0L) {
            KeyboardStats.suggestionMillis.add((clock.uptimeMillis() - requestedAt).toDouble())
            requestedAt = 0L
        }
        return true
    }

    /** Keeps the engine's answer about [query]; its correction is the candidate it marked. */
    fun keep(
        candidates: List<Candidate>,
        knownWord: String,
        query: String,
        possessive: String?,
        inflection: Boolean,
    ) {
        // The word the corrections heap settled on, in the engine's own case.
        val marked = candidates.firstOrNull { it.isCorrection }
        answer = SearchAnswer(
            query = query,
            knownWord = knownWord,
            correction = marked?.text,
            correctionIsName = marked?.isProperNoun == true,
            possessive = possessive,
            inflection = inflection,
        )
    }

    /** The swiped [word] is the answer a delimiter applies; [isName] when it is a name. */
    fun answerSwiped(word: String, isName: Boolean) {
        lastQuery = word
        answer = answer.copy(
            query = word,
            knownWord = word,
            correction = word,
            correctionIsName = isName,
        )
    }

    /** Drops the answer about the word; the possessive stays. */
    fun clearAnswer() {
        answer = answer.copy(
            query = "",
            knownWord = "",
            correction = null,
            correctionIsName = false,
            inflection = false,
        )
    }

    // ---- the row -------------------------------------------------------------------------------

    /** How many words the strip shows, or null when it shows none: switched off, or no strip. */
    fun wordSlots(): Int? {
        if (!settings.showSuggestionStrip) {
            return null
        }
        val slots = host.stripWordSlots() ?: return null
        return settings.suggestionCount.coerceAtMost(slots)
    }

    /**
     * Shows [cased] in [slots] words, [correction] outlined as what a delimiter would commit, and
     * [revertable] as the typed chip while a correction can be taken back.
     */
    fun show(cased: List<Candidate>, slots: Int, correction: String?, revertable: String?) {
        val shown = row.arrange(
            cased, lastQuery, slots, correction = correction, revertable = revertable,
        )
        host.showSuggestions(shown, row.typedIndex, row.appliedIndex)
    }
}
