// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision behind auto-capitalisation, kept separate from the platform calls it needs.
 *
 * Two bugs shipped from getting this wrong by hand: a field of a class that was not text could
 * have the text-capitalisation bit positions set for its own unrelated reasons and be read as
 * wanting capitals anyway, and the answer for "should shift already be on" was computed once at
 * `onStartInputView` before the target had been switched back from a draft box that was left
 * open, so it was answering about the wrong field. Neither is possible to catch from a device
 * screenshot the way it is possible to catch here.
 */
class AutoShiftTest {

    private val sentences = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val characters = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    private val plainText = InputType.TYPE_CLASS_TEXT

    @Test
    fun `off entirely when the setting is off, whatever the field asks for`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = false, inputType = sentences, composingIsEmpty = true,
            ) { error("must not be asked when the setting itself is off") },
        )
    }

    @Test
    fun `a field that is not text is never capitalised, even if the bits line up`() {
        // TYPE_CLASS_NUMBER's own flags share bit positions with the text class's CAP_SENTENCES
        // flag; a field of a different class must not be read through the text class's flags.
        val numberField = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        assertEquals(
            "a non-text field was read as wanting capitals",
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = numberField, composingIsEmpty = true,
            ) { error("must not be asked about a field that is not text") },
        )
    }

    @Test
    fun `a field that asks for every character locks shift, not just turns it on`() {
        assertEquals(
            2,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = characters, composingIsEmpty = true,
            ) { error("CAP_CHARACTERS decides on its own, before the platform is asked") },
        )
    }

    @Test
    fun `a field that asks for no capitalisation stays off without asking the platform`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = true,
            ) { error("nothing to ask about a field that requested no capitalisation") },
        )
    }

    @Test
    fun `the force override is off by default, so a field asking for nothing stays off`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = true,
                textBeforeCursor = { "" },
            ) { error("the platform is never asked about a field that requested no capitalisation") },
        )
    }

    @Test
    fun `the force override capitalises the very start of a field the field itself never asked for`() {
        assertEquals(
            1,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = true,
                forceCapitaliseSentences = true, textBeforeCursor = { "" },
            ) { error("the platform's capsMode is meaningless for a field that asked for nothing") },
        )
    }

    @Test
    fun `the force override also capitalises a later sentence start in the same field`() {
        assertEquals(
            1,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = true,
                forceCapitaliseSentences = true, textBeforeCursor = { "one. " },
            ) { error("judged from the text, not from the platform") },
        )
    }

    @Test
    fun `the force override does not capitalise mid-sentence`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = true,
                forceCapitaliseSentences = true, textBeforeCursor = { "one two" },
            ) { error("judged from the text, not from the platform") },
        )
    }

    @Test
    fun `the force override still respects a word still being typed`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = plainText, composingIsEmpty = false,
                forceCapitaliseSentences = true, textBeforeCursor = { "" },
            ) { error("must not be asked while something is still composing") },
        )
    }

    @Test
    fun `the force override still defers to the setting being off entirely`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = false, inputType = plainText, composingIsEmpty = true,
                forceCapitaliseSentences = true,
            ) { error("must not be asked when the setting itself is off") },
        )
    }

    @Test
    fun `the force override still leaves a non-text field alone`() {
        val numberField = InputType.TYPE_CLASS_NUMBER
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = numberField, composingIsEmpty = true,
                forceCapitaliseSentences = true,
            ) { error("must not be asked about a field that is not text") },
        )
    }

    @Test
    fun `the force override never touches a password field`() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = password, composingIsEmpty = true,
                forceCapitaliseSentences = true, textBeforeCursor = { "" },
            ) { error("a password field is left alone even with the force override on") },
        )
    }

    @Test
    fun `the force override never touches a visible or web password field either`() {
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val web = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        for (variation in listOf(visible, web)) {
            assertEquals(
                0,
                AutoShift.stateFor(
                    autoCapitaliseEnabled = true, inputType = variation, composingIsEmpty = true,
                    forceCapitaliseSentences = true, textBeforeCursor = { "" },
                ) { error("a password variation is left alone even with the force override on") },
            )
        }
    }

    @Test
    fun `a field that already asks for capitals is unaffected by the force override`() {
        // The force override only changes what happens when a field asks for NOTHING; a field
        // that already sets CAP_SENTENCES keeps using the platform's own capsMode, unchanged.
        assertEquals(
            1,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = sentences, composingIsEmpty = true,
                forceCapitaliseSentences = true,
            ) { android.text.TextUtils.CAP_MODE_SENTENCES },
        )
    }

    @Test
    fun `a word still being typed is never capitalised out from under the user`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = sentences, composingIsEmpty = false,
            ) { error("must not be asked while something is still composing") },
        )
    }

    @Test
    fun `defers to the platform's own answer for a field mid-sentence`() {
        assertEquals(
            0,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = sentences, composingIsEmpty = true,
            ) { 0 },
        )
    }

    @Test
    fun `defers to the platform's own answer at the start of a sentence`() {
        assertEquals(
            1,
            AutoShift.stateFor(
                autoCapitaliseEnabled = true, inputType = sentences, composingIsEmpty = true,
            ) { android.text.TextUtils.CAP_MODE_SENTENCES },
        )
    }

    private fun afterText(before: String): Int = AutoShift.stateFor(
        autoCapitaliseEnabled = true,
        inputType = sentences,
        composingIsEmpty = true,
        textBeforeCursor = { before },
        capsMode = { 0 },
    )

    @Test
    fun `a full stop with only a symbol emoticon after it is still a sentence end`() {
        assertEquals(1, afterText("asdasd. :) "))
        assertEquals(1, afterText("Done!! :-) "))
        assertEquals(1, afterText("what? :')"))
    }

    @Test
    fun `a full stop with only an emoji after it is still a sentence end`() {
        assertEquals(1, afterText("nice. 😀 ")) // grinning face
        assertEquals(1, afterText("ok. 👍🏽 ")) // thumbs up + skin tone
        assertEquals(1, afterText("bye. 👋 🙂 ")) // two emoji, space between
    }

    @Test
    fun `an emoji or emoticon with no full stop before it is not a sentence end`() {
        assertEquals(0, afterText("just chatting :) "))
        assertEquals(0, afterText("a word 😀 "))
    }

    @Test
    fun `a digit before the full stop keeps it a number, not a sentence end`() {
        assertEquals(0, afterText("it is 3.14 "))
        // The walk stops at the digit before ever reaching the full stop.
        assertEquals(0, afterText("v2.0 "))
    }

    @Test
    fun `a real word after the full stop is left to the platform, which said no`() {
        assertEquals(0, afterText("one. two "))
    }

    @Test
    fun `the cursor jammed against the full stop is not a new sentence yet`() {
        assertEquals(0, afterText("still typing."))
        assertEquals(0, afterText("wait.:)"))
    }

    @Test
    fun `a line just started is a sentence start, with nothing after the newline yet`() {
        // The platform's own capsMode is not asked to carry this one: Android's getCapsMode
        // only treats a blank line (a paragraph break) as equivalent to a full stop, not a
        // single Return -- which is exactly what sentenceEndsBeforeCursor's own '\n' branch
        // exists to cover, unconditionally and without waiting for a space to follow it the
        // way a written full stop has to.
        assertEquals(1, afterText("one\n"))
        assertEquals(1, afterText("one.\n"))
    }
}
