// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.predict.CorrectionOffer

/**
 * What a delimiter writes in place of the word just typed, and why. The first [CommitRule] to
 * claim the word wins, in order: a field that takes the keys verbatim, or a digit ending the
 * word, either of which leaves it as typed; the user's text shortcut; the bundled map, restoring
 * an apostrophe with corrections on in running text, or a capital with auto-capitalise on; a
 * name's possessive, with corrections on in running text; and autocorrect takes the rest. Reads
 * neither the editor nor the engine.
 */
internal object WordCommit {

    /**
     * What claimed the word; [NONE] commits the letters typed. [reason] is the label a payload
     * row asserts.
     */
    enum class Kind(val reason: String) {
        NONE("None"),
        SHORTCUT("Shortcut"),
        CONTRACTION("Contraction"),
        POSSESSIVE("Possessive"),
        CAPITAL("Capital"),
        CORRECTION("Correction"),
    }

    /**
     * The decision. [text] is what the delimiter writes in place of the typed word, or null to
     * commit the letters as typed. [situation] is autocorrect's reason, null when a rewrite
     * claimed the word. [reason] is the word a payload row asserts: the rewrite's kind, or
     * autocorrect's situation.
     */
    class Outcome(
        val text: String?,
        val kind: Kind,
        val situation: AutoCorrection.Situation?,
        val reason: String,
    ) {
        /** Whether a rewrite, not autocorrect, claimed the word. */
        val isRewrite: Boolean
            get() = kind != Kind.NONE && kind != Kind.CORRECTION
    }

    /** The key Enter is to the decision: the word it ends follows the Enter switch, not space's. */
    const val ENTER = '\n'.code

    /** The settings the decision reads. */
    class Settings(
        val autoCorrectOnSpace: Boolean,
        val autoCorrectOnEnter: Boolean,
        val autoCapitalise: Boolean,
        val minimumLength: Int,
        val correctionDistance: Int,
        val capitaliseNames: Boolean,
    )

    /** Autocorrect never ran: the switch is off. */
    const val REASON_OFF = "Off"

    /** Autocorrect never ran: the word is part of an address, a path or a number. */
    const val REASON_NOT_PROSE = "NotProse"

    /** Nothing ran: the field takes the keys verbatim. */
    const val REASON_VERBATIM = "Verbatim"

    /**
     * Decides for [typed], ended by the key [endedBy]: a space for what the strip outlines. In a
     * [verbatim] field every word stays as typed. No rewrite claims a word from a gesture
     * ([fromGesture]). [runningText] is whether the word is part of a sentence, settled as it
     * began ([RunningText]). [possessive] is the engine's possessive for [suggestionQuery], used
     * only when that is [typed] and [typed] is not empty; [corrections] is autocorrect's list for
     * the same query, [knownWord], [knownWordExact] and [knownWordIsName] what the dictionaries
     * say of it (see [AutoCorrection.pick]).
     */
    fun decide(
        typed: String,
        endedBy: Int,
        verbatim: Boolean,
        fromGesture: Boolean,
        runningText: Boolean,
        shortcuts: List<TextShortcut>,
        contractions: Map<String, String>,
        possessive: String?,
        corrections: List<CorrectionOffer>,
        suggestionQuery: String,
        knownWord: String,
        knownWordExact: Boolean,
        knownWordIsName: Boolean,
        settings: Settings,
    ): Outcome {
        val word = Word(
            typed, endedBy, verbatim, fromGesture, runningText, shortcuts, contractions,
            possessive, corrections, suggestionQuery, knownWord, knownWordExact, knownWordIsName,
            settings,
        )
        for (rule in RULES) {
            rule.claim(word)?.let { return it }
        }
        return autocorrect(word)
    }

    /** What a [CommitRule] reads: [decide]'s arguments, and whether the word is prose. */
    class Word(
        val typed: String,
        val endedBy: Int,
        val verbatim: Boolean,
        val fromGesture: Boolean,
        val runningText: Boolean,
        val shortcuts: List<TextShortcut>,
        val contractions: Map<String, String>,
        val possessive: String?,
        val corrections: List<CorrectionOffer>,
        val suggestionQuery: String,
        val knownWord: String,
        val knownWordExact: Boolean,
        val knownWordIsName: Boolean,
        val settings: Settings,
    ) {
        val isEnter: Boolean = endedBy == ENTER
        val autoCorrectAllowed: Boolean = if (isEnter) settings.autoCorrectOnEnter else settings.autoCorrectOnSpace

        /** Part of a sentence, as it began, and holding no digit. */
        val prose: Boolean = runningText && RunningText.admits(typed) { null }
    }

