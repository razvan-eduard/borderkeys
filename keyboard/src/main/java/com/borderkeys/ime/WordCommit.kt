// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut

/**
 * What a delimiter writes in place of the word just typed, and why.
 *
 * Five things can claim the word, asked in this order: the user's own text shortcut, the
 * bundled apostrophe map, a name's productive possessive, a word the language always writes
 * with a capital, and autocorrect's own answer. Each has a gate of its own -- a shortcut always
 * applies, the apostrophe and the possessive only with corrections on and only in running text,
 * the capital only with auto-capitalise on -- and the first to claim the word wins.
 *
 * Pure: strings and settings in, a decision out. Nothing here reads the editor or the engine.
 * The service and the pipeline harness both decide through this object.
 */
internal object WordCommit {

    /**
     * Which of the five claimed the word. [NONE] is a delimiter committing the letters typed.
     * [reason] is the label a payload row asserts, in the same style as
     * [AutoCorrection.Situation]'s own names.
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
     * commit the letters as typed. [situation] is autocorrect's own reason when it was
     * autocorrect's turn and null when a rewrite claimed the word first. [reason] is the one
     * word a payload row asserts: the rewrite's kind, or autocorrect's situation.
     */
    class Outcome(
        val text: String?,
        val kind: Kind,
        val situation: AutoCorrection.Situation?,
        val reason: String,
    ) {
        /** A rewrite of the user's own -- or the language's -- that autocorrect had no say in.
         *  Never learned as a word, never revisited by a language switch. */
        val isRewrite: Boolean
            get() = kind != Kind.NONE && kind != Kind.CORRECTION
    }

    /** The settings the decision reads, passed as values so the object stays pure. */
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
     * Decides for [typed].
     *
     * [fromGesture] is a swiped word, which no rewrite may claim: "omw" has to be typed to mean
     * the shortcut, and a decoded word is a whole word of the dictionary already.
     * [runningText] is the answer settled as the word began -- whether what stands in front of
     * it makes it part of a sentence (see [RunningText]). [possessive] is the engine's productive
     * possessive for [suggestionQuery], and counts only when that query is [typed] itself, the
     * same staleness rule [AutoCorrection] applies to [suggestion]. [inflection] is
     * [WordStems.shields]'s answer for the same query and suggestion.
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
            // The map holds two kinds of entry, told apart by whether the letters change. An
            // apostrophe restored answers to autocorrect's two gates; a capital restored -- the
            // English pronoun -- answers to the auto-capitalise switch alone.
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
            // A name's productive possessive -- see Engine::possessiveFor. Same two gates as
            // the map. The stem is a name, spelled in lower case by the pack, so it takes a
            // name's capital.
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
