// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.predict.AnswerScratch
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.CorrectionOffer
import com.borderkeys.predict.NativePredictor
import com.borderkeys.predict.NewestWins
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.predict.RefusedWords
import com.borderkeys.predict.answerRequest

/**
 * The engine on the host bridge. Each call is queued as the prediction thread would take it, and
 * [serveNext] runs the oldest, delivering its answer at once, as the main thread would receive it
 * before the next key.
 */
internal class QueuedEngine(
    private val handle: Long,
    private val languages: List<String>,
    private val refused: RefusedWords = RefusedWords.NONE,
) : EnginePort {

    /** Receives each answer to [requestSuggestions]. */
    var onSuggestions: (
        candidates: List<Candidate>,
        knownWord: String,
        knownWordExact: Boolean,
        knownWordIsName: Boolean,
        query: String,
        possessive: String?,
        corrections: List<CorrectionOffer>,
    ) -> Unit = { _, _, _, _, _, _, _ -> }

    private class Task(val request: Boolean, val run: () -> Unit)

    private val tasks = ArrayDeque<Task>()
    private val scratch = AnswerScratch()

    /** Each word [requestSuggestions] was asked about, in order. */
    val queries = mutableListOf<String>()

    /** The taps sent with each of [queries], x and y per code point, or null for none. */
    val tapsAsked = mutableListOf<Pair<List<Float>, List<Float>>?>()

    /** How the touch model was last set: whether learned patterns count, the weight, the taps a key needs. */
    data class TouchModelSetting(val learned: Boolean, val weight: Float, val minTaps: Int)

    var touchModel: TouchModelSetting? = null
        private set

    /** The learned touch patterns last handed over. */
    var touchPatterns: TouchPatterns? = null
        private set

    /** Runs the oldest queued call; false when none was queued. */
    fun serveNext(): Boolean {
        val task = tasks.removeFirstOrNull() ?: return false
        task.run()
        return true
    }

    override fun requestSuggestions(
        composing: String,
        previous1: String?,
        previous2: String?,
        tapXs: FloatArray?,
        tapYs: FloatArray?,
    ) {
        queries += composing
        tapsAsked += if (tapXs != null && tapYs != null) tapXs.toList() to tapYs.toList() else null
        tasks.addLast(
            Task(request = true) {
                val answer = answerRequest(
                    handle, composing, previous1, previous2, languages, scratch, tapXs, tapYs,
                )
                onSuggestions(
                    answer.candidates(refused), answer.knownWord, answer.knownWordExact,
                    answer.knownWordIsName, answer.query, answer.possessive,
                    answer.offers(refused),
                )
            },
        )
    }

    override fun learn(updates: List<LearnedWord>, previous1: String?, previous2: String?) {
        if (updates.isEmpty()) {
            return
        }
        tasks.addLast(
            Task(request = false) {
                for (update in updates) {
                    NativePredictor.nativeLearn(
                        handle, update.word, previous1, previous2, update.deliberateCapital,
                        update.asserted,
                    )
                }
            },
        )
    }

    override fun setPersonalModelEnabled(enabled: Boolean) {
        tasks.addLast(Task(request = false) { NativePredictor.nativeSetPersonalModelEnabled(handle, enabled) })
    }

    override fun setTouchModel(learned: Boolean, weight: Float, minTaps: Int) {
        touchModel = TouchModelSetting(learned, weight, minTaps)
        tasks.addLast(
            Task(request = false) { NativePredictor.nativeSetTouchModel(handle, learned, weight, minTaps) },
        )
    }

    override fun setTouchPatterns(patterns: TouchPatterns) {
        touchPatterns = patterns
        tasks.addLast(
            Task(request = false) {
                NativePredictor.nativeSetTouchPatterns(
                    handle, patterns.codes, patterns.taps, patterns.meanX, patterns.meanY,
                    patterns.varianceX, patterns.varianceY, patterns.covariance,
                )
            },
        )
    }

    override fun dominantLanguageTag(onResult: (String?) -> Unit) {
        tasks.addLast(Task(request = false) { onResult(NativePredictor.nativeDominantLanguageTag(handle)) })
    }

    override fun dominantPack(onResult: (Int) -> Unit) {
        tasks.addLast(Task(request = false) { onResult(NativePredictor.nativeDominantPack(handle)) })
    }

    override fun candidatesForPack(dominantPack: Int, words: List<String>, onResult: (List<String?>) -> Unit) {
        if (words.isEmpty()) {
            onResult(emptyList())
            return
        }
        tasks.addLast(
            Task(request = false) {
                onResult(words.map { NativePredictor.nativeCandidateForPack(handle, dominantPack, it) })
            },
        )
    }

    /** Drops the suggestion requests not yet served, and the swipe decode; the rest still runs. */
    override fun knownWords(words: List<String>, onResult: (List<Boolean>) -> Unit) {
        tasks.addLast(
            Task(request = false) {
                onResult(words.map { NativePredictor.nativeKnownSpelling(handle, it) != null })
            },
        )
    }

    override fun cancelPending() {
        tasks.removeAll { it.request }
        gestureRequests.cancel()
    }

    /** Receives each answer to [decodeGesture]. */
    var onGestureCandidates: (List<Candidate>) -> Unit = {}

    /** Receives each answer to [decodeGesturePreview]. */
    var onGesturePreviewCandidates: (List<Candidate>) -> Unit = {}

    /** Numbers the swipe and preview decodes; one cancelled or superseded is dropped. */
    private val gestureRequests = NewestWins()
    private val previewRequests = NewestWins()

    override fun decodeGesture(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    ) {
        if (count < 2) {
            return
        }
        val generation = gestureRequests.issue()
        val path = Path(xs.copyOf(count), ys.copyOf(count), timestamps.copyOf(count))
        tasks.addLast(
            Task(request = false) {
                val found = decode(path, previous1, previous2)
                if (gestureRequests.isNewest(generation)) {
                    onGestureCandidates(found)
                }
            },
        )
    }

    override fun decodeGesturePreview(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    ) {
        if (count < 2) {
            return
        }
        val generation = previewRequests.issue()
        val path = Path(xs.copyOf(count), ys.copyOf(count), timestamps.copyOf(count))
        tasks.addLast(
            Task(request = false) {
                val found = decode(path, previous1, previous2)
                if (previewRequests.isNewest(generation)) {
                    onGesturePreviewCandidates(found)
                }
            },
        )
    }

    override fun cancelPendingGesture() {
        gestureRequests.cancel()
    }

    override fun cancelPendingPreview() {
        previewRequests.cancel()
    }

    /** A swipe's samples, copied when the decode is asked for. */
    private class Path(val xs: FloatArray, val ys: FloatArray, val timestamps: LongArray)

    private val decodeWords = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
    private val decodeScores = FloatArray(PredictionEngine.MAX_RESULTS)
    private val decodeProperNoun = BooleanArray(PredictionEngine.MAX_RESULTS)

    /** The decode of [path], best first, the refused words left out. */
    private fun decode(path: Path, previous1: String?, previous2: String?): List<Candidate> {
        val found = NativePredictor.nativeDecodeGesture(
            handle, path.xs, path.ys, path.timestamps, path.xs.size, previous1, previous2,
            decodeWords, decodeScores, decodeProperNoun,
        )
        return (0 until found).mapNotNull { index ->
            decodeWords[index]?.let { Candidate(it, decodeProperNoun[index], decodeScores[index]) }
        }.filterNot { refused.refuses(it.text) }
    }
}
