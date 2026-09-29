// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType
import android.view.KeyEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.ime.KeyCodes
import com.borderkeys.ime.ShiftState
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.Pipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Whole keystroke sequences through [TypingOrchestrator] on the shipped English pack: the word
 * path of each ImeSmokeTest case, and the paths that suite does not reach. The settings are the
 * smoke suite's.
 */
class TypingScenarioTest {

    private lateinit var pipeline: Pipeline
    private lateinit var rig: TypingRig

    @Before
    fun open() {
        Pipeline.require()
        pipeline = Pipeline.open("en-US")
        rig = pipeline.typingRig(SMOKE_SETTINGS)
        rig.startField()
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
    }

    // ---- the smoke suite's cases ----------------------------------------------------------------

    @Test
    fun `a mistyped word is corrected by space`() {
        rig.type("teh ")
        assertEquals("the ", rig.editor.text)
    }

    @Test
    fun `backspace puts the typed word back`() {
        rig.type("teh ")
        assertEquals("the ", rig.editor.text)
        rig.press(KeyCodes.DELETE)
        assertEquals("teh ", rig.editor.text)
        assertEquals(listOf("the" to false), rig.host.forgotten)
    }

    @Test
    fun `typing after a caret move keeps every word`() {
        rig.type("abc def")
        repeat(4) { rig.moveCaret(rig.editor.selectionEnd - 1) }
        rig.type("x")
        assertEquals("abcx def", rig.editor.text)
    }

    @Test
    fun `with shift locked a slide along the space bar selects`() {
        rig.type("abc")
        rig.longPress(KeyCodes.SHIFT)
        rig.slide(-3)
        rig.type("x")
        assertEquals("X", rig.editor.text)
    }

    @Test
    fun `backspace deletes a selection made leftwards`() {
        rig.type("abc def")
        rig.longPress(KeyCodes.SHIFT)
        rig.slide(-1)
        rig.press(KeyCodes.DELETE)
        assertEquals("abc de", rig.editor.text)
    }

