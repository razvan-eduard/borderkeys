// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord

/** Where learned words, pairs and triples are kept. */
interface LearningStore {
    /** Writes [batch], off the typing flow's thread. */
    fun persist(batch: LearningBatch)

    /** Writes [batch], when there is one, and waits a bounded time for every write in flight. */
    fun persistBeforeShutdown(batch: LearningBatch?)
}

/** What the learning buffer had accumulated, taken out of it in one go. */
class LearningBatch(
    val updates: List<LearnedWord>,
    val pairs: List<LearnedBigram>,
    val triples: List<LearnedTrigram>,
)
