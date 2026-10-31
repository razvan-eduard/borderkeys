// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.DictionaryRepository.Added
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.Pipeline
import com.borderkeys.typing.TypingRig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The personal dictionary's edits as they reach [KeyboardPersonalModel], the production host on the
 * typing rig, while the keyboard stops: before the edit is asked, or while it waits or runs.
 */
class KeyboardPersonalModelTest {

    private lateinit var pipeline: Pipeline
    private lateinit var rig: TypingRig

    @Before
    fun open() {
        Pipeline.require()
        pipeline = Pipeline.open(TAG)
        rig = pipeline.typingRig(SETTINGS)
        rig.startField()
        rig.loadPersonalModel()
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
    }

    @Test
    fun `a forget asked of a keyboard that has stopped forgets the word`() {
        add()
        rig.stopKeyboard()
        rig.edit { it.forget(WORD) }
        assertWords()
    }

    @Test
    fun `a forget whose asker stopped waiting as it asked a keyboard that has stopped forgets the word`() {
        add()
        rig.stopKeyboard()
        rig.ask { dictionary ->
            currentCoroutineContext().cancel()
            dictionary.forget(WORD)
        }
        rig.settle()
        assertWords()
    }

    @Test
    fun `a forget the keyboard stops under before the forget ran forgets the word`() {
        add()
        val forget = rig.ask { it.forget(WORD) }
        rig.stopKeyboard()
        rig.settle()
        forget.answer()
        assertWords()
    }

    @Test
    fun `a forget whose asker stops waiting, the keyboard stopping before the forget ran, forgets the word`() {
        add()
        val forget = rig.ask { it.forget(WORD) }
        forget.stopWaiting()
        rig.stopKeyboard()
        rig.settle()
        assertWords()
    }

    @Test
    fun `a forget whose scope is cancelled while the forget runs forgets the word once and answers`() {
        add()
        val forget = rig.ask { it.forgetEveryCase(WORD) }
        serveEngineUntilWrite()
        rig.cancelPersonalScope()
        rig.settle()
        assertTrue(forget.answer())
        assertWords()
    }

    @Test
    fun `an add asked of a keyboard that has stopped adds the word once`() {
        rig.stopKeyboard()
        assertEquals(Added.ADDED, rig.edit { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) })
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `an add whose scope is cancelled while the add writes adds the word once and answers`() {
        val added = rig.ask { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        serveEngineUntilWrite()
        rig.cancelPersonalScope()
        rig.settle()
        assertEquals(Added.ADDED, added.answer())
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `an add the keyboard stops under while it waits on the engine adds the word once and answers`() {
        val added = rig.ask { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        serveEngineUntilWrite()
        check(rig.personalThread.runNext())
        rig.stopKeyboard()
        rig.settle()
        assertEquals(Added.ADDED, added.answer())
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `a block asked of a keyboard that has stopped blocks the word and forgets it`() {
        add()
        rig.stopKeyboard()
        rig.edit { it.block(WORD) }
        assertTrue(rig.read { it.isBlocked(WORD) })
        assertWords()
    }

    @Test
    fun `a block the keyboard stops under is not undone by the last flush of the word waiting to be saved`() {
        rig.type("$TYPED ")
        assertWords()
        val block = rig.ask { it.block(TYPED) }
        rig.stopKeyboard()
        rig.settle()
        block.answer()
        assertTrue(rig.read { it.isBlocked(TYPED) })
        assertWords()
    }

    @Test
    fun `an add the keyboard stops under while it waits on the lock runs under the lock, once`() {
        assertTrue(rig.personalLock.tryLock())
        val added = rig.ask { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        rig.stopKeyboard()
        rig.settle()
        assertWords()
        rig.personalLock.unlock()
        rig.settle()
        assertEquals(Added.ADDED, added.answer())
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `an add whose asker stops waiting while it waits on the lock, the keyboard then stopping, runs under the lock, once`() {
        assertTrue(rig.personalLock.tryLock())
        val added = rig.ask { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        added.stopWaiting()
        rig.stopKeyboard()
        rig.settle()
        assertWords()
        rig.personalLock.unlock()
        rig.settle()
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `two edits the keyboard stops under run once each, in the order they were asked`() {
        add()
        val forget = rig.ask { it.forget(WORD) }
        val added = rig.ask { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        rig.stopKeyboard()
        rig.settle()
        forget.answer()
        assertEquals(Added.ADDED, added.answer())
        assertWords(WORD to ONE_USE)
    }

    @Test
    fun `a forget whose keyboard part fails forgets the word, answers, and reports the failure`() {
        add()
        rig.blockedRefreshFailure = IllegalStateException(REFRESH_FAILED)
        val forget = rig.ask { it.forget(WORD) }
        val reported = runCatching { rig.settle() }.exceptionOrNull()
        forget.answer()
        assertEquals(REFRESH_FAILED, reported?.message)
        assertWords()
    }

    /** Adds [WORD] by hand while the keyboard runs. */
    private fun add() {
        rig.edit { it.add(WORD, rig.host.subtype, rig.clock.now, unblock = false) }
        assertWords(WORD to ONE_USE)
    }

    /** Serves the engine until a read or write of the personal dictionary waits on its thread. */
    private fun serveEngineUntilWrite() {
        while (rig.personalThread.waiting == 0) {
            check(rig.engine.serveNext()) { "nothing waits on the engine or the personal dictionary" }
        }
    }

    /** Asserts the word rows written are [rows], each a word with its count, in the order each was first written. */
    private fun assertWords(vararg rows: Pair<String, Int>) =
        assertEquals(rows.toList(), rig.writtenWords().map { it.word to it.count })

    private companion object {
        const val TAG = "en-US"

        /** A word the pack does not spell, added by hand. */
        const val WORD = "zorvani"

        /** A word the pack spells, typed and learned. */
        const val TYPED = "river"

        /** What the failing refresh of the blocked words throws. */
        const val REFRESH_FAILED = "the blocked words could not be refreshed"

        /** The count of a word added once. */
        const val ONE_USE = 1

        /** Learning on, and every word counted from its first use. */
        val SETTINGS = KeyboardPreferences(learningEnabled = true, autoCapitalise = false, learnAfterUses = ONE_USE)
    }
}
