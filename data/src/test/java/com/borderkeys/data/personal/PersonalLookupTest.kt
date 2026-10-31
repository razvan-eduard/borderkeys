// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The links the lookup records at a commit, under the budget, and the slice it hands the engine,
 * its words given ids as the engine's word table gives them.
 */
class PersonalLookupTest {

    private val lookup = PersonalLookup()

    /** The next id the engine's word table would give. */
    private var nextId = 0

    /** Hands the lookup the ids the engine's word table would give [rows]. */
    private fun hold(rows: WordRows) {
        lookup.assigned(rows, IntArray(rows.size) { if (rows.ids[it] >= 0) rows.ids[it] else nextId++ }, BooleanArray(rows.size))
    }

    /** Loads [words], each used [USES] times, and [pairs] and [triples], with their contexts and [clocks]. */
    private fun load(
        words: List<String>,
        pairs: List<UserBigram> = emptyList(),
        triples: List<UserTrigram> = emptyList(),
        pairContexts: List<UserPairContext> = emptyList(),
        tripleContexts: List<UserTripleContext> = emptyList(),
        clocks: List<UserPhraseClock> = emptyList(),
    ) {
        hold(lookup.loadWords(words.map { UserWord(it, LANGUAGE, USES, AT) }, pairs, triples))
        lookup.loadLinks(pairs, pairContexts, triples, tripleContexts, clocks)
    }

    /**
     * Commits [word] after [previous1] and [previous2] in [language] as the keyboard does: its words
     * held, then its links recorded, every word learnable but those of [unlearnable].
     */
    private fun commit(
        word: String,
        previous1: String?,
        previous2: String? = null,
        language: String = LANGUAGE,
        pairAsserted: Boolean = false,
        tripleAsserted: Boolean = false,
        at: Long = AT,
        unlearnable: Set<String> = emptySet(),
        asked: MutableList<String> = mutableListOf(),
    ): LinkWrites {
        hold(lookup.commit(language, LearnedWord(word, language, 1, at), previous1, previous2))
        return lookup.commitLinks(language, word, previous1, previous2, pairAsserted, tripleAsserted, at) { words ->
            asked += words
            words.map { it !in unlearnable }
        }
    }

    /** Counts one use of [word] in [language], held as the engine would hold it; returns the rows the engine takes. */
    private fun count(word: String, language: String): WordRows =
        lookup.commit(language, LearnedWord(word, language, 1, AT), null, null).also(::hold)

    /** The count [rows] hand the engine for [word]. */
    private fun handedCount(rows: WordRows, word: String): Int =
        rows.fields[rows.keys.indexOf(word) * WordRows.FIELDS + WordRows.COUNT]

    /** Active packs in [languages], those of [spelledBy] spelling each word it names. */
    private fun packs(languages: Set<String>, spelledBy: Map<String, Set<String>> = emptyMap()) =
        ActivePacks(languages) { words -> words.map { spelledBy[it].orEmpty() } }

    @Test
    fun `a word is counted in each language it is committed in, and handed to the engine at its highest`() {
        val packs = packs(setOf(LANGUAGE, OTHER), mapOf("taxi" to setOf(LANGUAGE, OTHER)))
        hold(lookup.loadWords(listOf("taxi", "zqxw").map { UserWord(it, UserWord.UNPLACED, USES, AT) }, emptyList(), emptyList(), packs))
        repeat(2) { count("taxi", LANGUAGE) }
        val taxi = count("taxi", OTHER)
        assertEquals(
            PersonalLookup.WordCounts(USES + 3, mapOf(LANGUAGE to USES + 2, OTHER to USES + 1), 0),
            lookup.counts("taxi"),
        )
        assertEquals("the engine is handed its highest count", USES + 2, handedCount(taxi, "taxi"))
        val unplaced = count("zqxw", OTHER)
        assertEquals(PersonalLookup.WordCounts(USES + 1, mapOf(OTHER to 1), USES), lookup.counts("zqxw"))
        assertEquals("uses in no known language count in every one", USES + 1, handedCount(unplaced, "zqxw"))
        val new = count("drum", OTHER)
        assertEquals(PersonalLookup.WordCounts(1, mapOf(OTHER to 1), 0), lookup.counts("drum"))
        assertEquals(1, handedCount(new, "drum"))
    }

