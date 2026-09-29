// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.ime.WordCommit
import com.borderkeys.typing.TypingOrchestrator.Companion.FIELD_HISTORY_CHARS

/**
 * The end of a word: what a delimiter writes in place of it ([WordCommit]), the correction left
 * pending until the next key, and the corrections a change of language leaves wrong.
 */
class CommitFlow(
    /** The field being typed into, or null when none is bound. */
    private val currentEditor: () -> FieldEditor?,
    private val engine: EnginePort,
) : TypingFlow() {

    /** Apostrophe spellings for the languages switched on; see [com.borderkeys.ime.Contractions]. */
    var contractions: Map<String, String> = emptyMap()

    private var pendingCorrection: PendingCorrection? = null

    /** Finds the words that look wrong once the conversation's language has changed. */
    private val languageSwitchCorrector = LanguageSwitchCorrector()

    /** What [revert] did with the pending correction. */
    class Revert(
        val pending: PendingCorrection,
        /** The correction and its delimiter were replaced by what was typed. */
        val reverted: Boolean,
        /** The correction stands and is to be learned. */
        val learnCorrected: Boolean,
    )

    /** Each field starts with no corrections for a change of language to take back. */
    override fun onFieldStarted(field: FieldSession) {
        languageSwitchCorrector.reset()
    }

    // ---- the decision --------------------------------------------------------------------------

    /**
     * What the key [endedBy] would write in place of [typed]; see [WordCommit]. [fromGesture] and
     * [runningText] are the word's, [answer] the engine's last answer. Reads no editor.
     */
    internal fun decide(
        typed: String,
        endedBy: Int,
        fromGesture: Boolean,
        runningText: Boolean,
        answer: SearchAnswer,
    ): WordCommit.Outcome = WordCommit.decide(
        typed = typed,
        endedBy = endedBy,
        verbatim = session.policy.verbatim,
        fromGesture = fromGesture,
        runningText = runningText,
        shortcuts = settings.textShortcuts,
        contractions = contractions,
        possessive = answer.possessive,
        suggestion = answer.correction,
        suggestionQuery = answer.query,
        knownWord = answer.knownWord,
        isProperNoun = answer.correctionIsName,
        inflection = answer.inflection,
        settings = WordCommit.Settings(
            autoCorrectOnSpace = settings.autoCorrectOnSpace,
            autoCapitalise = settings.autoCapitalise,
            minimumLength = settings.minCorrectionLength,
            correctionDistance = settings.correctionDistance,
            capitaliseNames = settings.capitaliseNames,
        ),
    )

    /** The word the strip outlines: what a space would write, unless it is a text shortcut. */
    fun outline(
        typed: String,
        fromGesture: Boolean,
        runningText: Boolean,
        answer: SearchAnswer,
    ): String? {
        val outcome = decide(typed, ' '.code, fromGesture, runningText, answer)
        return if (outcome.kind == WordCommit.Kind.SHORTCUT) null else outcome.text
    }

    // ---- the pending correction ----------------------------------------------------------------

    /** [correction] was just written and is pending until the next key. */
    fun setPending(correction: PendingCorrection) {
        pendingCorrection = correction
    }

    /** The pending correction, which is no longer pending. */
    fun takePending(): PendingCorrection? {
        val pending = pendingCorrection
        pendingCorrection = null
        return pending
    }

    /** Drops the pending correction, after an edit that rewrote the field. */
    fun dropPending() {
        pendingCorrection = null
    }

    /** The word the pending correction replaced, or null with none pending. */
    val pendingTypedWord: String? get() = pendingCorrection?.typed

    /** [pendingTypedWord], while the correction and its delimiter are the text before the caret. */
    fun revertableWord(): String? = pendingCorrection?.takeIf { correctionBeforeCaret(it) }?.typed

    private fun correctionBeforeCaret(pending: PendingCorrection): Boolean {
        val committed = pending.corrected + pending.delimiter
        val before = currentEditor()?.textBeforeCursor(committed.length) ?: return false
        return before.toString() == committed
    }

    /**
     * Takes the pending correction, and replaces it and its delimiter with what was typed, in one
     * batch edit. It stands instead when [viaBackspace] and
     * [KeyboardPreferences.revertCorrectionOnBackspace] is off, or when the text before the caret
     * changed. Returns null when nothing was pending.
     */
    fun revert(editor: FieldEditor, viaBackspace: Boolean): Revert? {
        val pending = takePending() ?: return null
        if (viaBackspace && !settings.revertCorrectionOnBackspace) {
            // An ordinary backspace confirms the correction.
            return Revert(pending, reverted = false, learnCorrected = pending.learn)
        }
        val committed = pending.corrected + pending.delimiter
        val before = editor.textBeforeCursor(committed.length)
        if (before == null || before.toString() != committed) {
            // The text before the caret changed: the correction stands.
            return Revert(pending, reverted = false, learnCorrected = pending.learn)
        }
        editor.beginBatchEdit()
        editor.deleteSurroundingText(committed.length, 0)
        editor.commitText(pending.typed + pending.delimiter, 1)
        editor.endBatchEdit()
        return Revert(pending, reverted = true, learnCorrected = false)
    }

    // ---- the language switch -------------------------------------------------------------------

    /**
     * Records where [correction] landed, read from the cursor, for [checkLanguageSwitch]; nothing
     * with [KeyboardPreferences.languageSwitchCorrectionMode] off.
     */
    fun recordLanguageSwitchFlag(
        editor: FieldEditor,
        typed: String,
        correction: String,
        delimiter: String,
    ) {
        if (settings.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
            return
        }
        val cursor = editor.extractedText(FIELD_HISTORY_CHARS)?.selectionEnd ?: return
        val end = cursor - delimiter.length
        val start = end - correction.length
        if (start < 0) {
            return
        }
        languageSwitchCorrector.recordCorrection(
            LanguageSwitchCorrector.Flag(typed, correction, start, end),
        )
    }

    /**
     * Asks whether the conversation's language changed and which recent corrections that leaves
     * wrong, and hands those to [onReplacements]. An answer about an older field is dropped.
     * Nothing is asked with [KeyboardPreferences.languageSwitchCorrectionMode] off.
     */
    fun checkLanguageSwitch(onReplacements: (List<LanguageSwitchCorrector.Replacement>) -> Unit) {
        if (settings.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
            return
        }
        val generation = session.generation
        engine.dominantPack { dominantPack ->
            if (!isCurrent(generation) ||
                !languageSwitchCorrector.observeDominantPack(dominantPack)
            ) {
                return@dominantPack
            }
            val editor = currentEditor() ?: return@dominantPack
            val verified = languageSwitchCorrector.snapshot().filter { flag ->
                textAt(editor, flag.startOffset, flag.endOffset) == flag.appliedText
            }
            if (verified.isEmpty()) {
                return@dominantPack
            }
            engine.candidatesForPack(dominantPack, verified.map { it.typedText }) { suggestions ->
                if (!isCurrent(generation)) {
                    return@candidatesForPack
                }
                val replacements = languageSwitchCorrector.resolve(verified, suggestions)
                if (replacements.isNotEmpty()) {
                    onReplacements(replacements)
                }
            }
        }
    }

    /**
     * Applies [replacements] in their order, each only if its text is still in place, then puts
     * the caret back where the user is writing. Runs inside the caller's batch edit; returns
     * whether any was applied.
     */
    fun replace(
        editor: FieldEditor,
        replacements: List<LanguageSwitchCorrector.Replacement>,
    ): Boolean {
        val applied = ArrayList<LanguageSwitchCorrector.Replacement>(replacements.size)
        val caret = selectionOf(editor)
        for (replacement in replacements) {
            if (textAt(editor, replacement.startOffset, replacement.endOffset) !=
                replacement.previousText
            ) {
                continue
            }
            editor.setComposingRegion(replacement.startOffset, replacement.endOffset)
            editor.setComposingText(replacement.text, 1)
            editor.finishComposingText()
            applied += replacement
        }
        // The caret goes back, shifted by the length the text before it changed.
        if (applied.isNotEmpty() && caret != null) {
            val (start, end) = caret
            editor.setSelection(
                languageSwitchCorrector.caretAfter(start, applied),
                languageSwitchCorrector.caretAfter(end, applied),
            )
        }
        return applied.isNotEmpty()
    }

    /** The field's text between two offsets, or null if either is out of range. */
    private fun textAt(editor: FieldEditor, start: Int, endExclusive: Int): String? {
        if (start < 0 || endExclusive < start) {
            return null
        }
        val text = editor.extractedText(FIELD_HISTORY_CHARS)?.text ?: return null
        if (endExclusive > text.length) {
            return null
        }
        return text.subSequence(start, endExclusive).toString()
    }

    /** The selection in the offsets [textAt] uses, or null when the editor does not report it. */
    private fun selectionOf(editor: FieldEditor): Pair<Int, Int>? {
        val extracted = editor.extractedText(FIELD_HISTORY_CHARS) ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        return if (start < 0 || end < 0) null else Pair(start, end)
    }
}
