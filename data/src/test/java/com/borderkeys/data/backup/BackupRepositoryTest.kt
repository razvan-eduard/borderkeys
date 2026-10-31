// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import com.borderkeys.data.DictionaryRows
import com.borderkeys.data.MemoryDictionary
import com.borderkeys.data.SteppedExecutor
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserCorrection
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.PersonalModelHost
import com.borderkeys.data.personal.WorkingCopy
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dictionary backup gathered on one device and applied on another, through the file. */
class BackupRepositoryTest {

    /** One device's personal dictionary in memory, its database's other tables holding nothing. */
    private class Device(thread: SteppedExecutor? = null) {
        val tables = if (thread == null) MemoryDictionary() else MemoryDictionary(thread)
        val dictionary = tables.repository
        val backups = BackupRepository(mockk(relaxed = true), mockk(relaxed = true), tables.repository, { FALLBACK }, { NOW })

        /** Everything the personal dictionary holds, each kind in an order of its own. */
        suspend fun contents(): DictionaryRows = dictionary.contents().let { rows ->
            rows.copy(
                words = rows.words.sortedWith(compareBy({ it.word }, { it.language })),
                pairs = rows.pairs.sortedWith(compareBy({ it.language }, { it.previousWord }, { it.word })),
                pairContexts = rows.pairContexts.sortedWith(compareBy({ it.language }, { it.previousWord })),
                triples = rows.triples.sortedWith(compareBy({ it.language }, { it.previousWord1 }, { it.word })),
                tripleContexts = rows.tripleContexts.sortedWith(compareBy({ it.language }, { it.previousWord1 })),
                corrections = rows.corrections.sortedWith(compareBy({ it.language }, { it.key }, { it.word })),
                blocked = rows.blocked.sorted(),
            )
        }
    }

    /**
     * A device holding a word in two languages and one of no language known, a Romanian pair and
     * triple chosen on purpose with their contexts, a correction and a blocked word.
     */
    private suspend fun source(): Device = Device().also {
        it.dictionary.applyLearning(
            listOf(
                LearnedWord("Pizza", "en", 3, USED_AT, deliberateCapital = true),
                LearnedWord("Pizza", "ro", 2, USED_AT, asserted = 1),
            ),
            LinkWrites(
                pairs = listOf(UserBigram("ro", "o", "cana", LINK_USES, USED_AT, SEEN, CLOCK, asserted = 1, added = 1)),
                triples = listOf(UserTrigram("ro", "vreau", "o", "cana", LINK_USES, USED_AT, SEEN, CLOCK, asserted = 1)),
                pairContexts = listOf(UserPairContext("ro", "o", CONTEXT_TOTAL, USED_AT, SEEN)),
                tripleContexts = listOf(UserTripleContext("ro", "vreau", "o", CONTEXT_TOTAL, USED_AT, SEEN)),
                clocks = listOf(UserPhraseClock("ro", CLOCK, CLOCK)),
            ),
        )
        it.dictionary.add("Kelpa", "en-US", USED_AT, unblock = false)
        it.tables.words.merge(UserWord("acasă", UserWord.UNPLACED, 2, USED_AT))
        it.tables.corrections.merge(UserCorrection("ro", "cnaa", "o", "cana", 2, USED_AT))
        it.dictionary.block("spam")
    }

    private fun throughFile(payload: BackupPayload): BackupPayload =
        BackupFile.read(BackupFile.write(payload, PASSPHRASE), PASSPHRASE).payload!!

    @Test
    fun `a file carries every word in its language with its counts, stamp and marks, and every link, context, correction and block`() = runTest {
        val source = source()
        val target = Device()
        val applied = target.backups.apply(throughFile(source.backups.gather(DICTIONARY)), DICTIONARY)
        val held = source.contents()
        val restored = target.contents()
        assertEquals(held.words, restored.words)
        assertEquals(held.pairContexts, restored.pairContexts)
        assertEquals(held.tripleContexts, restored.tripleContexts)
        assertEquals(held.corrections, restored.corrections)
        assertEquals(held.blocked, restored.blocked)
        assertEquals("the links, stamped at this device's clock", held.pairs.map { it.copy(languageSeen = 0L) }, restored.pairs)
        assertEquals(held.triples.map { it.copy(languageSeen = 0L) }, restored.triples)
        assertEquals(listOf(4, 1, 1, 1, 0, 0), applied.restoredCounts())
    }

    @Test
    fun `restoring a file twice changes nothing`() = runTest {
        val payload = throughFile(source().backups.gather(DICTIONARY))
        val target = Device()
        target.backups.apply(payload, DICTIONARY)
        val once = target.contents()
        target.backups.apply(payload, DICTIONARY)
        assertEquals(once, target.contents())
    }

