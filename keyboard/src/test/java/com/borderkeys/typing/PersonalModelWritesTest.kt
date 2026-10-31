// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.MemoryBackups
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.PersonalModelLoad
import com.borderkeys.predict.Pipeline
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Every way the personal dictionary is written, the typing flow's commits and the pages' edits, a
 * restore and the clean-up, in a random order through the rig: the lookup the commits and edits
 * change stays the one the dictionary loads.
 */
class PersonalModelWritesTest {

    private lateinit var pipeline: Pipeline
    private lateinit var rig: TypingRig

    /** An engine of its own on the same pack, which loads what the personal dictionary holds. */
    private lateinit var loaded: Pipeline

    /** The backups of the rig's tables: a restore of what the dictionary holds, merged back into it. */
    private lateinit var backups: MemoryBackups

    @Before
    fun open() {
        Pipeline.require()
        pipeline = Pipeline.open(TAG)
        loaded = Pipeline.open(TAG)
        rig = pipeline.typingRig(SETTINGS)
        rig.startField()
        rig.loadPersonalModel()
        backups = MemoryBackups(rig.dictionary, LanguageTags.primary(TAG)) { rig.clock.now }
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
        if (this::loaded.isInitialized) {
            loaded.close()
        }
    }

    @Test
    fun `the personal dictionary loads the lookup the commits and edits leave, after random writes`() {
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            repeat(STEPS) { step ->
                if (write(random)) {
                    assertLoadsAsHeld("seed $seed, step $step")
                }
            }
            assertLoadsAsHeld("seed $seed")
        }
    }

    /**
     * One write, chosen by [random]: a commit, one on its way, a pause, a new field, or an edit on
     * the pages. Returns whether it was an edit.
     */
    private fun write(random: Random): Boolean {
        val word = WORDS.random(random)
        val now = rig.clock.now
        val kind = random.nextInt(WRITES)
        when (kind) {
            0, 1, 2 -> rig.type("$word ${WORDS.random(random)} ")
            3 -> unsettled("$word ")
            4 -> rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
            5 -> rig.startField()
            6 -> rig.edit { it.add((WORDS + ADDED).random(random), rig.host.subtype, now, unblock = false) }
            7 -> rig.edit {
                it.addPhrase(List(random.nextInt(2, 4)) { WORDS.random(random) }, rig.host.subtype, now, unblock = false)
            }
            8 -> rig.edit { it.block(word) }
            9 -> rig.edit { it.unblock(word) }
            10 -> rig.edit { it.forget(listOf(word)) }
            11 -> rig.edit { dictionary ->
                dictionary.phrases.first().takeIf { it.isNotEmpty() }?.let { dictionary.forgetPhrase(it.random(random)) }
            }
            12 -> rig.edit { it.keepWords(random.nextInt(WORDS.size)) }
            13 -> {
                rig.pause(CLEAN_UP_HALF_LIFE_MILLIS)
                rig.edit { it.decayStaleEntries(WORDS.size, CLEAN_UP_HALF_LIFE_MILLIS, UNCONFIRMED_MILLIS, rig.clock.now) }
            }
            14 -> rig.edit { it.forgetAllPhrases() }
            15 -> rig.edit { it.forgetEverything() }
            16 -> rig.edit { backups.restoreWhatIsHeld() }
            17 -> rig.edit { it.forgetTouchPattern() }
            else -> rig.edit { dictionary -> dictionary.purgeUnlearnable { words -> words.map { it != word } } }
        }
        return kind >= FIRST_EDIT
    }

    /** Presses the keys of [text] with nothing delivered between them: the engine's queue keeps what they ask. */
    private fun unsettled(text: String) {
        for (code in text.codePoints().toArray()) {
            rig.clock.advance(TypingRig.KEY_INTERVAL_MILLIS)
            rig.orchestrator.onKey(code)
        }
    }

    /**
     * Writes what waits, loads what the personal dictionary holds into an engine of its own, as the
     * keyboard loads it, and asserts its lookup holds what the rig's does: every word with uses, as the word table is
     * handed it, and every pair and triple with its context's total and observations.
     */
    private fun assertLoadsAsHeld(message: String) {
        rig.pause(LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        val model = rig.read { PersonalModelLoad.read(it, rig.orchestrator.preferences, emptySet(), rig.clock.now) }
        loaded.workingCopy.load(model)
        val held = rig.engine.workingCopy.lookup
        val fresh = loaded.workingCopy.lookup
        assertEquals("$message: the words", words(fresh), words(held))
        val keys = (held.heldWords() + fresh.heldWords()).mapTo(sortedSetOf()) { it.entry.key } +
            PhraseKey.word(UserBigram.SENTENCE_START)
        val language = LanguageTags.primary(rig.host.subtype)
        for (previous in keys) {
            assertEquals("$message: after $previous", fresh.pairsAfter(language, previous), held.pairsAfter(language, previous))
            for (before in keys) {
                assertEquals(
                    "$message: after $before $previous",
                    fresh.triplesAfter(language, before, previous),
                    held.triplesAfter(language, before, previous),
                )
            }
        }
    }

    /** [lookup]'s words with uses, as the word table is handed them, with whether a pack knows each, by key. */
    private fun words(lookup: PersonalLookup) =
        lookup.heldWords().filter { it.entry.count > 0 }.associate { it.entry.key to (it.entry to it.known) }.toSortedMap()

    private companion object {
        const val TAG = "en-US"

        /** How many random runs, one after another on the same dictionary, and how many writes each. */
        const val SEEDS = 8
        const val STEPS = 60

        /** How many kinds of write [write] chooses from, and the first of them that is an edit on the pages. */
        const val WRITES = 19
        const val FIRST_EDIT = 6

        /** The half-life the clean-up runs under, after as long a pause: ten seconds of the rig's clock. */
        const val CLEAN_UP_HALF_LIFE_MILLIS = 10_000L

        /** How long a word written once is kept by the clean-up: a minute of the rig's clock. */
        const val UNCONFIRMED_MILLIS = 60_000L

        /** The words typed, added and edited: ones the pack spells, so they are typed as they are. */
        val WORDS = listOf("the", "river", "cat", "dog", "go", "home", "we", "see", "you", "now", "sun")

        /** Words added by hand that the pack does not spell. */
        val ADDED = listOf("zorvani", "Kelpa")

        /** Learning on, and every word, pair and triple counted from its first use. */
        val SETTINGS = KeyboardPreferences(learningEnabled = true, autoCapitalise = false, learnAfterUses = 1)
    }
}
