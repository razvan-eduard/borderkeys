// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserCorrection
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.linkUses
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The repository on [MemoryDictionary]'s tables against the repository on Room's, both given the
 * same random writes: after every write, everything the repository reads back is the same.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class MemoryTablesMatchRoomTest {

    /** What the repository reads back, each table in an order of its own. */
    private data class Snapshot(
        val rows: PersonalRows,
        val contents: DictionaryRows,
        val wordsByCount: List<String>,
        val blocked: Set<String>,
        val touches: List<KeyTouch>,
        val phrases: List<UserPhrase>,
        val matching: List<String>,
    )

    private class Write(val name: String, val apply: suspend (DictionaryRepository) -> Unit)

    @Test
    fun `random writes leave the memory tables as Room leaves its own`() = runTest {
        for (seed in 0 until SEEDS) {
            val room = InMemoryDictionary(ApplicationProvider.getApplicationContext())
            val memory = MemoryDictionary()
            try {
                val random = Random(seed)
                var now = START
                repeat(WRITES) { step ->
                    now += random.nextLong(MOST_TIME_BETWEEN_WRITES)
                    val write = write(random, now)
                    write.apply(room.repository)
                    write.apply(memory.repository)
                    assertEquals(
                        "seed $seed, write $step (${write.name})",
                        snapshot(room.repository),
                        snapshot(memory.repository),
                    )
                }
            } finally {
                room.close()
            }
        }
    }

    @Test
    fun `each memory table's flow reads a write as it lands`() = runTest(UnconfinedTestDispatcher()) {
        val memory = MemoryDictionary()
        val repository = memory.repository
        val words = mutableListOf<List<String>>()
        val blocked = mutableListOf<List<String>>()
        val phrases = mutableListOf<Int>()
        val touches = mutableListOf<Int>()
        backgroundScope.launch { repository.words.collect { rows -> words += rows.map { it.word } } }
        backgroundScope.launch { repository.blocked.collect { rows -> blocked += rows.map { it.word } } }
        backgroundScope.launch { repository.phrases.collect { phrases += it.size } }
        backgroundScope.launch { repository.touches.collect { touches += it.size } }
        repository.add("nadia", LOCALES.first(), START, unblock = false)
        repository.addPhrase(listOf("hi", "nadia"), LOCALES.first(), START, unblock = false)
        repository.block("cat")
        repository.applyTouches(
            listOf(KeyTouch(BUCKETS.first(), CODES.first(), 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, KEY_SIZE, KEY_SIZE, DENSITY, START)),
            HALF_LIFE,
        )
        assertEquals(listOf("nadia", "hi"), words.last())
        assertEquals(listOf("cat"), blocked.last())
        assertTrue(phrases.toString(), phrases.last() > 0)
        assertEquals(1, touches.last())
    }

    private suspend fun snapshot(repository: DictionaryRepository) = Snapshot(
        rows = repository.personalRows(LIMIT).let { rows -> rows.copy(words = rows.words.sortedWith(compareBy({ it.word }, { it.language }))) },
        contents = repository.contents().let { rows ->
            rows.copy(
                words = rows.words.sortedWith(compareBy({ it.word }, { it.language })),
                corrections = rows.corrections.sortedWith(compareBy({ it.language }, { it.key }, { it.previousWord }, { it.word })),
                blocked = rows.blocked.sorted(),
            )
        },
        wordsByCount = repository.topWords(LIMIT).map { it.word },
        blocked = repository.blockedWordSet(),
        touches = repository.touches.first().sortedWith(compareBy({ it.bucket }, { it.code })),
        phrases = repository.phrases.first(),
        matching = repository.search(SEARCHED).first().map { it.word },
    )

    /** One write drawn from every kind the repository makes, at [now]. */
    private fun write(random: Random, now: Long): Write {
        val word = WORDS.random(random)
        val other = WORDS.random(random)
        val locale = LOCALES.random(random)
        val language = locale.substringBefore('-')
        return when (random.nextInt(KINDS)) {
            0, 1, 2 -> {
                val updates = List(1 + random.nextInt(MOST_PER_FLUSH)) {
                    LearnedWord(
                        WORDS.random(random), language, 1 + random.nextInt(MOST_USES), now,
                        deliberateCapital = random.nextBoolean(), asserted = random.nextInt(MOST_USES),
                    )
                }
                val pair = LearnedBigram(language, PhraseKey.word(word), PhraseKey.word(other), 1, now)
                val triple =
                    LearnedTrigram(language, PhraseKey.word(other), PhraseKey.word(word), PhraseKey.word(other), 1, now)
                Write("learn $updates") {
                    it.applyLearning(updates, it.linkUses(pairUses = listOf(pair), tripleUses = listOf(triple)))
                }
            }
            3 -> {
                val unblock = random.nextBoolean()
                Write("add $word, unblocking $unblock") { it.add(word, locale, now, unblock) }
            }
            4 -> Write("add phrase $word $other") { it.addPhrase(listOf(word, other), locale, now, unblock = false) }
            5 -> Write("forget $word") { it.forget(word) }
            6 -> Write("forget every case of $word") { it.forgetEveryCase(word) }
            7 -> Write("block $word") { it.block(word) }
            8 -> Write("unblock $word") { it.unblock(word) }
            9 -> Write("decay") { it.decayStaleEntries(LIMIT, HALF_LIFE, UNCONFIRMED_LIFE, now) }
            10 -> {
                val keep = random.nextInt(MOST_KEPT)
                Write("keep $keep") { it.keepWords(keep) }
            }
            11 -> Write("purge") { repository -> repository.purgeUnlearnable { words -> words.map { it.length > SHORTEST_LEARNABLE } } }
            12 -> Write("forget a phrase") { repository -> repository.phrases.first().firstOrNull()?.let { repository.forgetPhrase(it) } }
            13 -> {
                val touch = KeyTouch(
                    BUCKETS.random(random), CODES.random(random), random.nextDouble(), random.nextDouble(),
                    random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble(),
                    KEY_SIZE, KEY_SIZE, DENSITY, now,
                )
                Write("touch $touch") { it.applyTouches(listOf(touch), HALF_LIFE) }
            }
            14 -> Write("forget the touches") { it.forgetTouchPattern() }
            15 -> {
                val restored = DictionaryRows(
                    words = listOf(
                        UserWord(
                            word, if (random.nextBoolean()) language else UserWord.UNPLACED, 1 + random.nextInt(MOST_USES), now,
                            random.nextInt(MOST_USES), random.nextInt(MOST_USES), random.nextInt(MOST_USES),
                        ),
                    ),
                    pairs = listOf(UserBigram(language, PhraseKey.word(word), PhraseKey.word(other), 1 + random.nextInt(MOST_USES), now, 0L, 0L)),
                    pairContexts = listOf(UserPairContext(language, PhraseKey.word(word), random.nextLong(MOST_TOTAL), now, random.nextLong(MOST_TOTAL))),
                    corrections = listOf(
                        UserCorrection(language, PhraseKey.word(other).reversed(), PhraseKey.word(word), PhraseKey.word(other), 1 + random.nextInt(MOST_USES), now),
                    ),
                    blocked = if (random.nextInt(MOST_USES) == 0) listOf(other) else emptyList(),
                )
                // Twice: the second merges into what the first wrote.
                Write("restore $restored, twice") {
                    it.restore(restored)
                    it.restore(restored)
                }
            }
            else -> Write("forget everything") { it.forgetEverything() }
        }
    }

    private companion object {
        const val SEEDS = 20
        const val WRITES = 60
        const val KINDS = 17
        const val START = 1_000_000L
        const val MOST_TIME_BETWEEN_WRITES = 5_000_000L
        const val HALF_LIFE = 3_000_000L
        const val UNCONFIRMED_LIFE = 8_000_000L
        const val LIMIT = 1_000
        const val MOST_PER_FLUSH = 3
        const val MOST_USES = 3
        const val MOST_KEPT = 6
        const val MOST_TOTAL = 10L
        const val SHORTEST_LEARNABLE = 3
        /** A letter the words hold in both cases. */
        const val SEARCHED = "c"
        const val KEY_SIZE = 100f
        const val DENSITY = 2f
        val WORDS = listOf("cat", "Cat", "dog", "paris", "Paris", "hi", "nadia", "ă")
        val LOCALES = listOf("en-US", "ro-RO")
        val BUCKETS = listOf("portrait", "landscape")
        val CODES = listOf('a'.code, 'b'.code)
    }
}
