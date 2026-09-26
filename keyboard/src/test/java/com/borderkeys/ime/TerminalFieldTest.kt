// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFieldTest {

    private fun field(inputType: Int, packageName: String = "com.example.editor") =
        EditorInfo().also {
            it.inputType = inputType
            it.packageName = packageName
        }

    @Test
    fun `a field of no class is a terminal, whatever flags ride with it`() {
        assertTrue(TerminalField.isBareField(InputType.TYPE_NULL))
        assertTrue(TerminalField.isBareField(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertTrue(TerminalField.isTerminal(field(InputType.TYPE_NULL)))
    }

    @Test
    fun `every class of text field is not`() {
        for (inputClass in listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )) {
            assertFalse(TerminalField.isBareField(inputClass))
            assertFalse(TerminalField.isTerminal(field(inputClass)))
        }
        assertFalse(
            TerminalField.isTerminal(
                field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            ),
        )
    }

    @Test
    fun `a known terminal is one even when it declares a text class`() {
        val textField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        assertTrue(TerminalField.isTerminal(field(textField, "com.termux")))
        assertTrue(TerminalField.isTerminal(field(textField, "org.connectbot")))
        assertFalse(TerminalField.isTerminal(field(textField, "com.termux.api")))
    }

    @Test
    fun `nothing known about the field is not a terminal`() {
        assertFalse(TerminalField.isTerminal(null))
    }
}
