// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.PhraseRecorder
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserPhraseClockDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext

/**
 * The link rows [pairUses] and [tripleUses] write as typing records them: a lookup loaded with
 * [pairs], [pairContexts], [triples], [tripleContexts] and [clocks], every word given an id as the
 * engine's word table would give it, then each use one record of its pair ([PersonalLookup.recordPair])
 * or triple ([PersonalLookup.recordTriple]), a choice when its [LearnedBigram.asserted] is above zero.
 */
fun linkUses(
    pairs: List<UserBigram>,
    pairContexts: List<UserPairContext>,
    triples: List<UserTrigram>,
    tripleContexts: List<UserTripleContext>,
    clocks: List<UserPhraseClock>,
    pairUses: List<LearnedBigram> = emptyList(),
    tripleUses: List<LearnedTrigram> = emptyList(),
): LinkWrites {
    val lookup = PersonalLookup()
    var nextId = 0
    fun hold(rows: WordRows) {
        lookup.assigned(rows, IntArray(rows.size) { if (rows.ids[it] >= 0) rows.ids[it] else nextId++ }, BooleanArray(rows.size))
    }
    hold(lookup.loadWords(emptyList(), pairs, triples))
    lookup.loadLinks(pairs, pairContexts, triples, tripleContexts, clocks)
    var writes = LinkWrites()
    for (use in pairUses) {
        hold(lookup.commit(use.language, LearnedWord(use.word, use.language, 0, use.lastUsedAt), use.previousWord, null))
        repeat(use.delta) {
            writes = writes.then(lookup.recordPair(use.language, use.previousWord, use.word, use.asserted > 0, use.lastUsedAt))
        }
    }
    for (use in tripleUses) {
        hold(lookup.commit(use.language, LearnedWord(use.word, use.language, 0, use.lastUsedAt), use.previousWord1, use.previousWord2))
        repeat(use.delta) {
            writes = writes.then(
                lookup.recordTriple(
                    use.language, use.previousWord2, use.previousWord1, use.word, use.asserted > 0, use.lastUsedAt,
                ),
            )
        }
    }
    return writes
}

/** [linkUses] on what [pairs], [triples] and [clocks] hold, written into them through [PhraseRecorder.write]. */
suspend fun recordUses(
    pairs: UserBigramDao,
    triples: UserTrigramDao,
    clocks: UserPhraseClockDao,
    pairUses: List<LearnedBigram> = emptyList(),
    tripleUses: List<LearnedTrigram> = emptyList(),
): LinkWrites {
    val writes = linkUses(
        pairs.all(), pairs.allContexts(), triples.all(), triples.allContexts(), clocks.all(), pairUses, tripleUses,
    )
    PhraseRecorder(pairs, triples, clocks).write(writes)
    return writes
}

/** [linkUses] on what the repository holds, not written. */
suspend fun DictionaryRepository.linkUses(
    pairUses: List<LearnedBigram> = emptyList(),
    tripleUses: List<LearnedTrigram> = emptyList(),
): LinkWrites {
    val rows = personalRows(Int.MAX_VALUE)
    return linkUses(rows.pairs, rows.pairContexts, rows.triples, rows.tripleContexts, rows.clocks, pairUses, tripleUses)
}

/** [linkUses] on what the repository holds, written through a learning flush ([DictionaryRepository.applyLearning]). */
suspend fun DictionaryRepository.recordUses(
    pairUses: List<LearnedBigram> = emptyList(),
    tripleUses: List<LearnedTrigram> = emptyList(),
): LinkWrites {
    val writes = linkUses(pairUses, tripleUses)
    applyLearning(emptyList(), writes)
    return writes
}