    @Test
    fun `a disabled language's words stay in the lookup, their counts in the word table zero until its pack is back`() {
        val words = listOf("drum", "lung", "taxi").map {
            UserWord(it, UserWord.UNPLACED, USES, AT, deliberateCapitals = 1, asserted = 1, added = 1)
        }
        val pairs = listOf(UserBigram(OTHER, "drum", "lung", USES, AT, 0L, 0L))
        val spelledBy = mapOf("drum" to setOf(OTHER), "taxi" to setOf(LANGUAGE, OTHER))
        fun loadUnder(languages: Set<String>): WordRows = lookup.loadWords(
            words, pairs, emptyList(), packs(languages, spelledBy.mapValues { it.value intersect languages }),
        ).also(::hold)
        fun handed(rows: WordRows, word: String): List<Int> =
            rows.keys.indexOf(word).let { rows.fields.slice(it * WordRows.FIELDS until (it + 1) * WordRows.FIELDS) }
        val held = listOf(USES, 1, 1, 1)
        val both = loadUnder(setOf(LANGUAGE, OTHER))
        assertEquals(held, handed(both, "drum"))
        val english = loadUnder(setOf(LANGUAGE))
        assertEquals(
            "placed by its link in a language no active pack is in",
            PersonalLookup.WordCounts(USES, mapOf(OTHER to USES), 0),
            lookup.counts("drum"),
        )
        assertEquals("handed with nothing", listOf(0, 0, 0, 0), handed(english, "drum"))
        assertEquals(listOf(0, 0, 0, 0), handed(english, "lung"))
        assertEquals("a word an active pack spells too stays", held, handed(english, "taxi"))
        assertEquals("the pack back, so is the word", held, handed(loadUnder(setOf(LANGUAGE, OTHER)), "drum"))
        assertEquals("with no pack active, no language is disabled", held, handed(loadUnder(emptySet()), "drum"))
        loadUnder(setOf(LANGUAGE))
        assertEquals("a use in an active language counts there", listOf(1, 1, 1, 1), handed(count("drum", LANGUAGE), "drum"))
    }

    @Test
    fun `a word's rows load in their own languages, only its uses in no language known placed, its spellings one form each`() {
        val words = listOf(
            UserWord("drum", OTHER, USES, AT),
            UserWord("lung", LANGUAGE, 2, AT),
            UserWord("lung", UserWord.UNPLACED, USES, AT + 1),
            UserWord("Pizza", LANGUAGE, 2, AT, deliberateCapitals = 1),
            UserWord("Pizza", OTHER, USES, AT + 2, asserted = 1),
            UserWord("zqxw", UserWord.UNPLACED, USES, AT),
            UserWord("Taxi", LANGUAGE, 2, AT),
            UserWord("taxi", OTHER, 2, AT),
            UserWord("taxi", UserWord.UNPLACED, 1, AT),
        )
        val rows = lookup.loadWords(words, emptyList(), emptyList(), packs(setOf(LANGUAGE), mapOf("lung" to setOf(LANGUAGE))))
        hold(rows)
        assertEquals(PersonalLookup.WordCounts(USES, mapOf(OTHER to USES), 0), lookup.counts("drum"))
        assertEquals("a disabled language's word with no link is not offered", 0, handedCount(rows, "drum"))
        assertEquals(PersonalLookup.WordCounts(2 + USES, mapOf(LANGUAGE to 2 + USES), 0), lookup.counts("lung"))
        assertEquals(PersonalLookup.WordCounts(2 + USES, mapOf(LANGUAGE to 2, OTHER to USES), 0), lookup.counts("pizza"))
        assertEquals(
            "one spelling's rows make one form",
            PersonalSpelling.Merged("pizza", "Pizza", count = 2, deliberateCapitals = 1, asserted = 1, added = 0),
            lookup.heldWords().single { it.entry.key == "pizza" }.entry,
        )
        assertEquals(PersonalLookup.WordCounts(USES, emptyMap(), USES), lookup.counts("zqxw"))
        assertEquals("uses in no language known are offered everywhere", USES, handedCount(rows, "zqxw"))
        assertEquals("a spelling's rows count as one", "taxi", lookup.heldWords().single { it.entry.key == "taxi" }.entry.spelling)
    }

