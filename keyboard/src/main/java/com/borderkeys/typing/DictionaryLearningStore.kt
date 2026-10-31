// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.OrderedWorker
import com.borderkeys.data.entity.KeyTouch

/**
 * The [LearningStore] on the personal dictionary, [dictionary]: each batch written on [worker]
 * after every read and write sent before it, then [afterWrite]; the heatmap totals read are handed
 * to the typing flow's thread through [post], which waits for none.
 */
class DictionaryLearningStore(
    private val worker: OrderedWorker,
    private val dictionary: () -> DictionaryRepository,
    private val post: (Runnable) -> Unit,
    private val afterWrite: suspend () -> Unit = {},
) : LearningStore {

    override fun persist(batch: LearningBatch) {
        worker.post { write(batch) }
    }

    /** A failed write is logged. */
    override suspend fun persistNow(batch: LearningBatch) {
        runCatching { worker.run { write(batch) } }
            .onFailure { error -> android.util.Log.e(LOG_TAG, "learning flush failed", error) }
    }

    /**
     * Sends [batch], when there is one, after the writes in flight, then the worker's teardown,
     * waited for at most [FINAL_FLUSH_TIMEOUT_MILLIS]; past it the worker finishes them on its own.
     */
    override fun persistBeforeShutdown(batch: LearningBatch?) {
        if (batch != null) {
            worker.post { write(batch) }
        }
        if (!worker.shutdown(FINAL_FLUSH_TIMEOUT_MILLIS)) {
            android.util.Log.w(LOG_TAG, "the final learning flush outlasted its wait")
        }
    }

    /** A failed read is logged, and loads no totals. */
    override fun loadTouches(bucket: String, onLoaded: (List<KeyTouch>) -> Unit) {
        worker.post {
            val rows = runCatching { dictionary().touchesIn(bucket) }
                .onFailure { error -> android.util.Log.e(LOG_TAG, "heatmap load failed", error) }
                .getOrDefault(emptyList())
            post { onLoaded(rows) }
        }
    }

    private suspend fun write(batch: LearningBatch) {
        batch.writeTo(dictionary())
        afterWrite()
    }

    companion object {
        /** How long the main thread waits for the last flush at shutdown, at most. */
        const val FINAL_FLUSH_TIMEOUT_MILLIS = 2_000L

        private const val LOG_TAG = "BorderKeys"
    }
}
