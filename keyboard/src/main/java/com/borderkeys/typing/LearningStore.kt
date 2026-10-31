// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.personal.LinkWrites

/** Where learned words, pairs and triples are kept. */
interface LearningStore {
    /** Writes [batch], off the typing flow's thread. */
    fun persist(batch: LearningBatch)

    /** Writes [batch], and returns once it is written. */
    suspend fun persistNow(batch: LearningBatch)

    /** Writes [batch], when there is one, and waits a bounded time for every write in flight. */
    fun persistBeforeShutdown(batch: LearningBatch?)

    /** Reads the heatmap's stored totals for [bucket], and hands them to [onLoaded] on the typing flow's thread. */
    fun loadTouches(bucket: String, onLoaded: (List<KeyTouch>) -> Unit)
}

/** What the learning buffer had accumulated, taken out of it in one go. */
class LearningBatch(
    val updates: List<LearnedWord>,
    /** The link rows the commits changed, as they hold now. */
    val links: LinkWrites,
    /** The heatmap's samples since the last write, as totals by bucket and letter. */
    val touches: List<KeyTouch> = emptyList(),
    /** The half-life [touches] are merged into what is stored under. */
    val touchHalfLifeMillis: Long = 0L,
) {
    /** Writes [updates], [links] and [touches] into [dictionary] in one transaction ([DictionaryRepository.applyLearning]). */
    suspend fun writeTo(dictionary: DictionaryRepository) =
        dictionary.applyLearning(updates, links, touches, touchHalfLifeMillis)
}
