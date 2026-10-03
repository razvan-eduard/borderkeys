// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType
import android.view.KeyEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.i18n.Keys
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
    fun `a swiped bare spelling the table rewrites composes with its apostrophe`() {
        rig.orchestrator.contractions = mapOf("didnt" to "didn't", "i" to "I")
        rig.orchestrator.onGestureCandidates(listOf(Candidate("didnt"), Candidate("didn't"), Candidate("didst")))
        rig.settle()
        assertEquals("didn't", rig.orchestrator.composingText)
        rig.type(" ")
        assertEquals("didn't ", rig.editor.text)
        rig.orchestrator.onGestureCandidates(listOf(Candidate("i"), Candidate("in")))
        rig.settle()
        rig.type(" ")
        assertEquals("didn't I ", rig.editor.text)
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
    fun `a password field is not corrected after a caret move, and its text is never asked about`() {
        rig.startField(passwordField = true, privateField = true)
        rig.engine.queries.clear()
        rig.type("teh ")
        rig.moveCaret(3)
        rig.type(" ")
        assertEquals("teh  ", rig.editor.text)
        assertEquals(emptyList<String>(), rig.engine.queries)
    }

    @Test
    fun `a password field gets exactly the keys typed`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(
                autoCapitalise = true,
                textShortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way")),
            ),
        )
        rig.startField(passwordField = true, privateField = true)
        val typed = "dont.im!omw!pass.word  a ,i."
        rig.type(typed)
        assertEquals(typed, rig.editor.text)
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
    fun `a pick where the caret splits two run-together words keeps the second`() {
        rig.startField("lokethis")
        rig.moveCaret("loke".length)
        rig.pick(1, "like")
        assertEquals("like this", rig.editor.text)
        assertEquals("like ".length, rig.editor.selectionEnd)
    }

    @Test
    fun `a word typed right before another is replaced without it`() {
        rig.startField("this")
        rig.moveCaret(0)
        rig.type("loke")
        rig.pick(1, "like")
        assertEquals("like this", rig.editor.text)
    }

    @Test
    fun `a pick that ends with the letters after the caret takes them`() {
        rig.startField("sonething")
        rig.moveCaret("sone".length)
        rig.pick(1, "something")
        assertEquals("something ", rig.editor.text)
    }

    @Test
    fun `picking the text before the caret as typed leaves the word whole`() {
        rig.startField("hello world")
        rig.moveCaret("hello wor".length)
        rig.pick(1, "wor")
        assertEquals("hello world", rig.editor.text)
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
    fun `a private copy takes the selection, and says so when there is none`() {
        rig.startField("hello world")
        assertNull(rig.orchestrator.privateCopyText())
        assertEquals(listOf(Keys.CLIP_PRIVATE_NOTHING_SELECTED), rig.host.notices)
        rig.editor.setSelection(6, 11)
        assertEquals("world", rig.orchestrator.privateCopyText())
        assertEquals(1, rig.host.notices.size)
    }

    @Test
    fun `a private field gives no private copy`() {
        rig.startField("hello world", privateField = true)
        rig.editor.setSelection(0, 5)
        assertNull(rig.orchestrator.privateCopyText())
        assertTrue(rig.host.notices.isEmpty())
    }

    @Test
    fun `a text flick writes its text mid-word and ends the composing word`() {
        rig.startField()
        rig.type("hel")
        assertTrue(rig.flick(KeyFlick('l'.code, KeyFlick.NORTH, KeyFlick.TEXT, "@")))
        assertEquals("hel@", rig.editor.text)
        assertNull(rig.editor.composingText)
        rig.type("lo")
        assertEquals("hel@lo", rig.editor.text)
    }

    @Test
    fun `a key flick presses the key it names, a caret flick while composing included`() {
        rig.startField()
        rig.type("abc")
        assertTrue(rig.flick(KeyFlick('c'.code, KeyFlick.WEST, KeyFlick.KEY, "left")))
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT), rig.host.physicalKeys.map { it.first })
        assertTrue(rig.flick(KeyFlick('c'.code, KeyFlick.SOUTH, KeyFlick.KEY, "delete")))
        assertEquals("ab", rig.editor.text)
        assertFalse(rig.flick(KeyFlick('c'.code, KeyFlick.EAST, KeyFlick.KEY, "symbols")))
    }

    @Test
    fun `a command flick asks the host for its quick action`() {
        rig.startField()
        rig.type("abc")
        assertTrue(rig.flick(KeyFlick('c'.code, KeyFlick.WEST, KeyFlick.COMMAND, QuickAction.DELETE_WORD.id.toString())))
        assertEquals(listOf(QuickAction.DELETE_WORD.id), rig.host.quickActions)
    }

    @Test
    fun `the editing actions go out as hardware keys with their modifiers`() {
        rig.startField("one two")
        assertTrue(rig.orchestrator.runEditingAction(QuickAction.SELECT_WORD_LEFT))
        val sent = rig.host.physicalKeys.single()
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_LEFT, sent.first)
        assertEquals(TypingOrchestrator.CTRL_META or TypingOrchestrator.SHIFT_META, sent.second)
        assertFalse(rig.orchestrator.runEditingAction(QuickAction.PASTE))
    }

    @Test
    fun `pasted text lands after the word in progress`() {
        rig.startField()
        rig.type("hi")
        rig.orchestrator.pasteText(" there")
        assertEquals("hi there", rig.editor.text)
        assertNull(rig.editor.composingText)
    }

    @Test
    fun `a pasted image goes to a field that takes images, after the word in progress`() {
        rig.startField(contentMimeTypes = listOf("image/*"))
        rig.type("hi")
        assertTrue(rig.orchestrator.pasteImage("content://clips/000/a.png", "image/png"))
        assertEquals(listOf("image/png content://clips/000/a.png"), rig.editor.contents)
        assertEquals("hi", rig.editor.text)
        assertNull(rig.editor.composingText)
        assertTrue(rig.host.notices.isEmpty())
    }

    @Test
    fun `a field that takes no images says so on the strip and gets nothing`() {
        rig.startField(contentMimeTypes = listOf("text/plain"))
        rig.type("hi")
        assertFalse(rig.orchestrator.pasteImage("content://clips/000/a.png", "image/png"))
        assertTrue(rig.editor.contents.isEmpty())
        assertEquals(listOf(Keys.CLIP_IMAGE_NOT_ACCEPTED), rig.host.notices)
    }

    @Test
    fun `a field that declares images but refuses the commit is told apart from one that takes it`() {
        rig.startField(contentMimeTypes = listOf("*/*"))
        rig.editor.takesContent = false
        assertFalse(rig.orchestrator.pasteImage("content://clips/000/a.png", "image/webp"))
        assertEquals(listOf(Keys.CLIP_IMAGE_NOT_ACCEPTED), rig.host.notices)
    }

    @Test
    fun `before the first unlock nothing is learned, and the unlock opens learning in the same field`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true))
        rig.startField(userUnlocked = false)
        rig.type("hello")
        rig.press(KeyCodes.ENTER)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertTrue(rig.store.batches.isEmpty())
        rig.orchestrator.onUserUnlocked()
        rig.type("world")
        rig.press(KeyCodes.ENTER)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(listOf("world"), rig.store.batches.flatMap { batch -> batch.updates.map { it.word } })
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
    fun `a shortcut's expansion the caret has left is not learned by the next backspace`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(
                learningEnabled = true,
                textShortcuts = listOf(TextShortcut(trigger = "omw", expansion = "on my way")),
            ),
        )
        rig.startField()
        rig.type("omw ")
        rig.moveCaret(2)
        rig.press(KeyCodes.DELETE)
        assertEquals("o my way ", rig.editor.text)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertTrue(rig.store.batches.isEmpty())
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
    fun `with the strip off nothing is shown, and a space still corrects`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(showSuggestionStrip = false))
        rig.startField()
        rig.type("teh")
        assertTrue(rig.host.strip.isEmpty())
        rig.type(" ")
        assertEquals("the ", rig.editor.text)
    }

    @Test
    fun `the strip holds as many words as the setting asks for`() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(suggestionCount = 4))
        rig.startField()
        rig.type("th")
        assertEquals(4, rig.host.strip.size)
    }

    @Test
    fun `an answer about a word already typed past is dropped`() {
        rig.orchestrator.onKey('t'.code)
        rig.orchestrator.onKey('h'.code)
        rig.settle()
        assertEquals("th", rig.host.strip[rig.host.typedIndex].text)
    }

    @Test
    fun `answers that arrive after a swipe replaced the typed word change nothing`() {
        for (code in "teh".map { it.code }) {
            rig.orchestrator.onKey(code)
        }
        rig.orchestrator.finishWordBeforeSwipe()
        rig.orchestrator.composeSwipedWord(swiped("then", "they"))
        val shown = rig.host.strip
        rig.settle()
        assertEquals(shown, rig.host.strip)
    }

    @Test
    fun `a swiped word is not asked about again`() {
        rig.engine.queries.clear()
        rig.orchestrator.composeSwipedWord(swiped("the", "then", "they"))
        rig.settle()
        assertFalse(rig.engine.queries.contains("the"))
    }

    @Test
    fun `an answer about the field before never applies in the next`() {
        rig.type("teh")
        rig.startField(settle = false)
        for (code in "teh ".map { it.code }) {
            rig.orchestrator.onKey(code)
        }
        rig.settle()
        assertEquals("teh ", rig.editor.text)
    }

    @Test
    fun `asking about the word in a private field reads the field into the strip`() {
        rig.startField(privateField = true)
        val before = rig.host.privateRevealRefreshes
        rig.orchestrator.requestSuggestions()
        assertEquals(before + 1, rig.host.privateRevealRefreshes)
    }

    @Test
    fun `a terminal's password field is never asked about`() {
        rig.startField(terminalField = true, passwordField = true, privateField = true)
        rig.engine.queries.clear()
        rig.type("ls")
        assertEquals(emptyList<String>(), rig.engine.queries)
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

    @Test
    fun `a Romanian verdict writes în whichever pack loaded first`() {
        for (order in listOf(listOf("ro-RO", "en-US"), listOf("en-US", "ro-RO"))) {
            val both = Pipeline.open(*order.toTypedArray())
            try {
                both.languageLock(BALANCED_EVIDENCE)
                val twoLanguages = both.typingRig(SMOKE_SETTINGS)
                twoLanguages.startField()
                for (word in ROMANIAN_PHRASE.split(' ') + "in") {
                    twoLanguages.type("$word ")
                }
                val text = twoLanguages.editor.text
                assertTrue("$order wrote '$text'", text.endsWith(" în "))
            } finally {
                both.close()
            }
        }
    }

    // ---- taps ---------------------------------------------------------------------------------

    @Test
    fun `each letter's tap travels with the word, and backspace takes the last one back`() {
        rig.tap('t'.code, 4, 11f, 12f)
        rig.tap('h'.code, 16, 21f, 22f)
        rig.tap('e'.code, 2, 31f, 32f)
        val taps = rig.orchestrator.taps
        assertEquals(3, taps.size)
        assertEquals(16, taps.keyIndexAt(1))
        assertEquals(21f, taps.xAt(1))
        assertEquals(22f, taps.yAt(1))
        rig.press(KeyCodes.DELETE)
        assertEquals(2, rig.orchestrator.taps.size)
        assertEquals(21f, rig.orchestrator.taps.xAt(1))
    }

    @Test
    fun `a delimiter ends the word and its taps`() {
        rig.tap('t'.code, 4, 11f, 12f)
        rig.type(" ")
        assertEquals(0, rig.orchestrator.taps.size)
    }

    @Test
    fun `a key chosen without a tap has no point`() {
        rig.type("t")
        assertEquals(1, rig.orchestrator.taps.size)
        assertFalse(rig.orchestrator.taps.isTapped(0))
    }

    @Test
    fun `a swiped word and a word adopted at the caret carry no taps`() {
        rig.swipe("the")
        assertEquals(3, rig.orchestrator.taps.size)
        assertFalse((0 until 3).any { rig.orchestrator.taps.isTapped(it) })
        rig.startField()
        rig.type("hello world ")
        rig.moveCaret("hello wor".length)
        assertEquals("wor", rig.orchestrator.composingText)
        assertFalse((0 until 3).any { rig.orchestrator.taps.isTapped(it) })
    }

    @Test
    fun `each request carries where the word's letters were tapped, no point for a key pressed without a tap`() {
        rig.tap('t'.code, 4, 11f, 12f)
        rig.type("h")
        assertEquals(listOf(11f, Float.NaN) to listOf(12f, Float.NaN), rig.engine.tapsAsked.last())
    }

    @Test
    fun `with Learning or the Heatmap off, and in a private field, requests still carry the taps`() {
        val fields = listOf<() -> Unit>(
            { startWithSwitches(learning = false, heatmap = true) },
            { startWithSwitches(learning = true, heatmap = false) },
            {
                startWithSwitches(learning = true, heatmap = true)
                rig.startField(privateField = true)
            },
        )
        for (field in fields) {
            field()
            rig.tap('t'.code, 4, 11f, 12f)
            rig.tap('h'.code, 16, 21f, 22f)
            assertEquals(listOf(11f, 21f) to listOf(12f, 22f), rig.engine.tapsAsked.last())
        }
    }

    @Test
    fun `a terminal's requests carry no taps`() {
        rig.startField(terminalField = true)
        rig.engine.tapsAsked.clear()
        rig.tap('t'.code, 4, 11f, 12f)
        rig.tap('h'.code, 16, 21f, 22f)
        assertTrue(rig.engine.tapsAsked.isNotEmpty())
        assertTrue(rig.engine.tapsAsked.all { it == null })
    }

    @Test
    fun `the learned patterns count while the heatmap is allowed, at the weight and minimum set`() {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(learningEnabled = true, heatmapWeight = 1.5f, heatmapMinTaps = 40),
        )
        rig.startField()
        assertEquals(QueuedEngine.TouchModelSetting(true, 1.5f, 40), rig.engine.touchModel)
        rig.startField(privateField = true)
        assertEquals(false, rig.engine.touchModel?.learned)
        rig.startField()
        assertEquals(true, rig.engine.touchModel?.learned)
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true, heatmapEnabled = false))
        assertEquals(false, rig.engine.touchModel?.learned)
    }

    // ---- the heatmap ---------------------------------------------------------------------------

    @Test
    fun `a word kept as typed teaches each of its keys where it was tapped`() {
        heatmapOn()
        tapWord("the", mapOf(0 to (0.1f to 0.2f)))
        rig.type(" ")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("t h e", touchedLetters())
        val t = rig.store.touches.first()
        assertEquals(0.1, t.sumX, 1e-5)
        assertEquals(0.2, t.sumY, 1e-5)
    }

    @Test
    fun `a correction the next key confirms teaches the key meant`() {
        heatmapOn()
        tapWord("thw", mapOf(2 to (0.3f to 0f)))
        rig.type(" ")
        assertEquals("the ", rig.editor.text)
        tapWord("a")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("t h e", touchedLetters())
        assertEquals(-0.7, rig.store.touches.single { it.code == 'e'.code }.sumX, 1e-5)
    }

    @Test
    fun `a correction taken back teaches the letters as typed`() {
        heatmapOn()
        tapWord("thw", mapOf(2 to (0.3f to 0f)))
        rig.type(" ")
        rig.press(KeyCodes.DELETE)
        assertEquals("thw ", rig.editor.text)
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("t h w", touchedLetters())
        assertEquals(0.3, rig.store.touches.single { it.code == 'w'.code }.sumX, 1e-5)
    }

    @Test
    fun `a completion picked from the strip teaches the letters typed`() {
        heatmapOn()
        tapWord("keyb")
        rig.pick(1, "keyboard")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("k e y b", touchedLetters())
    }

    @Test
    fun `with the Heatmap off, or in a private field, nothing is learned about taps`() {
        startWithSwitches(learning = true, heatmap = false)
        rig.orchestrator.keyGeometry = harnessGeometry()
        tapWord("the")
        rig.type(" ")
        heatmapOn()
        rig.startField(privateField = true)
        tapWord("the")
        rig.type(" ")
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals("", touchedLetters())
    }

    @Test
    fun `switching Learning off drops the words and taps not yet written`() {
        heatmapOn()
        tapWord("the")
        rig.type(" ")
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = false))
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        assertEquals(emptyList<LearningBatch>(), rig.store.batches)
    }

    @Test
    fun `the engine gets the patterns as taps are learned, and a new bucket's stored ones`() {
        heatmapOn()
        tapWord("the")
        rig.type(" ")
        assertEquals("e h t", rig.engine.touchPatterns!!.codes.joinToString(" ") { it.toChar().toString() })
        val landscape = HARNESS_BUCKET.copy(landscape = true)
        rig.store.storedTouches[landscape.key] = listOf(
            com.borderkeys.data.entity.KeyTouch(landscape.key, 'q'.code, 40.0, 4.0, 0.0, 1.0, 1.0, 0.0, 108f, 160f, 2.75f, rig.clock.now),
        )
        rig.orchestrator.keyGeometry = harnessGeometry(landscape)
        val patterns = rig.engine.touchPatterns!!
        assertEquals(listOf('q'.code), patterns.codes.toList())
        assertEquals(0.1f, patterns.meanX.single(), 1e-5f)
    }

    // ---- swipes and the ring ------------------------------------------------------------------

    @Test
    fun `a swipe types its word, and the strip shows its alternatives unmarked`() {
        rig.swipe("the")
        assertEquals("the", rig.editor.text)
        assertEquals("the", rig.host.strip.first().text)
        assertEquals(-1, rig.host.typedIndex)
        assertEquals(-1, rig.host.appliedIndex)
        assertTrue(rig.ring.opened.isEmpty())
    }

    @Test
    fun `a pause composes the top word and opens the ring, and a lift in place applies it`() {
        ringOn()
        rig.swipeAndPause("the")
        assertEquals("the", rig.editor.text)
        val ring = rig.ring.opened.single()
        assertEquals("the", ring.words.first())
        assertEquals(KeyboardPreferences().radialSuggestionCount, ring.words.size)
        assertFalse(ring.waitsForTap)
        assertEquals(KeyboardPreferences().radialPickTimeoutMillis.toLong(), ring.pickTimeoutMillis)
        rig.orchestrator.onRingLifted()
        rig.settle()
        assertEquals("the ", rig.editor.text)
    }

    @Test
    fun `a lift on a wedge applies that wedge's word`() {
        ringOn()
        rig.swipeAndPause("the")
        val word = rig.ring.opened.single().words[1]
        rig.ring.selectionNow = RingUi.Selection.Word(1, word)
        rig.orchestrator.onRingLifted()
        rig.settle()
        assertEquals("$word ", rig.editor.text)
        assertEquals(listOf<Int?>(1), rig.ring.closed)
    }

    @Test
    fun `a lift on the centre discards the swipe`() {
        ringOn()
        rig.swipeAndPause("the")
        rig.ring.selectionNow = RingUi.Selection.Cancel
        rig.orchestrator.onRingLifted()
        rig.settle()
        assertEquals("", rig.editor.text)
    }

    @Test
    fun `a lift with no pick discards the swipe when the timeout is set to cancel`() {
        ringOn(timeoutDefault = KeyboardPreferences.RADIAL_TIMEOUT_CANCEL)
        rig.swipeAndPause("the")
        rig.orchestrator.onRingLifted()
        rig.settle()
        assertEquals("", rig.editor.text)
    }

    @Test
    fun `the pick timeout resolves the ring from its highlight`() {
        ringOn()
        rig.swipeAndPause("the")
        val word = rig.ring.opened.single().words[2]
        rig.ring.selectionNow = RingUi.Selection.Word(2, word)
        rig.orchestrator.onRingTimedOut()
        rig.settle()
        assertEquals("$word ", rig.editor.text)
    }

    @Test
    fun `a lift in place keeps the ring open for a tap when set to`() {
        ringOn(liftKeepsOpen = true)
        rig.swipeAndPause("the")
        assertNull(rig.ring.opened.single().pickTimeoutMillis)
        rig.orchestrator.onRingLifted()
        rig.settle()
        assertEquals(1, rig.ring.keptOpenForTap)
        assertEquals("the", rig.editor.text)
    }

    @Test
    fun `a ring kept open after the lift waits for a tap on a wedge`() {
        ringOn(liftKeepsOpen = true)
        rig.swipe("hello")
        val ring = rig.ring.opened.single()
        assertTrue(ring.waitsForTap)
        assertNull(ring.pickTimeoutMillis)
        rig.orchestrator.onRingTapped(RingUi.Selection.Word(1, ring.words[1]))
        rig.settle()
        assertEquals("${ring.words[1]} ", rig.editor.text)
    }

    @Test
    fun `a tap on the centre of a kept-open ring discards the swipe, and a tap elsewhere leaves it`() {
        ringOn(liftKeepsOpen = true)
        rig.swipe("hello")
        rig.orchestrator.onRingTapped(RingUi.Selection.Cancel)
        rig.settle()
        assertEquals("", rig.editor.text)
        rig.swipe("hello")
        rig.orchestrator.onRingTapped(RingUi.Selection.None)
        rig.settle()
        assertEquals("hello", rig.editor.text)
        assertEquals(1, rig.ring.dismissals)
    }

    @Test
    fun `a decisive swipe opens no ring when the trusted word applies itself`() {
        ringOn(liftKeepsOpen = true, trustedWord = KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY)
        rig.swipe("the")
        assertEquals("the", rig.editor.text)
        assertTrue(rig.ring.opened.isEmpty())
    }

    @Test
    fun `a swipe after a pause that opened no ring replaces the pause's guess`() {
        ringOn()
        rig.ring.refuses = true
        rig.swipeAndPause("the")
        assertEquals(1, rig.ring.resumedCaptures)
        rig.swipe("the")
        assertEquals("the", rig.editor.text)
    }

    @Test
    fun `a ring closed from outside leaves the pause's word, and the next swipe adds its own`() {
        ringOn()
        rig.swipeAndPause("the")
        rig.orchestrator.dismissRing()
        rig.swipe("the")
        val words = rig.editor.text.split(' ')
        assertEquals("the", words.first())
        assertEquals(2, words.size)
    }

    @Test
    fun `a swipe's answer is dropped once the word is reset`() {
        val path = SwipePath.through("the", rig.clock)
        rig.orchestrator.onGesture(path.xs, path.ys, path.timestamps, path.count)
        rig.orchestrator.resetComposing()
        rig.settle()
        assertEquals("", rig.editor.text)
    }

    @Test
    fun `a pause in a terminal opens no ring`() {
        ringOn()
        rig.startField(terminalField = true)
        rig.swipeAndPause("the")
        assertEquals(1, rig.ring.resumedCaptures)
        assertTrue(rig.ring.opened.isEmpty())
    }

    @Test
    fun `a pause in a password field opens no ring`() {
        ringOn()
        rig.startField(passwordField = true, privateField = true)
        rig.swipeAndPause("the")
        assertEquals(1, rig.ring.resumedCaptures)
        assertTrue(rig.ring.opened.isEmpty())
        assertEquals("", rig.editor.text)
    }

    @Test
    fun `an empty decode clears the strip and asks about the word afresh`() {
        rig.type("he")
        rig.engine.queries.clear()
        rig.orchestrator.onGestureCandidates(emptyList())
        assertTrue(rig.host.strip.isEmpty())
        assertEquals(listOf("he"), rig.engine.queries)
    }

    @Test
    fun `a swipe into a terminal goes out as keys`() {
        rig.startField(terminalField = true)
        rig.swipe("the")
        assertEquals(
            listOf(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_E),
            rig.host.physicalKeys.map { it.first },
        )
    }

    /** Opens a fresh field with Learning and the Heatmap on, the harness's keys snapshotted. */
    private fun heatmapOn() {
        rig.orchestrator.applySettings(SMOKE_SETTINGS.copy(learningEnabled = true, heatmapEnabled = true))
        rig.orchestrator.keyGeometry = harnessGeometry()
        rig.startField()
    }

    /** Taps each letter of [word] at its key's centre moved by [offsets], in key units. */
    private fun tapWord(word: String, offsets: Map<Int, Pair<Float, Float>> = emptyMap()) {
        word.forEachIndexed { index, letter ->
            val (x, y) = Pipeline.keyCentre(letter)
            val (dx, dy) = offsets[index] ?: (0f to 0f)
            rig.tap(letter.code, index, x + dx * HARNESS_KEY_WIDTH, y + dy * HARNESS_KEY_HEIGHT)
        }
    }

    /** The letters the heatmap wrote totals for, in order. */
    private fun touchedLetters(): String =
        rig.store.touches.joinToString(" ") { it.code.toChar().toString() }

    /** Opens a fresh field with the Learning switch at [learning] and the Heatmap at [heatmap]. */
    private fun startWithSwitches(learning: Boolean, heatmap: Boolean) {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(learningEnabled = learning, heatmapEnabled = heatmap),
        )
        rig.startField()
    }

    /** Switches the ring on, with [liftKeepsOpen], [timeoutDefault] and [trustedWord], anew. */
    private fun ringOn(
        liftKeepsOpen: Boolean = false,
        timeoutDefault: Int = KeyboardPreferences.RADIAL_TIMEOUT_APPLY_TOP,
        trustedWord: Int = KeyboardPreferences.RADIAL_TRUSTED_CHIP,
    ) {
        rig.orchestrator.applySettings(
            SMOKE_SETTINGS.copy(
                radialMenuEnabled = true,
                radialLiftKeepsOpen = liftKeepsOpen,
                radialTimeoutDefault = timeoutDefault,
                radialTrustedWord = trustedWord,
            ),
        )
        rig.startField()
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
