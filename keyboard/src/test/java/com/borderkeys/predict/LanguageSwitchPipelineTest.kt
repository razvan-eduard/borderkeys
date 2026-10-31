// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.KeyboardPreferences
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
        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
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
        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
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
        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
        pipeline.commitPhrase(ROMANIAN)
        val settled = pipeline.dominantPack()

        assertTrue("the first verdict is a change from undecided",
                   corrector.observeDominantPack(settled))
        assertFalse("the same verdict twice is not a flip",
                    corrector.observeDominantPack(settled))
        assertFalse("nor three times", corrector.observeDominantPack(settled))
    }

    @Test
    fun `an answer leans on the verdict's pack, else the heaviest`() {
        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
        pipeline.commitPhrase(ENGLISH)
        assertEquals("English has to be the verdict for this to mean anything",
                     englishPack, pipeline.dominantPack())
        assertEquals("the verdict's pack", ENGLISH_TAG, pipeline.answerPack(""))

        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_OFF)
        assertEquals("undecided with the lock off", NativePredictor.NO_PACK, pipeline.dominantPack())
        assertEquals("the first of the equally weighted packs, whatever the evidence",
                     ROMANIAN_TAG, pipeline.answerPack(""))
    }

    @Test
    fun `the engine reads the store the request names`() {
        pipeline.preferredLanguage(ENGLISH_TAG)
        learnAfter(CONTEXT, ROMANIAN_NEXT, ENGLISH_NEXT)
        assertEquals("the engine leans on English", ENGLISH_TAG, pipeline.answerPack(""))
        assertEquals(ROMANIAN_NEXT, pipeline.strip("", CONTEXT, language = ROMANIAN_LANGUAGE).firstOrNull())
        assertEquals(ENGLISH_NEXT, pipeline.strip("", CONTEXT, language = ENGLISH_LANGUAGE).firstOrNull())
    }

    @Test
    fun `a swipe's decode reads the store the request names`() {
        pipeline.preferredLanguage(ENGLISH_TAG)
        learnAfter(UNKNOWN_CONTEXT, ROMANIAN_SWIPED, ENGLISH_SWIPED)
        assertEquals("with no store named, the path's own word leads",
                     ENGLISH_SWIPED, pipeline.decoded(ENGLISH_SWIPED, UNKNOWN_CONTEXT, "").first())
        assertEquals(ROMANIAN_SWIPED, pipeline.decoded(ENGLISH_SWIPED, UNKNOWN_CONTEXT, ROMANIAN_LANGUAGE).first())
        assertEquals(ENGLISH_SWIPED, pipeline.decoded(ENGLISH_SWIPED, UNKNOWN_CONTEXT, ENGLISH_LANGUAGE).first())
    }

    /** An explanation reads the word as a sentence's first, where each store has its own opener. */
    @Test
    fun `an explanation reads the store the request names`() {
        pipeline.preferredLanguage(ENGLISH_TAG)
        learnAfter(UserBigram.SENTENCE_START, ROMANIAN_NEXT, ENGLISH_NEXT)
        assertEquals(0, pipeline.explain("", ROMANIAN_NEXT, ROMANIAN_LANGUAGE)?.rank)
        assertEquals(0, pipeline.explain("", ENGLISH_NEXT, ENGLISH_LANGUAGE)?.rank)
    }

    /** Answering as a pack reads the word as a sentence's first, where each store has its own opener. */
    @Test
    fun `answering as a pack reads the store of that pack's language`() {
        learnAfter(UserBigram.SENTENCE_START, ROMANIAN_NEXT, ENGLISH_NEXT)
        assertEquals(ROMANIAN_NEXT, pipeline.answerAs(romanianPack, "")?.words?.firstOrNull())
        assertEquals(ENGLISH_NEXT, pipeline.answerAs(englishPack, "")?.words?.firstOrNull())
    }

    @Test
    fun `a swipe's decode names the pack it leaned on`() {
        pipeline.preferredLanguage(ENGLISH_TAG)
        assertEquals(ENGLISH_TAG, pipeline.swipeAnswerPack(ROMANIAN_WORD))
        pipeline.preferredLanguage(ROMANIAN_TAG)
        assertEquals(ROMANIAN_TAG, pipeline.swipeAnswerPack(ROMANIAN_WORD))
    }

    /** What autocorrect reads in the letters as English, while the verdict stays Romanian. */
    @Test
    fun `answering as a language reads the letters as that language's autocorrect does`() {
        pipeline.languageLock(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
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

    /**
     * Replaces the personal model with [romanian] learned after [context] in Romanian's store and
     * [english] after it in English's ([PersonalSnapshot.learnedAfter]).
     */
    private fun learnAfter(context: String, romanian: String, english: String) = pipeline.load(
        PersonalSnapshot.learnedAfter(context, mapOf(ROMANIAN_LANGUAGE to romanian, ENGLISH_LANGUAGE to english), AT),
    )

    private companion object {
        const val ROMANIAN_TAG = "ro-RO"
        const val ENGLISH_TAG = "en-US"

        /** A slot with no pack in it. */
        const val EMPTY_SLOT = 3

        // Words only one of the two shipped packs holds.
        const val ROMANIAN = "acesta trebuie foarte despre pentru"
        const val ENGLISH =
            "through because another thought between however people water number system"
        val ROMANIAN_WORD = ROMANIAN.substringBefore(' ')

        // A context and a word after it in each language's store.
        const val CONTEXT = "cappuccino"
        const val ROMANIAN_NEXT = "acesta"
        const val ENGLISH_NEXT = "through"

        // A swipe through the English word's keys decodes the Romanian one beside it.
        const val ENGLISH_SWIPED = "far"
        const val ROMANIAN_SWIPED = "dar"

        /** A context no pack spells, so neither pack's own pairs follow it. */
        const val UNKNOWN_CONTEXT = "zqxv"

        // The primary subtags each language's store is named by.
        val ROMANIAN_LANGUAGE = LanguageTags.primary(ROMANIAN_TAG)
        val ENGLISH_LANGUAGE = LanguageTags.primary(ENGLISH_TAG)

        /** When the loaded rows were last used. */
        const val AT = 1_000L
    }
}