    /** One claim on a word a delimiter ends: the outcome, or null to leave the word to the next. */
    fun interface CommitRule {
        fun claim(word: Word): Outcome?
    }

    /** A field that takes the keys verbatim, as a password field does. */
    private val verbatimField = CommitRule { word ->
        if (word.verbatim) Outcome(null, Kind.NONE, null, REASON_VERBATIM) else null
    }

    /** A word a digit ends is part of a number or a code. */
    private val digitEnds = CommitRule { word ->
        if (Character.isDigit(word.endedBy)) {
            Outcome(null, Kind.NONE, null, REASON_NOT_PROSE)
        } else {
            null
        }
    }

    /** The user's own shortcut outranks every bundled rewrite. */
    private val shortcut = CommitRule { word ->
        if (word.fromGesture) {
            return@CommitRule null
        }
        TextShortcuts.expansionFor(word.typed, word.shortcuts)?.let { expansion ->
            Outcome(expansion, Kind.SHORTCUT, null, Kind.SHORTCUT.reason)
        }
    }

    /**
     * The bundled map: an entry that changes the letters is gated like autocorrect; one that
     * changes only the case, by auto-capitalise.
     */
    private val contraction = CommitRule { word ->
        if (word.fromGesture) {
            return@CommitRule null
        }
        val written = Contractions.expansionFor(word.typed, word.contractions)
        when {
            written == null || written == word.typed -> null
            written.equals(word.typed, ignoreCase = true) -> if (word.settings.autoCapitalise) {
                Outcome(written, Kind.CAPITAL, null, Kind.CAPITAL.reason)
            } else {
                null
            }
            word.autoCorrectAllowed && word.prose ->
                Outcome(written, Kind.CONTRACTION, null, Kind.CONTRACTION.reason)
            else -> null
        }
    }

    /** A name's possessive (Engine::possessiveFor), gated like the map, cased as a name. */
    private val possessive = CommitRule { word ->
        val answer = word.possessive
        if (word.fromGesture || !word.autoCorrectAllowed || !word.prose ||
            answer == null || word.typed.isEmpty() || word.suggestionQuery != word.typed
        ) {
            return@CommitRule null
        }
        val cased = AutoCorrection.matchCase(word.typed, answer, word.settings.capitaliseNames)
        Outcome(cased, Kind.POSSESSIVE, null, Kind.POSSESSIVE.reason)
    }

    private val correctionsOff = CommitRule { word ->
        if (!word.autoCorrectAllowed) Outcome(null, Kind.NONE, null, REASON_OFF) else null
    }

    private val notProse = CommitRule { word ->
        if (!word.prose) Outcome(null, Kind.NONE, null, REASON_NOT_PROSE) else null
    }

    /** The rules in the order they claim a word; autocorrect takes what none of them does. */
    private val RULES: List<CommitRule> =
        listOf(verbatimField, digitEnds, shortcut, contraction, possessive, correctionsOff, notProse)

    /** Autocorrect's decision, with its situation as the reason. */
    private fun autocorrect(word: Word): Outcome {
        val settings = word.settings
        val pick = AutoCorrection.pick(
            word.typed, word.corrections, word.suggestionQuery, word.knownWord,
            word.knownWordExact, word.knownWordIsName, settings.minimumLength,
            AutoCorrection.maxEditsFor(word.typed.length, settings.correctionDistance),
            settings.capitaliseNames,
            AutoCorrection.maxSlipEditsFor(word.typed.length, settings.correctionDistance),
            AutoCorrection.decoderAllowedFor(settings.correctionDistance),
        )
        return if (pick.situation.replaces) {
            Outcome(pick.text, Kind.CORRECTION, pick.situation, pick.situation.name)
        } else {
            Outcome(null, Kind.NONE, pick.situation, pick.situation.name)
        }
    }
}
