// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.LanguageSwitchCorrector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [LanguageSwitchCorrector] against two shipped packs, with evidence from committed words. */
class LanguageSwitchPipelineTest {

    private lateinit var pipeline: Pipeline
    private lateinit var corrector: LanguageSwitchCorrector

    @Before
    fun open() {
        Pipeline.require()
        // Slot 0 is ro-RO, slot 1 en-US.
        pipeline = Pipeline.open("ro-RO", "en-US")
        corrector = LanguageSwitchCorrector()
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
    }

    /** A settled Romanian verdict turns English at the sixth English word. */
    @Test
    fun `the verdict moves when the language does, and this is what it costs`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        assertEquals("undecided before a word is written", -1, pipeline.dominantPack())

        val romanian = verdictTrail(ROMANIAN)
        assertEquals("Romanian words settle on the Romanian pack", ROMANIAN_PACK, romanian.last())

        val english = verdictTrail(ENGLISH)
        assertEquals(
            "the verdict should move when the language does -- if it stops moving, the revert " +
                "can never fire and every test below is vacuous",
            ENGLISH_PACK, english.last(),
        )
        assertEquals(
            "six English words to overturn a settled Romanian verdict. If this grows " +
                "past LanguageSwitchCorrector.MAX_TRACKED the correction is forgotten before " +
                "the verdict turns, and the feature stops working without anything failing. " +
                "Verdict after each English word: $english",
            5, english.indexOfFirst { it == ENGLISH_PACK },
        )
    }

    @Test
    fun `the verdict passes through undecided rather than jumping`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        verdictTrail(ROMANIAN)
        val english = verdictTrail(ENGLISH)

        val lastRomanian = english.indexOfLast { it == ROMANIAN_PACK }
        val firstEnglish = english.indexOfFirst { it == ENGLISH_PACK }
        assertTrue("both verdicts should appear in one run", lastRomanian in 0 until firstEnglish)
        assertTrue(
            "there should be an undecided stretch between the two verdicts, not a jump",
            english.subList(lastRomanian + 1, firstEnglish).all { it == -1 },
        )
    }

    @Test
    fun `nothing is revisited while the language holds steady`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        pipeline.commitPhrase(ROMANIAN)
        val settled = pipeline.dominantPack()

        assertTrue("the first verdict is a change from undecided",
                   corrector.observeDominantPack(settled))
        assertFalse("the same verdict twice is not a flip",
                    corrector.observeDominantPack(settled))
        assertFalse("nor three times", corrector.observeDominantPack(settled))
    }

    /** "in", corrected under a Romanian verdict, is asked about again once the verdict turns. */
    @Test
    fun `a word corrected under the wrong language is offered back`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        pipeline.commitPhrase(ROMANIAN)
        val romanianPack = pipeline.dominantPack()
        assertEquals("Romanian has to be the verdict for this to mean anything",
                     ROMANIAN_PACK, romanianPack)

        // What the service records when it applies a correction.
        val applied = pipeline.candidateForPack(romanianPack, "in")
        assertNotNull(
            "the Romanian pack must offer something for \"in\" -- this was an assumption, which " +
                "meant the one test of the whole loop skipped itself rather than failed when " +
                "the answer went away",
            applied,
        )
        corrector.recordCorrection(
            LanguageSwitchCorrector.Flag(typedText = "in", appliedText = applied!!,
                                         startOffset = 0, endOffset = applied.length),
        )
        corrector.observeDominantPack(romanianPack)

        pipeline.commitPhrase(ENGLISH)
        val englishPack = pipeline.dominantPack()
        assertEquals("the verdict has to move for any of this to fire", ENGLISH_PACK, englishPack)

        assertTrue("a flip is what arms the revisit", corrector.observeDominantPack(englishPack))
        val tracked = corrector.snapshot()
        assertTrue("the correction should still be tracked", tracked.isNotEmpty())

        val suggestions = tracked.map { pipeline.candidateForPack(englishPack, it.typedText) }
        val replacements = corrector.resolve(tracked, suggestions)

        assertEquals("one tracked correction, one answer", 1, replacements.size)
        val replacement = replacements.single()
        assertEquals("it replaces what was applied", applied, replacement.previousText)
        assertNotEquals(
            "and with something else -- a replacement identical to what is there is not one",
            replacement.previousText, replacement.text,
        )
    }

    @Test
    fun `a pack that agrees proposes no replacement`() {
        corrector.recordCorrection(
            LanguageSwitchCorrector.Flag("care", "care", startOffset = 0, endOffset = 4),
        )
        val tracked = corrector.snapshot()
        assertTrue(corrector.resolve(tracked, listOf("care")).isEmpty())
        // Nor one that differs only in case.
        assertTrue(corrector.resolve(tracked, listOf("Care")).isEmpty())
    }

    @Test
    fun `a new field forgets what it was tracking`() {
        corrector.recordCorrection(
            LanguageSwitchCorrector.Flag("in", "în", startOffset = 0, endOffset = 2),
        )
        assertTrue(corrector.snapshot().isNotEmpty())
        corrector.reset()
        assertTrue("nothing is tracked across fields", corrector.snapshot().isEmpty())
        assertTrue("and the verdict is undecided again, so the next one counts as a change",
                   corrector.observeDominantPack(0))
    }

    /** The verdict after each word of [phrase], each word committed after the one before it. */
    private fun verdictTrail(phrase: String): List<Int> {
        var previous: String? = null
        return phrase.split(' ').filter { it.isNotEmpty() }.map { word ->
            previous = pipeline.commit(word, previous).committed ?: word
            pipeline.dominantPack()
        }
    }

    private companion object {
        /** The Languages screen's default. */
        const val BALANCED_EVIDENCE = 1.8f

        /** Slots, in Pipeline.open's argument order. */
        const val ROMANIAN_PACK = 0
        const val ENGLISH_PACK = 1

        // Words only one of the two shipped packs holds.
        const val ROMANIAN = "acesta trebuie foarte despre pentru"
        const val ENGLISH =
            "through because another thought between however people water number system"
    }
}
