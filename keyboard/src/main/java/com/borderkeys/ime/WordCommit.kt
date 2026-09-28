// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut

/**
 * What a delimiter writes in place of the word just typed, and why. The first to claim the word
 * wins, in order: the user's text shortcut; the bundled map, restoring an apostrophe with
 * corrections on in running text, or a capital with auto-capitalise on; a name's possessive, with
 * corrections on in running text; autocorrect. Reads neither the editor nor the engine.
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

    /** The settings the decision reads. */
    class Settings(
        val autoCorrectOnSpace: Boolean,
        val autoCapitalise: Boolean,
        val minimumLength: Int,
        val correctionDistance: Int,
        val capitaliseNames: Boolean,
    )

    /** Autocorrect never ran: the switch is off. */
    const val REASON_OFF = "Off"

    /** Autocorrect never ran: the word is part of an address, a path or a number. */
    const val REASON_NOT_PROSE = "NotProse"

    /**
     * Decides for [typed]. No rewrite claims a word from a gesture ([fromGesture]). [runningText]
     * is whether the word is part of a sentence, settled as it began ([RunningText]).
     * [possessive] is the engine's possessive for [suggestionQuery], used only when that is
     * [typed]; [inflection] is [WordStems.shields]'s answer for the same query and suggestion.
     */
    fun decide(
        typed: String,
        fromGesture: Boolean,
        runningText: Boolean,
        shortcuts: List<TextShortcut>,
        contractions: Map<String, String>,
        possessive: String?,
        suggestion: String?,
        suggestionQuery: String,
        knownWord: String,
        isProperNoun: Boolean,
        inflection: Boolean,
        settings: Settings,
    ): Outcome {
        val prose = runningText && RunningText.admits(typed) { null }
        if (!fromGesture) {
            // The user's own shortcut outranks every bundled rewrite.
            TextShortcuts.expansionFor(typed, shortcuts)?.let { expansion ->
                return Outcome(expansion, Kind.SHORTCUT, null, Kind.SHORTCUT.reason)
            }
            // An entry that changes the letters is gated like autocorrect; one that changes only
            // the case, by auto-capitalise.
            Contractions.expansionFor(typed, contractions)?.let { written ->
                if (written == typed) {
                    return@let
                }
                if (written.equals(typed, ignoreCase = true)) {
                    if (settings.autoCapitalise) {
                        return Outcome(written, Kind.CAPITAL, null, Kind.CAPITAL.reason)
                    }
                } else if (settings.autoCorrectOnSpace && prose) {
                    return Outcome(written, Kind.CONTRACTION, null, Kind.CONTRACTION.reason)
                }
            }
            // A name's possessive (Engine::possessiveFor), gated like the map, cased as a name.
            if (settings.autoCorrectOnSpace && prose && possessive != null &&
                suggestionQuery == typed
            ) {
                val cased = AutoCorrection.matchCase(typed, possessive, settings.capitaliseNames)
                return Outcome(cased, Kind.POSSESSIVE, null, Kind.POSSESSIVE.reason)
            }
        }
        if (!settings.autoCorrectOnSpace) {
            return Outcome(null, Kind.NONE, null, REASON_OFF)
        }
        if (!prose) {
            return Outcome(null, Kind.NONE, null, REASON_NOT_PROSE)
        }
        val cased = AutoCorrection.matchCase(
            typed, suggestion.orEmpty(), isProperNoun && settings.capitaliseNames,
        )
        val maxEdits = AutoCorrection.maxEditsFor(typed.length, settings.correctionDistance)
        val situation = AutoCorrection.situationOf(
            typed, suggestion, suggestionQuery, knownWord, cased, settings.minimumLength,
            isProperNoun, maxEdits, settings.capitaliseNames, inflection,
        )
        return if (situation == AutoCorrection.Situation.Correctable) {
            Outcome(cased, Kind.CORRECTION, situation, situation.name)
        } else {
            Outcome(null, Kind.NONE, situation, situation.name)
        }
    }
}