    @Test
    fun `a deleted row takes its uses out of its own language, one of no language known out of where it was placed`() {
        val lungPlaced = UserWord("lung", UserWord.UNPLACED, USES, AT)
        val pizzaInOther = UserWord("pizza", OTHER, USES, AT)
        val zqxw = UserWord("zqxw", UserWord.UNPLACED, USES, AT)
        val words = listOf(UserWord("lung", LANGUAGE, 2, AT), lungPlaced, UserWord("pizza", LANGUAGE, 2, AT), pizzaInOther, zqxw)
        hold(lookup.loadWords(words, emptyList(), emptyList(), packs(setOf(LANGUAGE, OTHER), mapOf("lung" to setOf(LANGUAGE)))))
        hold(lookup.removed(listOf(lungPlaced, pizzaInOther, zqxw)))
        assertEquals(PersonalLookup.WordCounts(2, mapOf(LANGUAGE to 2), 0), lookup.counts("lung"))
        assertEquals(PersonalLookup.WordCounts(2, mapOf(LANGUAGE to 2, OTHER to 0), 0), lookup.counts("pizza"))
        assertEquals(PersonalLookup.WordCounts(0, emptyMap(), 0), lookup.counts("zqxw"))
    }

    @Test
    fun `a commit records the pair when both words are learnable, the triple when all three are, never asking of the sentence start`() {
        load(listOf("the", "cat", "sat"))
        assertTrue(commit("sat", "cat", "the").let { it.pairs.isNotEmpty() && it.triples.isNotEmpty() })
        assertTrue(
            "an unlearnable word two before keeps the pair alone",
            commit("sat", "cat", "the", unlearnable = setOf("the")).let { it.pairs.isNotEmpty() && it.triples.isEmpty() },
        )
        assertTrue(
            "an unlearnable word before records neither",
            commit("sat", "cat", "the", unlearnable = setOf("cat")).isEmpty,
        )
        assertTrue("nor an unlearnable word", commit("sat", "cat", "the", unlearnable = setOf("sat")).isEmpty)
        val asked = mutableListOf<String>()
        assertTrue(
            "the sentence start is learnable",
            commit("cat", UserBigram.SENTENCE_START, asked = asked).pairs.isNotEmpty(),
        )
        assertEquals("and never asked about", listOf("cat"), asked)
        assertTrue("with no word before, nothing", commit("cat", null).isEmpty)
    }

