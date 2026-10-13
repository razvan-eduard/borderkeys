// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import androidx.room.withTransaction
import com.borderkeys.data.BorderKeysDatabase
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.MemoryClocks
import com.borderkeys.data.MemoryPairs
import com.borderkeys.data.MemoryTriples
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A dictionary backup gathered on one device and applied on another, through the file. */
class BackupRepositoryTest {

    /** One device's phrase tables, behind a database whose other tables hold nothing. */
    private class Device {
        val pairs = MemoryPairs()
        val triples = MemoryTriples()
        val clocks = MemoryClocks()
        val database = mockk<BorderKeysDatabase>(relaxed = true).also {
            every { it.userBigramDao() } returns pairs
            every { it.userTrigramDao() } returns triples
            every { it.userPhraseClockDao() } returns clocks
            coEvery { it.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        }
        val dictionary = DictionaryRepository(
            database, mockk(relaxed = true), mockk(relaxed = true), pairs, triples, mockk(relaxed = true), clocks,
        )
        val backups = BackupRepository(database, mockk(relaxed = true), dictionary, { FALLBACK }, { NOW })
    }

    @Before
    fun open() {
        mockkStatic(ROOM_DATABASE_KT)
    }

    @After
    fun close() {
        unmockkStatic(ROOM_DATABASE_KT)
    }

    /** A device holding one Romanian pair, used before [NOW], whose context counts uses past it. */
    private suspend fun source(): Device = Device().also {
        it.pairs.upsert(UserBigram("ro", "o", "cana", LINK_USES, USED_AT, contextSeen = SEEN, languageSeen = CLOCK))
        it.pairs.upsertContext(UserPairContext("ro", "o", CONTEXT_TOTAL, USED_AT, SEEN))
        it.clocks.upsert(UserPhraseClock("ro", CLOCK, 0L))
    }

    private suspend fun throughFile(payload: BackupPayload): BackupPayload =
        BackupFile.read(BackupFile.write(payload, PASSPHRASE), PASSPHRASE).payload!!

    @Test
    fun `a restore keeps each link's language and last use and each context's uses, and moves no clock`() = runTest {
        val payload = throughFile(source().backups.gather(DICTIONARY))
        val target = Device()
        target.backups.apply(payload, DICTIONARY)
        val pair = target.pairs.rows.getValue(listOf("ro", "o", "cana"))
        assertEquals(LINK_USES, pair.count)
        assertEquals(USED_AT, pair.lastUsedAt)
        assertEquals(CONTEXT_TOTAL, target.pairs.contexts.getValue(listOf("ro", "o")).total)
        assertTrue("the device's clocks are not taken from the file", target.clocks.rows.isEmpty())
    }

    @Test
    fun `a file without stamps or contexts restores its links as used now, each context its links' uses`() = runTest {
        val written = throughFile(source().backups.gather(DICTIONARY))
        val older = written.copy(
            bigrams = written.bigrams.map { BackupBigram(it.previous, it.word, it.count, it.language) },
            pairContexts = emptyList(),
            tripleContexts = emptyList(),
            phraseClocks = emptyList(),
        )
        val target = Device()
        target.backups.apply(older, DICTIONARY)
        assertEquals(NOW, target.pairs.rows.getValue(listOf("ro", "o", "cana")).lastUsedAt)
        assertEquals(LINK_USES.toLong(), target.pairs.contexts.getValue(listOf("ro", "o")).total)
    }

    private companion object {
        const val ROOM_DATABASE_KT = "androidx.room.RoomDatabaseKt"
        val DICTIONARY = BackupRepository.Parts(dictionary = true)
        const val PASSPHRASE = "correct horse"
        const val FALLBACK = "en"
        const val NOW = 1_000L
        const val USED_AT = 400L
        const val LINK_USES = 4
        const val CONTEXT_TOTAL = 6L
        const val SEEN = 9L
        const val CLOCK = 11L
    }
}
