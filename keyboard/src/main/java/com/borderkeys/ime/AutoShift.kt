// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType

/**
 * What shift should be, from a field's request and what the platform says about the text before
 * the cursor.
 *
 * Its own object, for the same reason as [AutoCorrection]: the decision is worth being able to
 * test without an editor, an input connection or a real field. Returns the same three states
 * [BorderKeysService] itself uses -- 0 off, 1 on for one character, 2 locked -- so the caller
 * needs no translation, only its own named constants for readability at the call site.
 *
 * [capsMode] is a callback rather than a plain value because it is the one part of the answer
 * that genuinely needs a live connection
 * ([android.view.inputmethod.InputConnection.getCursorCapsMode]), and it is called only on the
 * one branch that needs it -- a field asking for no capitalisation, one with something already
 * composing, or one that is not text at all never reaches it.
 *
 * [forceCapitaliseSentences] answers the case [capsMode] cannot: a field that asked for no
 * capitalisation at all gets none from the platform either, by construction, so overriding that
 * decision is judged from [textBeforeCursor] alone rather than deferred to [capsMode].
 */
internal object AutoShift {
    fun stateFor(
        autoCapitaliseEnabled: Boolean,
        inputType: Int,
        composingIsEmpty: Boolean,
        forceCapitaliseSentences: Boolean = false,
        textBeforeCursor: () -> CharSequence? = { null },
        capsMode: () -> Int,
    ): Int {
        if (!autoCapitaliseEnabled) {
            return OFF
        }
        // Gated on the class before anything else: a field that is not text -- a number pad, a
        // phone field -- can have those same bit positions set for its own unrelated reasons,
        // and reading them without this check is how such a field could be misread as wanting
        // capitals. The force override changes what happens when a text field asks for nothing,
        // not what counts as a text field.
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return OFF
        }
        if ((inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
            return LOCKED
        }
        val words = (inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0
        val sentences = (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        if (!words && !sentences) {
            if (!forceCapitaliseSentences || isPasswordVariation(inputType)) {
                return OFF
            }
            // The field asked for nothing, so there is no reqModes bit for the platform's own
            // capsMode() to answer against -- it is parameterised by this same inputType, and
            // would report 0 at every position no matter what is actually at the cursor. "Is
            // this a sentence start" has to be answered from the text itself instead, the same
            // check used below for the one case the platform's own answer gets wrong, plus the
            // start of the field, which that check alone does not cover.
            if (!composingIsEmpty) {
                return OFF
            }
            val before = textBeforeCursor()
            return if (before.isNullOrEmpty() || sentenceEndsBeforeCursor(before)) ON else OFF
        }
        // The composing region is text the user is in the middle of; if there is any, they are
        // inside a word and nothing should be capitalised.
        if (!composingIsEmpty) {
            return OFF
        }
        // The platform's own answer is the primary one: it knows the start of a field and the
        // start of a line, it does not have a 64-character window to see past, and it correctly
        // holds off on "end." until a space follows the full stop. Only when it says no is the
        // text checked directly, for the one case it gets wrong -- it stops at the ")" of a ":)"
        // or the last code point of an emoji and calls that mid-sentence, when a full stop with
        // only emoji, emoticons and spaces after it is still the end of a sentence.
        if (capsMode() != 0) {
            return ON
        }
        return if (sentenceEndsBeforeCursor(textBeforeCursor())) ON else OFF
    }

    /**
     * Whether the text before the cursor ends in a sentence mark followed only by whitespace,
     * emoji, emoticons and other non-letters -- the case the platform's own check gets wrong.
     *
     * By exclusion rather than by a list of what an emoji is: anything that is not sentence
     * content -- a smiley, an emoji, a bracket, a dash -- is walked past, and only a letter or a
     * digit stops the walk. A digit in particular, so that "3.14 " is a number rather than a
     * sentence that ended at the "3". There has to be at least one space after the mark: "end."
     * with the cursor against the full stop is not a new sentence yet, the same rule the
     * platform applies.
     */
    private fun sentenceEndsBeforeCursor(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) {
            return false
        }
        var sawSpace = false
        var i = before.length
        while (i > 0) {
            val c = before[i - 1]
            when {
                c == '\n' -> return true
                c.isLetterOrDigit() -> return false
                c in SENTENCE_ENDINGS -> return sawSpace
                c.isWhitespace() -> sawSpace = true
            }
            i--
        }
        return false
    }

    /** The marks that close a sentence: ASCII, the single-character ellipsis, and the CJK set. */
    private const val SENTENCE_ENDINGS = ".!?…。！？"

    /** Whether [inputType] is one of the three password variations, checked only by the force
     *  override -- overriding what a field asks for is one thing, silently changing what gets
     *  typed into a password is another. */
    private fun isPasswordVariation(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private const val OFF = 0
    private const val ON = 1
    private const val LOCKED = 2
}
