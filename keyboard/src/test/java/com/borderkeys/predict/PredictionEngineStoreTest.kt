// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import com.borderkeys.typing.ManualClock
import com.borderkeys.typing.SwipePath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PredictionEngine] on the host library, its main and prediction threads stepped by hand, the
 * Romanian and English packs open: a swipe, its preview and an explanation read the learned pairs
 * in the store each names.
 */
class PredictionEngineStoreTest {

    private val main = SteppedThread()
    private val prediction = SteppedThread()
    private val engine = PredictionEngine(main) { prediction }

    /** The words of each swipe answer, and of each preview answer, in the order they came. */
    private val swiped = mutableListOf<List<String>>()
    private val previewed = mutableListOf<List<String>>()

    @Before
    fun open() {
        Pipeline.require()
        assertTrue(engine.start())
        Pipeline.openPacks(engine, ROMANIAN_TAG, ENGLISH_TAG)
        engine.setPreferredLanguage(ENGLISH_TAG)
        engine.listener = object : PredictionEngine.ResultListener {
            override fun onSuggestions(
                candidates: List<Candidate>,
                knownWord: String,
                knownWordExact: Boolean,
                knownWordIsName: Boolean,
                query: String,
                possessive: String?,
                corrections: List<CorrectionOffer>,
                answerPack: String?,
            ) = Unit

            override fun onGestureCandidates(candidates: List<Candidate>, answerPack: String?) {
                swiped += candidates.map { it.text }
            }

            override fun onGesturePreviewCandidates(candidates: List<Candidate>) {
                previewed += candidates.map { it.text }
            }
        }
    }

    @After
    fun close() {
        engine.shutdown()
        prediction.drain()
    }

    @Test
    fun `a swipe and its preview read the store they name`() {
        engine.loadPersonal(
            PersonalSnapshot.learnedAfter(
                UNKNOWN_CONTEXT, mapOf(ROMANIAN to ROMANIAN_SWIPED, ENGLISH to ENGLISH_SWIPED), AT,
            ),
        )
        val path = SwipePath.through(ENGLISH_SWIPED, ManualClock())
        engine.decodeGesture(path.xs, path.ys, path.timestamps, path.count, UNKNOWN_CONTEXT, null, ROMANIAN)
        engine.decodeGesturePreview(path.xs, path.ys, path.timestamps, path.count, UNKNOWN_CONTEXT, null, ROMANIAN)
        serve()
        assertEquals("the swipe", ROMANIAN_SWIPED, swiped.single().first())
        assertEquals("its preview", ROMANIAN_SWIPED, previewed.single().first())
    }

    @Test
    fun `a commit before the personal model's first load learns nothing, and one after it learns`() {
        val written = mutableListOf<LinkWrites>()
        val commit = Commit(LearnedWord("river", ENGLISH, 1, AT), "the", null, ENGLISH)
        engine.commit(commit) { written += it }
        serve()
        engine.loadPersonal(PersonalSnapshot.EMPTY)
        engine.commit(commit) { written += it }
        serve()
        assertEquals("rows before the load, and after", listOf(true, false), written.map { it.isEmpty })
        assertEquals(listOf("river"), written.last().pairs.map { it.word })
    }

    /** An explanation reads the word as a sentence's first, where each store has its own opener. */
    @Test
    fun `an explanation reads the store it names`() {
        engine.loadPersonal(
            PersonalSnapshot.learnedAfter(
                UserBigram.SENTENCE_START, mapOf(ROMANIAN to ROMANIAN_OPENER, ENGLISH to ENGLISH_OPENER), AT,
            ),
        )
        val explained = mutableListOf<ScoreExplanation?>()
        engine.explain("", ROMANIAN_OPENER, ROMANIAN) { explained += it }
        serve()
        assertEquals(0, explained.single()?.rank)
    }

    /** Runs what the prediction thread was handed, then the answers it posted to the main thread. */
    private fun serve() {
        prediction.drain()
        main.drain()
    }

    private companion object {
        const val ROMANIAN_TAG = "ro-RO"
        const val ENGLISH_TAG = "en-US"
        val ROMANIAN = LanguageTags.primary(ROMANIAN_TAG)
        val ENGLISH = LanguageTags.primary(ENGLISH_TAG)

        // A swipe through the English word's keys decodes the Romanian one beside it.
        const val ENGLISH_SWIPED = "far"
        const val ROMANIAN_SWIPED = "dar"

        /** A context no pack spells, so neither pack's own pairs follow it. */
        const val UNKNOWN_CONTEXT = "zqxv"

        // A word each language's store opens sentences with.
        const val ROMANIAN_OPENER = "acesta"
        const val ENGLISH_OPENER = "through"

        /** When the loaded rows were last used. */
        const val AT = 1_000L
    }
}