    @Test
    fun `control on the modifier row selects all with a`() {
        rig.type("abc")
        rig.host.onPhysicalKey = { keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_A && rig.host.modifiersArmed) {
                rig.editor.setSelection(0, rig.editor.text.length)
            }
        }
        rig.host.modifiersArmed = true
        rig.type("a")
        assertEquals(listOf(KeyEvent.KEYCODE_A to 0), rig.host.physicalKeys)
        rig.type("x")
        assertEquals("x", rig.editor.text)
    }

    @Test
    fun `a swipe across the keys types the word`() {
        rig.orchestrator.composeSwipedWord(swiped("the", "then", "they"))
        rig.settle()
        rig.type(" ")
        assertEquals("the ", rig.editor.text)
    }

    @Test
    fun `the ring's pick replaces the swiped word and adds a space`() {
        rig.orchestrator.composeSwipedWord(swiped("the", "then", "they"))
        rig.settle()
        rig.pick(0, "then")
        assertEquals("then ", rig.editor.text)
    }

    @Test
    fun `a password field is never corrected`() {
        rig.startField(passwordField = true, privateField = true)
        rig.type("teh ")
        assertEquals("teh ", rig.editor.text)
    }

    @Test
    fun `a private field is read into the strip at each key, and nothing typed there is learned`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField(passwordField = true, privateField = true)
        val before = rig.host.privateRevealRefreshes
        rig.type("hunter")
        rig.press(KeyCodes.ENTER)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("hunter\n", rig.editor.text)
        assertTrue(rig.host.privateRevealRefreshes - before >= "hunter".length)
        assertTrue(rig.store.batches.isEmpty())
    }

    @Test
    fun `in a terminal each letter goes out as its key and enter runs the line`() {
        rig.startField(terminalField = true)
        rig.host.onPhysicalKey = { keyCode, metaState -> terminal(keyCode, metaState) }
        rig.type("echo")
        assertEquals("echo", rig.editor.text)
        assertNull(rig.editor.composingText)
        rig.type(" teh")
        rig.press(KeyCodes.ENTER)
        assertEquals("echo teh\n", rig.editor.text)
        assertEquals(KeyEvent.KEYCODE_ENTER, rig.host.physicalKeys.last().first)
    }

    @Test
    fun `in a terminal backspace, a pick and a swiped word all go out as keys`() {
        rig.startField(terminalField = true)
        rig.host.onPhysicalKey = { keyCode, metaState -> terminal(keyCode, metaState) }
        rig.type("lsx")
        rig.press(KeyCodes.DELETE)
        assertEquals("ls", rig.editor.text)
        assertEquals(KeyEvent.KEYCODE_DEL, rig.host.physicalKeys.last().first)
        rig.orchestrator.swipeIntoTerminal(swiped("the"))
        rig.settle()
        assertEquals("ls the", rig.editor.text)
        rig.type(" ech")
        rig.pick(0, "echo")
        assertEquals("ls the echo ", rig.editor.text)
        assertNull(rig.editor.composingText)
    }

    @Test
    fun `a slide up the space bar moves the caret a line`() {
        rig.type("ab")
        rig.press(KeyCodes.ENTER)
        rig.type("cd")
        rig.slideLines(-1)
        rig.type("x")
        assertEquals("abx\ncd", rig.editor.text)
    }

    @Test
    fun `a chip picked with the caret at zero keeps the next typed word`() {
        rig.type("teh")
        val typed = rig.editor.text
        rig.moveCaret(0)
        val picked = rig.host.strip.first().text
        rig.pick(0, picked)
        rig.type("ab ")
        assertEquals("$picked ab $typed", rig.editor.text)
    }

    @Test
    fun `letters typed on the Russian layout reach the field`() {
        rig.type("мир")
        assertEquals("мир", rig.editor.text)
    }

    @Test
    fun `with nothing typed the first suggestion is the, and picking it writes it`() {
        assertEquals("the", rig.host.strip.first().text)
        rig.pick(0, "the")
        assertEquals("the ", rig.editor.text)
    }

    @Test
    fun `a correction made in the wrong language is undone when the language flips`() {
        val both = Pipeline.open("ro-RO", "en-US")
        try {
            both.languageLock(BALANCED_EVIDENCE)
            val twoLanguages = both.typingRig(
                SMOKE_SETTINGS.copy(
                    languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY,
                ),
            )
            twoLanguages.startField()
            for (word in ROMANIAN_PHRASE.split(' ') + "in") {
                twoLanguages.type("$word ")
            }
            val corrected = twoLanguages.editor.text
            assertTrue("the Romanian verdict wrote în in '$corrected'", corrected.endsWith(" în "))
            for (word in ENGLISH_PHRASE.split(' ')) {
                twoLanguages.type("$word ")
            }
            val text = twoLanguages.editor.text
            assertTrue("the English word came back in '$text'", text.contains(" in ") && !text.contains("în"))
        } finally {
            both.close()
        }
    }

    // ---- what the smoke suite does not reach ----------------------------------------------------

    @Test
    fun `a sentence starts with a capital, which its first letter spends`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(autoCapitalise = true))
        rig.startField(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(ShiftState.ON, rig.host.shiftState)
        rig.type("hello")
        assertEquals(ShiftState.OFF, rig.host.shiftState)
        rig.type(".")
        assertEquals(ShiftState.ON, rig.host.shiftState)
        rig.type("world")
        assertEquals("Hello. World", rig.editor.text)
    }

    @Test
    fun `a field that asks for capitals locks shift, and a tap releases it until the next letter`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(autoCapitalise = true))
        rig.startField(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        assertEquals(ShiftState.LOCKED, rig.host.shiftState)
        rig.type("ab")
        rig.press(KeyCodes.SHIFT)
        assertEquals(ShiftState.OFF, rig.host.shiftState)
        rig.type("c")
        assertEquals("ABc", rig.editor.text)
    }

    @Test
    fun `two quick taps lock shift and a third releases it`() {
        rig.press(KeyCodes.SHIFT)
        rig.press(KeyCodes.SHIFT)
        assertEquals(ShiftState.LOCKED, rig.host.shiftState)
        rig.type("ab")
        rig.press(KeyCodes.SHIFT)
        rig.type("c")
        assertEquals("ABc", rig.editor.text)
    }

    @Test
    fun `shift spent on a swipe capitalises the swiped word, and the strip follows shift`() {
        rig.press(KeyCodes.SHIFT)
        assertTrue(rig.host.strip.isNotEmpty())
        assertTrue(rig.host.strip.all { it.text.first().isUpperCase() || !it.text.first().isLetter() })
        rig.orchestrator.composeSwipedWord(swiped("the", "then"))
        rig.settle()
        assertEquals(ShiftState.OFF, rig.host.shiftState)
        rig.type(" ")
        assertEquals("The ", rig.editor.text)
    }

    @Test
    fun `two spaces after a word make a full stop, and backspace takes it back`() {
        rig.type("hello  ")
        assertEquals("hello. ", rig.editor.text)
        rig.press(KeyCodes.DELETE)
        assertEquals("hello  ", rig.editor.text)
    }

    @Test
    fun `a mark gets its space, and the space typed after it is not doubled`() {
        rig.type("hello,")
        assertEquals("hello, ", rig.editor.text)
        rig.type(" world")
        assertEquals("hello, world", rig.editor.text)
    }

    @Test
    fun `a space typed before a mark moves after it`() {
        rig.type("hello ,")
        assertEquals("hello, ", rig.editor.text)
    }

    @Test
    fun `French keeps its space before an exclamation mark, English does not`() {
        rig.type("hello !")
        assertEquals("hello! ", rig.editor.text)
        val french = Pipeline.open("fr-FR")
        try {
            val frenchRig = french.typingRig(SMOKE_SETTINGS)
            frenchRig.startField()
            frenchRig.type("bonjour !")
            assertEquals("bonjour ! ", frenchRig.editor.text)
        } finally {
            french.close()
        }
    }

    @Test
    fun `a space typed after a picked word is not doubled`() {
        rig.pick(0, "the")
        assertEquals("the ", rig.editor.text)
        rig.type(" cat")
        assertEquals("the cat", rig.editor.text)
    }

    @Test
    fun `a text shortcut expands, and backspace takes it back`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(textShortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way"))),
        )
        rig.type("omw ")
        assertEquals("on my way ", rig.editor.text)
        rig.press(KeyCodes.DELETE)
        assertEquals("omw ", rig.editor.text)
    }

    @Test
    fun `a missing apostrophe is restored`() {
        rig.type("dont im ")
        assertEquals("don't I'm ", rig.editor.text)
    }

    @Test
    fun `a name's possessive gets its apostrophe and its capital`() {
        rig.type("lauras ")
        assertEquals("Laura's ", rig.editor.text)
    }

    @Test
    fun `a word a digit ends is left as typed`() {
        rig.type("sha256 covid19 i7 ")
        assertEquals("sha256 covid19 i7 ", rig.editor.text)
    }

    @Test
    fun `the typed chip takes a correction back`() {
        rig.type("teh ")
        assertEquals("the ", rig.editor.text)
        val chip = rig.host.typedIndex
        assertEquals("teh", rig.host.strip[chip].text)
        rig.pick(chip, "teh")
        assertEquals("teh ", rig.editor.text)
    }

    @Test
    fun `a pick in the middle of a finished word replaces the whole word`() {
        rig.type("hello world ")
        rig.moveCaret("hello wor".length)
        rig.pick(0, "wonderful")
        assertEquals("hello wonderful ", rig.editor.text)
    }

    @Test
    fun `holding backspace deletes the word before the caret`() {
        rig.type("hello world")
        rig.longPress(KeyCodes.DELETE)
        assertEquals("hello ", rig.editor.text)
    }

    @Test
    fun `undo takes back the word typed since the last step, and redo puts it back`() {
        rig.type("hello world")
        undo()
        assertEquals("hello ", rig.editor.text)
        redo()
        assertEquals("hello world", rig.editor.text)
    }

    @Test
    fun `text typed after an undo is kept, not replaced by redo`() {
        rig.type("hello world ")
        undo()
        assertEquals("hello ", rig.editor.text)
        rig.type("x")
        redo()
        assertEquals("hello x", rig.editor.text)
    }

    @Test
    fun `enter learns the word it ends`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField()
        rig.type("hello")
        rig.press(KeyCodes.ENTER)
        assertEquals("hello\n", rig.editor.text)
        assertTrue("written only after the debounce", rig.store.batches.isEmpty())
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(listOf("hello"), rig.store.batches.flatMap { batch -> batch.updates.map { it.word } })
    }

    @Test
    fun `a correction is learned only once the next key confirms it`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField()
        rig.type("teh ")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertFalse(rig.store.batches.flatMap { it.updates }.any { it.word == "the" })
        rig.type("a")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertTrue(rig.store.batches.flatMap { it.updates }.any { it.word == "the" })
    }

    @Test
    fun `holding backspace takes a correction back`() {
        rig.type("teh ")
        rig.longPress(KeyCodes.DELETE)
        assertEquals("teh ", rig.editor.text)
        assertEquals(listOf("the" to false), rig.host.forgotten)
    }

    @Test
    fun `with revert on backspace off, backspace keeps a correction and learns it`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(learningEnabled = true, revertCorrectionOnBackspace = false),
        )
        rig.startField()
        rig.type("teh ")
        rig.press(KeyCodes.DELETE)
        assertEquals("the", rig.editor.text)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(listOf("the"), rig.store.batches.flatMap { batch -> batch.updates.map { it.word } })
    }

    @Test
    fun `with revert on backspace off, backspace keeps a shortcut's expansion and learns nothing`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(
                learningEnabled = true,
                revertCorrectionOnBackspace = false,
                textShortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way")),
            ),
        )
        rig.startField()
        rig.type("omw ")
        rig.press(KeyCodes.DELETE)
        assertEquals("on my way", rig.editor.text)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertTrue(rig.store.batches.isEmpty())
    }

    @Test
    fun `a correction the caret has left is learned by the next backspace, which edits at the caret`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField()
        rig.type("teh ")
        rig.moveCaret(2)
        rig.press(KeyCodes.DELETE)
        assertEquals("te ", rig.editor.text)
        assertTrue(rig.host.forgotten.isEmpty())
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(listOf("the"), rig.store.batches.flatMap { batch -> batch.updates.map { it.word } })
    }

    @Test
    fun `undo drops a pending correction without learning it`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField()
        rig.type("hello teh ")
        undo()
        assertEquals("hello ", rig.editor.text)
        rig.press(KeyCodes.DELETE)
        assertEquals("hello", rig.editor.text)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(listOf("hello"), rig.store.batches.flatMap { batch -> batch.updates.map { it.word } })
    }

    @Test
    fun `the strip outlines the correction a space would write, never a shortcut's expansion`() {
        rig.type("teh")
        assertEquals("the", rig.host.strip[rig.host.appliedIndex].text)
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(textShortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way"))),
        )
        rig.startField()
        rig.type("omw")
        assertEquals(-1, rig.host.appliedIndex)
    }

    @Test
    fun `in Ask mode a language flip offers its replacements, and a picked one is applied`() {
        val both = Pipeline.open("ro-RO", "en-US")
        try {
            both.languageLock(BALANCED_EVIDENCE)
            val twoLanguages = both.typingRig(
                SMOKE_SETTINGS.copy(
                    languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_ASK,
                ),
            )
            twoLanguages.startField()
            for (word in ROMANIAN_PHRASE.split(' ') + "in") {
                twoLanguages.type("$word ")
            }
            for (word in ENGLISH_PHRASE.split(' ')) {
                twoLanguages.type("$word ")
            }
            assertTrue(twoLanguages.editor.text.contains(" în "))
            val offered = twoLanguages.host.offeredReplacements.flatten()
            val back = offered.single { it.previousText == "în" }
            assertEquals("in", back.text)
            twoLanguages.orchestrator.applyLanguageSwitchReplacements(listOf(back))
            twoLanguages.settle()
            val text = twoLanguages.editor.text
            assertTrue("the English word came back in '$text'", text.contains(" in ") && !text.contains("în"))
        } finally {
            both.close()
        }
    }

    @Test
    fun `with the language-switch correction off a language flip changes nothing and offers nothing`() {
        val both = Pipeline.open("ro-RO", "en-US")
        try {
            both.languageLock(BALANCED_EVIDENCE)
            val twoLanguages = both.typingRig(SMOKE_SETTINGS)
            twoLanguages.startField()
            for (word in ROMANIAN_PHRASE.split(' ') + "in") {
                twoLanguages.type("$word ")
            }
            for (word in ENGLISH_PHRASE.split(' ')) {
                twoLanguages.type("$word ")
            }
            assertTrue(twoLanguages.editor.text.contains(" în "))
            assertTrue(twoLanguages.host.offeredReplacements.isEmpty())
        } finally {
            both.close()
        }
    }

    /** The Undo quick action. */
    private fun undo() {
        rig.orchestrator.undo()
        rig.settle()
    }

    /** The Redo quick action. */
    private fun redo() {
        rig.orchestrator.redo()
        rig.settle()
    }

    /** A decode of [words], best first, cased as the orchestrator cases a swipe. */
    private fun swiped(vararg words: String): List<Candidate> =
        rig.orchestrator.caseSwipedWords(words.map { Candidate(it) })

    /** What a terminal does with a key: a letter or a space is written, Enter ends the line. */
    private fun terminal(keyCode: Int, metaState: Int) {
        val shifted = metaState and KeyEvent.META_SHIFT_ON != 0
        when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                rig.editor.commitText((if (shifted) letter.uppercaseChar() else letter).toString(), 1)
            }
            KeyEvent.KEYCODE_SPACE -> rig.editor.commitText(" ", 1)
            KeyEvent.KEYCODE_ENTER -> rig.editor.commitText("\n", 1)
            KeyEvent.KEYCODE_DEL -> rig.editor.deleteSurroundingText(1, 0)
        }
    }

    private companion object {
        /** ImeSmokeTest's settings. */
        val SMOKE_SETTINGS = KeyboardPreferences(
            learningEnabled = false,
            autoCorrectOnSpace = true,
            revertCorrectionOnBackspace = true,
            autoCapitalise = false,
            spaceAfterSuggestion = true,
            radialMenuEnabled = false,
            showSuggestionStrip = true,
            preferredLanguageTag = "",
            numberRow = false,
            modifierRow = false,
            languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_OFF,
        )

        /** The Languages screen's default. */
        const val BALANCED_EVIDENCE = 1.8f

        /** Words only one of the two shipped packs holds, as ImeSmokeTest types them. */
        const val ROMANIAN_PHRASE = "acesta trebuie foarte despre pentru"
        const val ENGLISH_PHRASE =
            "through because another thought between however people water number system"
    }
}
