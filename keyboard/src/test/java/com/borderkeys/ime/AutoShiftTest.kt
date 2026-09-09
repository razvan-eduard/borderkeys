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
}