    @Test
    fun `a restore keeps the larger of each count and the later of each stamp, the device's or the file's`() = runTest {
        val payload = throughFile(source().backups.gather(DICTIONARY))
        val target = Device()
        val later = USED_AT + 1
        target.dictionary.applyLearning(
            listOf(LearnedWord("Pizza", "en", 1, later), LearnedWord("Pizza", "ro", 9, USED_AT - 1)),
            LinkWrites(
                pairs = listOf(UserBigram("ro", "o", "cana", 1, later, SEEN + 1, 0L)),
                pairContexts = listOf(UserPairContext("ro", "o", CONTEXT_TOTAL - 1, later, SEEN + 1)),
            ),
        )
        target.backups.apply(payload, DICTIONARY)
        val restored = target.contents()
        val words = restored.words.associateBy { it.word to it.language }
        assertEquals("the file's count and capitals, this device's later use", UserWord("Pizza", "en", 3, later, 1, 0, 0), words.getValue("Pizza" to "en"))
        assertEquals("this device's count, the file's later use and choice", UserWord("Pizza", "ro", 9, USED_AT, 0, 1, 0), words.getValue("Pizza" to "ro"))
        assertEquals(UserBigram("ro", "o", "cana", LINK_USES, later, SEEN + 1, 0L, asserted = 1, added = 1), restored.pairs.single())
        assertEquals(UserPairContext("ro", "o", CONTEXT_TOTAL, later, SEEN + 1), restored.pairContexts.single())
    }

    @Test
    fun `an entry that cannot be read is left out and reported, wherever it stands, and the rest restored`() = runTest {
        val good = BackupWord("salut", "ro", 2, USED_AT)
        val payload = BackupPayload(
            words = listOf(
                BackupWord("nothing", "ro", 0, USED_AT),
                BackupWord("salut", "ro-RO", 1, USED_AT),
                BackupWord("x", "ro", 1, USED_AT),
                good,
                BackupWord("later", "ro", 1, -1L),
            ),
            bigrams = listOf(BackupBigram("same", "Same", 1, "ro", USED_AT), BackupBigram("o", "cana", 1, "", USED_AT)),
            pairContexts = listOf(BackupPairContext("ro", "o", total = -1L, seen = 1L, lastUsedAt = USED_AT)),
            corrections = listOf(BackupCorrection("ro", "cnaa", "o", "cana", 0, USED_AT)),
            blocked = listOf(""),
        )
        val target = Device()
        val applied = target.backups.apply(throughFile(payload), DICTIONARY)
        assertEquals(listOf(UserWord("salut", "ro", 2, USED_AT)), target.contents().words)
        assertTrue(target.contents().pairs.isEmpty() && target.contents().corrections.isEmpty())
        assertTrue(target.contents().blocked.isEmpty())
        assertEquals("unreadable", 9, applied.leftOutUnreadable)
        assertEquals(0, applied.leftOutBlocked)
    }

    @Test
    fun `a stamp after this device's now is taken as now`() = runTest {
        val target = Device()
        target.backups.apply(throughFile(BackupPayload(words = listOf(BackupWord("salut", "ro", 2, NOW + 1)))), DICTIONARY)
        assertEquals(NOW, target.contents().words.single().lastUsedAt)
    }

    @Test
    fun `a word this device blocks is refused in any case, with the links and corrections naming it, and reported`() = runTest {
        val payload = throughFile(source().backups.gather(DICTIONARY))
        val target = Device()
        target.dictionary.block("CANA")
        target.dictionary.block("pizza")
        val applied = target.backups.apply(payload, DICTIONARY)
        val restored = target.contents()
        assertEquals(listOf("Kelpa", "acasă"), restored.words.map { it.word })
        assertTrue(restored.pairs.isEmpty() && restored.triples.isEmpty() && restored.corrections.isEmpty())
        assertEquals("two rows of Pizza, the pair, the triple and the correction", 5, applied.leftOutBlocked)
    }

    @Test
    fun `a file's block blocks the word here and forgets what this device learned of it`() = runTest {
        val target = Device()
        target.dictionary.applyLearning(
            listOf(LearnedWord("spam", "en", 4, USED_AT), LearnedWord("eggs", "en", 1, USED_AT)),
            LinkWrites(
                pairs = listOf(UserBigram("en", "spam", "eggs", 1, USED_AT, 1L, 1L)),
                pairContexts = listOf(UserPairContext("en", "spam", 1L, USED_AT, 1L)),
            ),
        )
        target.backups.apply(throughFile(BackupPayload(blocked = listOf("spam"))), DICTIONARY)
        assertTrue(target.dictionary.isBlocked("spam"))
        assertEquals(listOf("eggs"), target.contents().words.map { it.word })
        assertTrue(target.contents().pairs.isEmpty())
    }

