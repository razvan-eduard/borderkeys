// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.language.LanguageTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [LanguageSwitchCorrector] against two shipped packs, with evidence from committed words. */
class LanguageSwitchPipelineTest {

    private lateinit var pipeline: Pipeline
    private lateinit var corrector: LanguageSwitchCorrector

    /** The slots the two packs were loaded into. */
    private val romanianPack: Int get() = pipeline.packOf(ROMANIAN_TAG)
    private val englishPack: Int get() = pipeline.packOf(ENGLISH_TAG)

    @Before
    fun open() {
        Pipeline.require()
        pipeline = Pipeline.open(ROMANIAN_TAG, ENGLISH_TAG)
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
        assertEquals("undecided before a word is written", NativePredictor.NO_PACK, pipeline.dominantPack())

        val romanian = verdictTrail(ROMANIAN)
        assertEquals("Romanian words settle on the Romanian pack", romanianPack, romanian.last())

        val english = verdictTrail(ENGLISH)
        assertEquals(
            "the verdict should move when the language does -- if it stops moving, the revert " +
                "can never fire and every test below is vacuous",
            englishPack, english.last(),
        )
        assertEquals(
            "six English words to overturn a settled Romanian verdict. If this grows " +
                "past LanguageSwitchCorrector.MAX_TRACKED the correction is forgotten before " +
                "the verdict turns, and the feature stops working without anything failing. " +
                "Verdict after each English word: $english",
            5, english.indexOfFirst { it == englishPack },
        )
    }

    @Test
    fun `the verdict passes through undecided rather than jumping`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        verdictTrail(ROMANIAN)
        val english = verdictTrail(ENGLISH)

        val lastRomanian = english.indexOfLast { it == romanianPack }
        val firstEnglish = english.indexOfFirst { it == englishPack }
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

    @Test
    fun `the pairs read are the verdict's language, else the heaviest pack's`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        pipeline.commitPhrase(ENGLISH)
        assertEquals("English has to be the verdict for this to mean anything",
                     englishPack, pipeline.dominantPack())
        assertEquals("the verdict's pairs are read", LanguageTags.primary(ENGLISH_TAG), pipeline.answerLanguage(""))

        pipeline.languageLock(LOCK_OFF)
        assertEquals("undecided with the lock off", NativePredictor.NO_PACK, pipeline.dominantPack())
        assertEquals("the first of the equally weighted packs is read, whatever the evidence",
                     LanguageTags.primary(ROMANIAN_TAG), pipeline.answerLanguage(""))
    }

    @Test
    fun `a swipe's decode names the language whose pairs it read`() {
        pipeline.preferredLanguage(ENGLISH_TAG)
        assertEquals(LanguageTags.primary(ENGLISH_TAG), pipeline.swipeLanguage(ROMANIAN_WORD))
        pipeline.preferredLanguage(ROMANIAN_TAG)
        assertEquals(LanguageTags.primary(ROMANIAN_TAG), pipeline.swipeLanguage(ROMANIAN_WORD))
    }

    /** What autocorrect reads in the letters as English, while the verdict stays Romanian. */
    @Test
    fun `answering as a language reads the letters as that language's autocorrect does`() {
        pipeline.languageLock(BALANCED_EVIDENCE)
        pipeline.commitPhrase(ROMANIAN)
        assertEquals("Romanian has to be the verdict for this to mean anything",
                     romanianPack, pipeline.dominantPack())

        val slip = pipeline.answerAs(englishPack, "makw")
        assertNotNull("an active pack answers", slip)
        assertEquals("English autocorrect's first choice for the slip",
                     "make", slip!!.corrections.first().text)
        assertTrue("English spells in exactly", pipeline.answerAs(englishPack, "in")!!.knownWordExact)
        assertEquals("answering as English leaves the verdict Romanian",
                     romanianPack, pipeline.dominantPack())
        assertNull("a slot with no pack answers nothing", pipeline.answerAs(EMPTY_SLOT, "in"))
    }

    @Test
    fun `a pack that agrees proposes no replacement`() {
        val flags = listOf(
            LanguageSwitchCorrector.Flag("care", "care", startOffset = 0, endOffset = 4, madeUnder = romanianPack),
        )
        assertTrue(corrector.resolve(flags, listOf("care")).isEmpty())
        // Nor one that differs only in case.
        assertTrue(corrector.resolve(flags, listOf("Care")).isEmpty())
    }

    @Test
    fun `a new field forgets what it was tracking`() {
        corrector.startUnder(romanianPack)
        corrector.recordCorrection("in", "în", startOffset = 0, endOffset = 2)
        corrector.reset()
        assertTrue("nothing is tracked across fields", corrector.snapshot(englishPack).isEmpty())
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

        /** A lock minimum that never locks. */
        const val LOCK_OFF = 0f

        const val ROMANIAN_TAG = "ro-RO"
        const val ENGLISH_TAG = "en-US"

        /** A slot with no pack in it. */
        const val EMPTY_SLOT = 3

        // Words only one of the two shipped packs holds.
        const val ROMANIAN = "acesta trebuie foarte despre pentru"
        const val ENGLISH =
            "through because another thought between however people water number system"
        val ROMANIAN_WORD = ROMANIAN.substringBefore(' ')
    }
}
