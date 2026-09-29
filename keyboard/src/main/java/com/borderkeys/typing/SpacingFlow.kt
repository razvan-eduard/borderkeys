// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.ime.HabitSpace
import com.borderkeys.ime.PunctuationSpace

/**
 * The spaces the keyboard writes or takes back on its own: after a mark, before one, the full
 * stop two spaces make, and a space typed right after one it added.
 */
class SpacingFlow(private val clock: TypingClock) : TypingFlow() {

    /** When the last space was committed, for the two-spaces-make-a-full-stop window. */
    private var lastSpaceAt = 0L

    /** Set for one keystroke after two spaces became a full stop; backspace then undoes it. */
    private var pendingSpacePeriod = false

    /** Set when a space was added after a sentence mark; the next typed space is swallowed. */
    private var pendingAutoSpace = false

    /** The language the conversation is considered written in, or null. */
    var dominantLanguageTag: String? = null

    /** The tags of the packs the engine consults, heaviest first. */
    @Volatile
    var languageTags: List<String> = emptyList()

    /** The spacing state carries over from one field to the next. */
    override fun onFieldStarted(field: FieldSession) = Unit

    /**
     * Whether a space typed now, with [composingEmpty], is swallowed as the one this keyboard just
     * added, per [com.borderkeys.data.theme.KeyboardPreferences.autoSpaceHabit]; see [HabitSpace].
     */
    fun swallowsTypedSpace(composingEmpty: Boolean, editor: FieldEditor): Boolean {
        val swallows = HabitSpace.swallows(
            composingEmpty = composingEmpty,
            pendingAutoSpace = pendingAutoSpace,
            habit = settings.autoSpaceHabit,
            characterBeforeCursor = {
                editor.textBeforeCursor(1)?.takeIf { it.isNotEmpty() }?.get(0)
            },
        )
        if (swallows && !HabitSpace.staysArmed(settings.autoSpaceHabit)) {
            pendingAutoSpace = false
        }
        return swallows
    }

    /**
     * Whether a space typed now, with [composingEmpty], follows another within
     * [DOUBLE_SPACE_MILLIS] after a word character, and so makes a full stop.
     */
    fun doubleSpaceMakesPeriod(composingEmpty: Boolean, editor: FieldEditor): Boolean =
        composingEmpty && settings.doubleSpacePeriod && !session.addressField &&
            clock.currentTimeMillis() - lastSpaceAt < DOUBLE_SPACE_MILLIS &&
            endsWithWordCharacterBeforeSpace(editor)

    /** Writes ". " in place of the space before the caret. */
    fun writePeriod(editor: FieldEditor) {
        editor.beginBatchEdit()
        editor.deleteSurroundingText(1, 0)
        editor.commitText(". ", 1)
        editor.endBatchEdit()
        lastSpaceAt = 0L
        pendingSpacePeriod = true
        pendingAutoSpace = true
    }

    /** The delimiter [code] is about to be written. */
    fun delimiterTyped(code: Int) {
        if (code == ' '.code) {
            lastSpaceAt = clock.currentTimeMillis()
        }
        pendingSpacePeriod = false
    }

    /**
     * With [composingEmpty], removes the space before the caret ahead of a tight mark [code],
     * except the one French writes before ! ? ; :.
     */
    fun removeSpaceBeforeMark(code: Int, composingEmpty: Boolean, editor: FieldEditor) {
        if (!composingEmpty || !settings.removeSpaceBeforePunctuation ||
            !isTightPunctuation(code) || isFrenchSpacedPunctuation(code)
        ) {
            return
        }
        val before = editor.textBeforeCursor(1)
        if (before != null && before.length == 1 && before[0] == ' ') {
            editor.deleteSurroundingText(1, 0)
        }
    }

    /** The space that follows the mark [code], or nothing at all; see [PunctuationSpace]. */
    fun spaceAfterMark(code: Int, editor: FieldEditor): String {
        val follows = PunctuationSpace.follows(
            enabled = settings.spaceAfterPunctuation,
            addressField = session.addressField,
            insideNumbers = settings.spaceInsideNumbers,
            tightPunctuation = isTightPunctuation(code),
            before = { editor.textBeforeCursor(1)?.firstOrNull() },
            after = { editor.textAfterCursor(1)?.firstOrNull() },
        )
        val added = if (follows) " " else ""
        pendingAutoSpace = added.isNotEmpty()
        return added
    }

    /** Whether a space was just added after a pick: [added]. */
    fun spaceAddedAfterPick(added: Boolean) {
        pendingAutoSpace = added
    }

    /** The space added after a mark is no longer the text before the caret. */
    fun dropAutoSpace() {
        pendingAutoSpace = false
    }

    /**
     * Backspace right after two spaces became a full stop: turns ". " back into the two spaces.
     * Returns false when there was nothing to take back.
     */
    fun takeBackPeriod(editor: FieldEditor): Boolean {
        if (!pendingSpacePeriod) {
            return false
        }
        pendingSpacePeriod = false
        val before = editor.textBeforeCursor(2)
        if (before == null || before.toString() != ". ") {
            return false
        }
        editor.beginBatchEdit()
        editor.deleteSurroundingText(2, 0)
        editor.commitText("  ", 1)
        editor.endBatchEdit()
        return true
    }

    /** A line break was written: nothing before it counts towards the spacing rules. */
    fun lineStarted() {
        pendingAutoSpace = false
        pendingSpacePeriod = false
        lastSpaceAt = 0L
    }

    /** True when what precedes the single trailing space is a word character or a digit. */
    private fun endsWithWordCharacterBeforeSpace(editor: FieldEditor): Boolean {
        val before = editor.textBeforeCursor(2) ?: return false
        return before.length == 2 && before[1] == ' ' &&
            (TypingOrchestrator.isWordCharacter(before[0].code) || before[0].isDigit())
    }

    /** Marks that close up against the word before them. */
    private fun isTightPunctuation(code: Int): Boolean =
        code == '.'.code || code == ','.code || code == '!'.code || code == '?'.code ||
            code == ';'.code || code == ':'.code

    /** Whether [code] is one of ! ? ; : and the text is French. */
    private fun isFrenchSpacedPunctuation(code: Int): Boolean =
        (code == '!'.code || code == '?'.code || code == ';'.code || code == ':'.code) &&
            writingInFrench()

    /**
     * Whether the text being written is French: the dominant language, or, before there is one,
     * the only language enabled.
     */
    private fun writingInFrench(): Boolean {
        val dominant = dominantLanguageTag
        if (dominant != null) {
            return dominant.startsWith("fr", ignoreCase = true)
        }
        val tags = languageTags
        return tags.isNotEmpty() && tags.all { it.startsWith("fr", ignoreCase = true) }
    }

    companion object {
        /** The longest gap between two spaces that become a full stop. */
        const val DOUBLE_SPACE_MILLIS = 1200L
    }
}