    @Test
    fun `a format 2 file, as the released version wrote it, restores its words unplaced and its links in their words' languages`() = runTest {
        val target = Device()
        val applied = target.backups.apply(fixture("backup-format2.json"), DICTIONARY)
        assertEquals(
            listOf(UserWord("Nadia", UserWord.UNPLACED, 5, 1000L, 2, 0, 0), UserWord("salut", UserWord.UNPLACED, 2, 2000L, 0, 0, 0)),
            target.contents().words,
        )
        assertEquals(
            listOf("en hi nadia 3 $NOW", "ro spune salut 2 $NOW"),
            target.contents().pairs.map { "${it.language} ${it.previousWord} ${it.word} ${it.count} ${it.lastUsedAt}" },
        )
        assertEquals(listOf("en say hi nadia"), target.contents().triples.map { "${it.language} ${it.previousWord2} ${it.previousWord1} ${it.word}" })
        assertEquals("each context its links' uses", listOf(3L, 2L), target.contents().pairContexts.map { it.total })
        assertTrue(target.dictionary.isBlocked("spam"))
        assertEquals(listOf(2, 2, 1, 0, 0, 0), applied.restoredCounts())
    }

    @Test
    fun `a format 3 file restores its words unplaced, chosen only as added by hand, and takes no clock or language stamp from it`() = runTest {
        val target = Device()
        target.backups.apply(fixture("backup-format3.json"), DICTIONARY)
        assertEquals(
            listOf(UserWord("Kelpa", UserWord.UNPLACED, 2, 1200L, 1, 1, 1), UserWord("Nadia", UserWord.UNPLACED, 5, 1000L, 2, 0, 0)),
            target.contents().words,
        )
        assertEquals(
            listOf(UserBigram("ro", "o", "cana", 4, 1500L, 9L, 0L, asserted = 1, added = 1)),
            target.contents().pairs,
        )
        assertEquals(listOf(UserPairContext("ro", "o", 6L, 1500L, 9L)), target.contents().pairContexts)
        assertEquals("no clock taken from the file", 0L, target.tables.clocks.rows["ro"]?.pairs ?: 0L)
    }

    @Test
    fun `a file holding only learned corrections or context totals is offered as a dictionary`() {
        val backups = Device().backups
        val corrections = BackupPayload(corrections = listOf(BackupCorrection("ro", "cnaa", "o", "cana", 2, USED_AT)))
        val pairContexts = BackupPayload(pairContexts = listOf(BackupPairContext("ro", "o", CONTEXT_TOTAL, SEEN, USED_AT)))
        val tripleContexts =
            BackupPayload(tripleContexts = listOf(BackupTripleContext("ro", "vreau", "o", CONTEXT_TOTAL, SEEN, USED_AT)))
        for (payload in listOf(corrections, pairContexts, tripleContexts)) {
            assertTrue(payload.toString(), backups.contentsOf(payload).dictionary)
        }
        assertFalse("an empty file is no dictionary", backups.contentsOf(BackupPayload()).dictionary)
    }

    /** A running keyboard's personal model that runs each write and edit at once, and records each reload with the pairs then held. */
    private class RecordingHost(private val device: Device) : PersonalModelHost {
        val reloads = mutableListOf<Int>()

        override suspend fun <T> update(
            write: suspend () -> T,
            forgotten: Collection<String>,
            change: (WorkingCopy, T) -> LinkWrites,
        ): T = write()

        override suspend fun <T> reload(edit: suspend () -> T): T = edit().also { reloads += device.tables.pairs.rows.size }

        override suspend fun blockedChanged() = Unit

        override suspend fun forgetTouches(write: suspend () -> Unit) = write()
    }

    @Test
    fun `a restore is written as a reload of the running keyboard's personal model`() = runTest {
        val payload = throughFile(source().backups.gather(DICTIONARY))
        val target = Device()
        val host = RecordingHost(target)
        target.dictionary.attach(host)
        target.backups.apply(payload, DICTIONARY)
        assertEquals("one reload, the pair restored by then", listOf(1), host.reloads)
    }

    @Test
    fun `a backup is gathered after every write asked for before it`() = runTest {
        val thread = SteppedExecutor()
        val device = Device(thread)
        val adding = launch(start = CoroutineStart.UNDISPATCHED) { device.dictionary.add("Kelpa", "en-US", USED_AT, unblock = false) }
        val gathering = async(start = CoroutineStart.UNDISPATCHED) { device.backups.gather(DICTIONARY) }
        thread.drain()
        adding.join()
        assertEquals(listOf("Kelpa"), gathering.await().words.map { it.word })
    }

    /** The file [name] in the test resources, read as the backup screen reads one. */
    private fun fixture(name: String): BackupPayload =
        BackupFile.read(javaClass.getResource("/$name")!!.readText(), passphrase = "").payload!!

    /** What [BackupRepository.Applied] says was restored: words, pairs, triples, corrections, and left out. */
    private fun BackupRepository.Applied.restoredCounts() = listOf(words, pairs, triples, corrections, leftOutBlocked, leftOutUnreadable)

    private companion object {
        val DICTIONARY = BackupRepository.Parts(dictionary = true)
        const val PASSPHRASE = "correct horse"
        const val FALLBACK = "en"
        const val NOW = 10_000L
        const val USED_AT = 400L
        const val LINK_USES = 4
        const val CONTEXT_TOTAL = 6L
        const val SEEN = 9L
        const val CLOCK = 11L
    }
}
