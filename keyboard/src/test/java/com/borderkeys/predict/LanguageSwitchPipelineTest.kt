// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.LanguageSwitchCorrector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The one feature in this keyboard that edits text the cursor has already moved past, measured.
 *
 * It has been argued about twice and never tested: `LanguageSwitchCorrector` had no test at all,
 * because half of what it needs is a native answer -- which language the conversation turned out
 * to be in, and what *that* pack would have spelled the word as. Both cross JNI, so the whole
 * feature was reachable only by typing two languages into a phone and watching.
 *
 * All of it is reachable here. The corrector holds no InputConnection and makes no native calls
 * of its own -- its own doc says so, and this is what that buys. The one thing left on the
 * service is reading the field to confirm the tracked word is still where it was, which is
 * supplied directly below; everything that decides anything is the shipping code.
 *
 * Two packs, and real evidence: dominance is not set, it is accumulated by committing words the
 * way `observeContextLanguage` sees them.
 */
class LanguageSwitchPipelineTest {

    private lateinit var pipeline: Pipeline
    private lateinit var corrector: LanguageSwitchCorrector

    @Before
    fun open() {
        assumeTrue(
            "needs the host bridge and compiled packs -- see PipelineTest",
            Pipeline.available(),
        )
        // Romanian first, so slot 0 is ro-RO and slot 1 en-US; the corrector deals in slots.
        pipeline = Pipeline.open("ro-RO", "en-US")
        corrector = LanguageSwitchCorrector()
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
    }

    /**
     * The premise the whole feature rests on -- that writing one language and then another
     * moves the verdict -- and what it costs, which turns out to be the more useful half.
     *
     * The count is asserted rather than the mere fact of a flip, because the count is what
     * decides whether the feature is reachable at all. It is six English words to overturn a
     * settled Romanian verdict, against the twenty *corrections* LanguageSwitchCorrector keeps
     * -- and since most words are not corrected, that is a good deal more than twenty words of
     * headroom. So a word corrected under the wrong language is still in hand when the verdict
     * turns. Were the count to drift past the window the revert would silently stop firing with
     * nothing failing, which is the shape of bug this file exists to catch.
     *
     * The mechanism, should this line ever need re-deriving: every observation multiplies all
     * evidence by kLanguageEvidenceDecay and awards up to 1.0 to the pack that knows the word
     * best, and a verdict needs kLanguageDominanceShare of the running total. So the old
     * language is not outvoted, it is outlasted -- which is why the count depends on how
     * settled Romanian was, and why the fixture words are checked to be one-sided.
     *
     * The measured trail across the English run is [0, 0, -1, -1, -1, 1, 1, 1, 1, 1].
     */
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

    /**
     * The verdict never crosses straight from one language to the other: the old evidence has
     * to decay below the 70% share before the new can reach it, and in between nothing is
     * dominant. Worth pinning because that gap is the safe state -- a correction applied during
     * it is applied by no pack in particular, and the revert has nothing to revert.
     */
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

    /** Only a flip is worth spending anything on. Reading the field and asking the engine both
     *  cost more than an int comparison, which is why this gate exists at all. */
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

    /**
     * The whole loop, end to end: a word corrected under one language, the conversation turning
     * out to be in another, and the correction revisited.
     *
     * "in" is the case this feature was built for. Typed in an English sentence it is an English
     * word; under a Romanian verdict the Romanian pack spells it "în", and that is what gets
     * applied. Once enough English follows, the verdict moves and the word is asked about again
     * -- this time of the English pack, which says "in".
     */
    @Test
    fun `a word corrected under the wrong language is offered back`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        pipeline.commitPhrase(ROMANIAN)
        val romanianPack = pipeline.dominantPack()
        assumeTrue("Romanian has to be the verdict for this to mean anything", romanianPack >= 0)

        // What the service records the moment it applies a correction: what was typed, what
        // landed, and where. The offsets are the field's, and are not read back here -- the
        // service verifies the text is still there before any of this runs.
        val applied = pipeline.candidateForPack(romanianPack, "in")
        assumeTrue("the Romanian pack has to offer something for \"in\"", applied != null)
        corrector.recordCorrection(
            LanguageSwitchCorrector.Flag(typedText = "in", appliedText = applied!!,
                                         startOffset = 0, endOffset = applied.length),
        )
        corrector.observeDominantPack(romanianPack)

        pipeline.commitPhrase(ENGLISH)
        val englishPack = pipeline.dominantPack()
        assumeTrue("the verdict has to move", englishPack >= 0 && englishPack != romanianPack)

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

    /** A pack that agrees with what is already there proposes nothing. Half of `resolve`'s job
     *  is refusing to offer a replacement that would change nothing. */
    @Test
    fun `a pack that agrees proposes no replacement`() {
        corrector.recordCorrection(
            LanguageSwitchCorrector.Flag("care", "care", startOffset = 0, endOffset = 4),
        )
        val tracked = corrector.snapshot()
        assertTrue(corrector.resolve(tracked, listOf("care")).isEmpty())
        // ...and case alone is not a disagreement worth showing anyone.
        assertTrue(corrector.resolve(tracked, listOf("Care")).isEmpty())
    }

    /** A new field is a new conversation, and an offset from the last one means nothing here. */
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

    /** The verdict after each word of [phrase], the words chained the way a sentence is --
     *  evidence accrues from the word *before* the one being asked about, so a word committed
     *  with no predecessor is never weighed at all. */
    private fun verdictTrail(phrase: String): List<Int> {
        var previous: String? = null
        return phrase.split(' ').filter { it.isNotEmpty() }.map { word ->
            previous = pipeline.commit(word, previous).committed ?: word
            pipeline.dominantPack()
        }
    }

    private companion object {
        /** The Languages screen's own default, so this measures the keyboard people have. */
        const val BALANCED_EVIDENCE = 1.8f

        /** Pipeline.open's argument order: the corrector deals in slots, not tags. */
        const val ROMANIAN_PACK = 0
        const val ENGLISH_PACK = 1

        // Words each language holds and the other does not, checked against both shipped
        // dictionaries -- observeContextLanguage awards nothing for a word both packs know, and
        // a fixture word that quietly enters the other pack would weaken this suite silently.
        //
        // The English run is long deliberately: six words is not enough to turn a settled
        // verdict, and an English phrase of six left the verdict undecided and every assertion
        // below unreachable. The length is the measurement, not padding.
        const val ROMANIAN = "acesta trebuie foarte despre pentru"
        const val ENGLISH =
            "through because another thought between however people water number system"
    }
}