    @Test
    fun `a word after itself, or after a word not held, records no link`() {
        load(listOf("very", "so"))
        assertTrue(commit("Very", "very", "so").isEmpty)
        hold(lookup.loadWords(listOf(UserWord("time", LANGUAGE, USES, AT)), emptyList(), emptyList()))
        lookup.commit(LANGUAGE, LearnedWord("acum", LANGUAGE, 0, AT), null, null)
        assertTrue(
            "a word the engine refused has no id, and links to nothing",
            lookup.commitLinks(LANGUAGE, "time", "acum", null, false, false, AT) { words -> words.map { true } }.isEmpty,
        )
        val pairs = listOf(
            UserBigram(LANGUAGE, "first", "time", USES, AT, 0L, 0L),
            UserBigram(LANGUAGE, "first", "unheld", USES, AT, 0L, 0L),
            UserBigram(LANGUAGE, "very", "very", USES, AT, 0L, 0L),
        )
        val rows = lookup.loadWords(listOf(UserWord("time", LANGUAGE, USES, AT)), pairs, emptyList())
        lookup.assigned(
            rows, IntArray(rows.size) { if (rows.keys[it] == "unheld") WordRows.NEW_WORD else nextId++ }, BooleanArray(rows.size),
        )
        lookup.loadLinks(pairs, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(
            "a loaded pair is held whatever its words' counts, one naming a word the engine refused or following itself left out",
            listOf("time"),
            lookup.pairsAfter(LANGUAGE, "first").links.map { it.word },
        )
        assertTrue(lookup.pairsAfter(LANGUAGE, "very").links.isEmpty())
    }

    @Test
    fun `each use counts once, observes its context and moves its language's clock, and is stamped with both`() {
        load(listOf("the", "cat", "dog", "on"))
        commit("cat", "the", at = 1L)
        commit("dog", "the", at = 2L)
        val writes = commit("cat", "the", "on", pairAsserted = true, at = 3L)
        assertEquals(
            listOf(UserBigram(LANGUAGE, "the", "cat", 2, 3L, contextSeen = 3L, languageSeen = 3L, asserted = 1)),
            writes.pairs,
        )
        assertEquals(listOf(UserPairContext(LANGUAGE, "the", 3L, 3L, seen = 3L)), writes.pairContexts)
        assertEquals(
            listOf(UserTrigram(LANGUAGE, "on", "the", "cat", 1, 3L, contextSeen = 1L, languageSeen = 1L)),
            writes.triples,
        )
        assertEquals(listOf(UserTripleContext(LANGUAGE, "on", "the", 1L, 3L, seen = 1L)), writes.tripleContexts)
        assertEquals("the triples keep a clock of their own", listOf(UserPhraseClock(LANGUAGE, 3L, 1L)), writes.clocks)
        assertEquals(
            listOf(PersonalLookup.HeldLink("cat", 2, 1, 3L, 3L), PersonalLookup.HeldLink("dog", 1, 0, 2L, 2L)),
            lookup.pairsAfter(LANGUAGE, "the").links,
        )
    }

    @Test
    fun `a load holds a link's case forms as one, keeps its stamps, and raises its context, never lowering it`() {
        val pairs = listOf(
            UserBigram(LANGUAGE, "the", "friday", 2, AT, contextSeen = 4L, languageSeen = 9L),
            UserBigram(LANGUAGE, "the", "friday", 3, AT, contextSeen = 7L, languageSeen = 40L, asserted = 1),
            UserBigram(LANGUAGE, "the", "night", 4, AT, contextSeen = 2L, languageSeen = 30L),
        )
        load(
            listOf("The", "Friday", "night"), pairs,
            pairContexts = listOf(UserPairContext(LANGUAGE, "the", 20L, AT, seen = 12L)),
            clocks = listOf(UserPhraseClock(LANGUAGE, 40L, 5L)),
        )
        val context = lookup.pairsAfter(LANGUAGE, "the")
        assertEquals(
            listOf(PersonalLookup.HeldLink("friday", 5, 1, 7L, 40L), PersonalLookup.HeldLink("night", 4, 0, 2L, 30L)),
            context.links,
        )
        assertEquals(listOf(20L, 12L), listOf(context.total, context.seen))
        assertEquals(
            "the next use goes on from the loaded observations and clock",
            listOf(UserBigram(LANGUAGE, "the", "night", 5, AT, contextSeen = 13L, languageSeen = 41L)),
            commit("night", "the").pairs,
        )
        load(
            listOf("The", "Friday", "night"), pairs,
            pairContexts = listOf(UserPairContext(LANGUAGE, "the", 3L, AT, seen = 1L)),
        )
        assertEquals(
            "a loaded total below its links' uses leaves the total at them",
            listOf(9L, 1L),
            lookup.pairsAfter(LANGUAGE, "the").let { listOf(it.total, it.seen) },
        )
    }

    @Test
    fun `a context's links are offered chosen first, then the most used, then in the order recorded`() {
        load(listOf("on", "xx", "yy", "zz", "ww"))
        commit("zz", "on")
        commit("yy", "on")
        commit("xx", "on")
        assertEquals(listOf("zz", "yy", "xx"), lookup.pairsAfter(LANGUAGE, "on").links.map { it.word })
        commit("xx", "on")
        commit("ww", "on", pairAsserted = true)
        assertEquals(listOf("ww", "xx", "zz", "yy"), lookup.pairsAfter(LANGUAGE, "on").links.map { it.word })
        lookup.drop(listOf(UserBigram(LANGUAGE, "on", "zz", 1, AT, 0L, 0L)), emptyList())
        val after = lookup.pairsAfter(LANGUAGE, "on")
        assertEquals("a dropped link leaves the rest in their order", listOf("ww", "xx", "yy"), after.links.map { it.word })
        assertEquals("and its uses in the total", 5L, after.total)
    }

    @Test
    fun `each language keeps its own links`() {
        load(listOf("de", "la"))
        commit("la", "de", language = "ro")
        commit("la", "de", language = "es")
        commit("la", "de", language = "es")
        assertEquals(listOf(1), lookup.pairsAfter("ro", "de").links.map { it.count })
        assertEquals(listOf(2), lookup.pairsAfter("es", "de").links.map { it.count })
        assertTrue(lookup.pairsAfter("fr", "de").links.isEmpty())
    }

    @Test
    fun `a link names one spelling, its marks kept and only its case folded`() {
        load(listOf("pe", "masă", "masa"))
        commit("Masă", "pe")
        assertEquals(listOf("masă"), lookup.pairsAfter(LANGUAGE, "pe").links.map { it.word })
        assertEquals(
            "masă and masa are two words",
            setOf("pe", "masă", "masa"),
            lookup.heldWords().map { it.entry.key }.filter { it != UserBigram.SENTENCE_START }.toSet(),
        )
    }

    @Test
    fun `a store at its cap keeps every link and every total until a commit takes it past, which gives up a tenth`() {
        val contexts = PhraseBudget.LANGUAGE_CAP / FOLLOWERS
        val pairs = (0 until PhraseBudget.LANGUAGE_CAP).map {
            UserBigram(LANGUAGE, "w${it / FOLLOWERS}", "x${it % FOLLOWERS}", 1, AT, 1L, it.toLong())
        }
        load(emptyList(), pairs, clocks = listOf(UserPhraseClock(LANGUAGE, PhraseBudget.LANGUAGE_CAP.toLong(), 0L)))
        val loaded = (0 until contexts).map { lookup.pairsAfter(LANGUAGE, "w$it") }
        assertEquals("every link loaded", PhraseBudget.LANGUAGE_CAP, loaded.sumOf { it.links.size })
        assertTrue("every context's total counting its links", loaded.all { it.total == FOLLOWERS.toLong() })
        val writes = commit("pair", "fresh")
        assertEquals(PhraseBudget.LANGUAGE_CAP + 1 - PhraseBudget.LANGUAGE_KEEP, writes.gonePairs.size)
        val after = (0 until contexts).map { lookup.pairsAfter(LANGUAGE, "w$it") }
        assertEquals(PhraseBudget.LANGUAGE_KEEP - 1, after.sumOf { it.links.size })
        assertTrue(
            "each context still followed keeps the uses of the links it gave up",
            after.filter { it.links.isNotEmpty() }.all { it.total == FOLLOWERS.toLong() },
        )
        assertEquals(listOf("pair"), lookup.pairsAfter(LANGUAGE, "fresh").links.map { it.word })
    }

    @Test
    fun `a context the budget leaves with no link goes, as the personal dictionary drops it, and its next use starts it again`() {
        val pairs = (0 until PhraseBudget.LANGUAGE_CAP).map {
            UserBigram(LANGUAGE, "w${it / FOLLOWERS}", "x${it % FOLLOWERS}", 1, AT, 1L, it.toLong())
        }
        load(emptyList(), pairs, clocks = listOf(UserPhraseClock(LANGUAGE, PhraseBudget.LANGUAGE_CAP.toLong(), 0L)))
        val writes = commit("pair", "fresh")
        assertEquals("the stalest context gave up every link", FOLLOWERS, writes.gonePairs.count { it.previousWord == "w0" })
        assertEquals(PersonalLookup.ContextLinks(emptyList(), 0L, 0L), lookup.pairsAfter(LANGUAGE, "w0"))
        val context = commit("x0", "w0").pairContexts.single { it.previousWord == "w0" }
        assertEquals("its total and observations start again", listOf(1L, 1L), listOf(context.total, context.seen))
    }

    @Test
    fun `a word added by hand is one chosen use added by hand, counted in the language it is added in`() {
        load(emptyList())
        hold(lookup.added(OTHER, listOf("Casă"), AT))
        assertEquals(PersonalLookup.WordCounts(stored = 1, languages = mapOf(OTHER to 1), unplaced = 0), lookup.counts("casă"))
        assertEquals(
            PersonalSpelling.Merged("casă", "Casă", count = 1, deliberateCapitals = 1, asserted = 1, added = 1),
            lookup.heldWords().single { it.entry.key == "casă" }.entry,
        )
    }

    @Test
    fun `forgetting a word takes its links out of every language, their uses out of what they followed, and its contexts`() {
        load(listOf("the", "cat", "dog", "sat"))
        commit("cat", "the")
        commit("dog", "the")
        commit("sat", "cat", "the")
        commit("cat", "the", language = OTHER)
        val writes = lookup.forgetLinks("Cat")
        val none = PersonalLookup.ContextLinks(emptyList(), 0L, 0L)
        val after = lookup.pairsAfter(LANGUAGE, "the")
        assertEquals(listOf("dog"), after.links.map { it.word })
        assertEquals("the forgotten link's use is out of the total and the observations", listOf(1L, 1L), listOf(after.total, after.seen))
        assertEquals("a context it is part of goes", none, lookup.pairsAfter(LANGUAGE, "cat"))
        assertEquals(none, lookup.triplesAfter(LANGUAGE, "the", "cat"))
        assertEquals("a context left with no link goes", none, lookup.pairsAfter(OTHER, "the"))
        assertEquals(
            setOf(LANGUAGE to "the cat", LANGUAGE to "cat sat", OTHER to "the cat"),
            writes.gonePairs.mapTo(HashSet()) { it.language to "${it.previousWord} ${it.word}" },
        )
        assertEquals(listOf("sat"), writes.goneTriples.map { it.word })
        assertEquals(listOf(Triple(LANGUAGE, "the", 1L)), writes.pairContexts.map { Triple(it.language, it.previousWord, it.total) })
    }

    @Test
    fun `the budget gives up a full context's stalest newcomer, never the link just recorded nor one chosen on purpose`() {
        val limits = PhraseBudget.PAIRS
        val established = (0 until limits.established).map { "word$it" }
        val newcomers = (0 until limits.newcomers).map { "new$it" }
        load(listOf("the", "chosen", "newest") + established + newcomers)
        commit("chosen", "the", pairAsserted = true)
        for (word in established) {
            repeat(limits.promotion) { commit(word, "the") }
        }
        newcomers.forEach { commit(it, "the") }
        val writes = commit("newest", "the")
        assertEquals(listOf("new0"), writes.gonePairs.map { it.word })
        val held = lookup.pairsAfter(LANGUAGE, "the").links.map { it.word }
        assertEquals(limits.most + 1, held.size)
        assertTrue("the link just recorded stays", "newest" in held)
        assertTrue("and the chosen one, uncounted", "chosen" in held)
        assertEquals(
            "the context's total keeps the given up link's use",
            (1 + limits.established * limits.promotion + limits.newcomers + 1).toLong(),
            lookup.pairsAfter(LANGUAGE, "the").total,
        )
        assertFalse("a link given up is written no more", writes.pairs.any { it.word == "new0" })
    }

    @Test
    fun `the slice hands every link of the context in the offered order, the totals, the observations and a hop per first pair`() {
        load(listOf("on", "the", "cat", "dog", "sat", "ran", "fox"))
        repeat(3) { commit("cat", "the", "on") }
        repeat(2) { commit("dog", "the") }
        commit("fox", "the", tripleAsserted = false, pairAsserted = true)
        repeat(2) { commit("sat", "cat") }
        commit("ran", "cat")
        val id = { word: String -> lookup.heldWords().single { it.entry.key == word }.id }
        val slice = lookup.slice(LANGUAGE, "The", "On")
        assertEquals(
            listOf(0, id("the"), id("on"), 6, 3, 3, 1, 3),
            slice.header.toList(),
        )
        assertEquals(
            "the pairs, chosen first then the most used, then the triples",
            listOf(id("fox"), 1, 1, id("cat"), 3, 0, id("dog"), 2, 0, id("cat"), 3, 0),
            slice.links.toList(),
        )
        assertEquals(
            "each first pair's word with the pair after it and that context's total",
            listOf(SliceRows.NONE, 0, 0, 0, id("sat"), 2, 0, 3, SliceRows.NONE, 0, 0, 0),
            slice.hops.toList(),
        )
        assertEquals(
            "the observations of each context, then each link's",
            listOf(6L, 3L, 6L, 3L, 5L, 3L),
            slice.seen.toList(),
        )
        assertEquals(listOf(0, id("the"), id("on")), slice.key.toList())
    }

    @Test
    fun `a slice names the sentence start for no word before, none for a word not held, and each language by its own id`() {
        load(listOf("hello"))
        commit("hello", UserBigram.SENTENCE_START)
        val start = lookup.slice(LANGUAGE, null, null)
        assertEquals(1, start.header[SliceRows.PAIRS])
        assertEquals(SliceRows.NONE, start.header[SliceRows.CONTEXT2])
        val unheld = lookup.slice(LANGUAGE, "absent", "hello")
        assertEquals(listOf(SliceRows.NONE, 0), listOf(unheld.header[SliceRows.CONTEXT1], unheld.header[SliceRows.PAIRS]))
        val other = lookup.slice("ro", null, null)
        assertTrue(other.header[SliceRows.LANGUAGE] != start.header[SliceRows.LANGUAGE])
        assertEquals(start.header[SliceRows.LANGUAGE], lookup.slice(LANGUAGE, "hello", null).header[SliceRows.LANGUAGE])
        assertEquals(0, other.header[SliceRows.PAIRS])
    }

    private companion object {
        const val LANGUAGE = "en"
        const val OTHER = "ro"
        const val AT = 1_000L
        const val USES = 3

        /** The links after each context of a store loaded at its cap. */
        const val FOLLOWERS = 100
    }
}
