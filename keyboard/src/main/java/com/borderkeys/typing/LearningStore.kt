// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.PhraseRecorder
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram

/** Where learned words, pairs and triples are kept. */
interface LearningStore {
    /**
     * Writes [batch], off the typing flow's thread, and hands the pairs and triples the store
     * gave up under its budget beyond the engine's to [onDropped] on the typing flow's thread.
     */
    fun persist(batch: LearningBatch, onDropped: (List<UserBigram>, List<UserTrigram>) -> Unit)

    /** Writes [batch], when there is one, and waits a bounded time for every write in flight. */
    fun persistBeforeShutdown(batch: LearningBatch?)

    /** Reads the heatmap's stored totals for [bucket], and hands them to [onLoaded] on the typing flow's thread. */
    fun loadTouches(bucket: String, onLoaded: (List<KeyTouch>) -> Unit)
}

/** What the learning buffer had accumulated, taken out of it in one go. */
class LearningBatch(
    val updates: List<LearnedWord>,
    /** Each pair use the engine recorded, with what it dropped right after, in order. */
    val pairs: List<PairStep>,
    /** [pairs], for the triples. */
    val triples: List<TripleStep>,
    /** The heatmap's samples since the last write, as totals by bucket and letter. */
    val touches: List<KeyTouch> = emptyList(),
    /** The half-life [touches] are merged into what is stored under. */
    val touchHalfLifeMillis: Long = 0L,
) {
    /**
     * Writes [updates], [pairs] and [triples] into [dictionary] ([DictionaryRepository.applyLearning]);
     * returns the pairs and triples it gave up beyond the engine's.
     */
    suspend fun writeTo(dictionary: DictionaryRepository): PhraseRecorder.Dropped =
        dictionary.applyLearning(updates, pairs, triples)
}
