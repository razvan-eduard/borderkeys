// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.content.res.AssetFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.Trace
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import com.borderkeys.ime.WordStems
import com.borderkeys.typing.EnginePort

/**
 * Owns the native engine and the one thread it runs on. Every native call goes through
 * [withHandle] on that thread, answers are delivered on the main looper, and an answer older
 * than the newest request is dropped.
 */
class PredictionEngine(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : EnginePort {
    /** Receives the engine's answers on the UI thread. */
    interface ResultListener {
        /**
         * [query] is the composing text the answer is about, as it was asked. [knownWord] is the
         * dictionaries' spelling of [query], or empty when they do not hold it.
         */
        fun onSuggestions(
            candidates: List<Candidate>,
            knownWord: String,
            query: String,
            possessive: String?,
            inflection: Boolean,
        )

        /** A decoded swipe, best first. */
        fun onGestureCandidates(candidates: List<Candidate>)

        /** A decode of a swipe still in progress, from [decodeGesturePreview]. */
        fun onGesturePreviewCandidates(candidates: List<Candidate>)
    }

    var listener: ResultListener? = null

    /** How long the engine took over the last swipe, in microseconds. */
    @Volatile
    var lastGestureDecodeMicros: Long = 0L

    /** Whether the last gesture decode went through the neural decoder. */
    @Volatile
    var lastGestureUsedNeural: Boolean = false
        private set

    private val lock = Any()
    private var handle: Long = 0L
    private var released = false

    private val thread = HandlerThread("borderkeys-predict", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var worker: Handler

    private val queue = PredictionRequestQueue()

    /** The tags handed to [setActiveLanguages], read on the worker for [WordStems]. */
    private var activeTags: List<String> = emptyList()

    /** The last answer served, published on the UI thread. Guarded by [resultLock]. */
    private var latestAnswer: PredictionAnswer? = null
    private val resultLock = Any()

    /** The prediction thread's buffers for [answerRequest]. */
    private val scratch = AnswerScratch()

    /** Words dropped from every answer. */
    @Volatile
    private var refused: RefusedWords = RefusedWords.NONE

    /** Scratch for pushing key geometry down. Sized once for the largest layout. */
    private val geometryCodes = IntArray(MAX_KEYS)
    private val geometryX = FloatArray(MAX_KEYS)
    private val geometryY = FloatArray(MAX_KEYS)

    // A copy of the view's capture buffers, taken before the gesture crosses threads.
    private val gestureX = FloatArray(MAX_GESTURE_POINTS)
    private val gestureY = FloatArray(MAX_GESTURE_POINTS)
    private val gestureTime = LongArray(MAX_GESTURE_POINTS)
    private var gestureCount = 0
    private val gestureLock = Any()

    private val workerLoop = Runnable { serveRequests() }
    private val publishResults = Runnable { publish() }

    // The finished swipe's result buffers, separate from the typed ones.
    private val gestureResultLock = Any()
    private val gestureNativeWords = arrayOfNulls<String>(MAX_RESULTS)
    private val gestureNativeScores = FloatArray(MAX_RESULTS)
    private val gestureNativeProperNoun = BooleanArray(MAX_RESULTS)

    private var gestureNativeCount = 0

    // The decode's own scratch, filled without a lock and copied under [gestureResultLock].
    private val decodeWords = arrayOfNulls<String>(MAX_RESULTS)
    private val decodeScores = FloatArray(MAX_RESULTS)
    private val decodeProperNoun = BooleanArray(MAX_RESULTS)

    /** Numbers the swipe decodes; one cancelled or superseded is dropped. UI thread only. */
    private val gestureRequests = NewestWins()

    // ---- swipe-preview path (radial menu) --------------------------------------------------
    //
    // Its own buffers, separate from the gesture fields above.
    private val previewGestureX = FloatArray(MAX_GESTURE_POINTS)
    private val previewGestureY = FloatArray(MAX_GESTURE_POINTS)
    private val previewGestureTime = LongArray(MAX_GESTURE_POINTS)
    private var previewGestureCount = 0
    private val previewGestureLock = Any()

    private val previewResultLock = Any()
    private val previewNativeWords = arrayOfNulls<String>(MAX_RESULTS)
    private val previewNativeScores = FloatArray(MAX_RESULTS)
    /** The proper-noun bit of each preview word. */
    private val previewNativeProperNoun = BooleanArray(MAX_RESULTS)
    private var previewNativeCount = 0

    /** Numbers the preview decodes; one cancelled or superseded is dropped. UI thread only. */
    private val previewRequests = NewestWins()

    fun start(): Boolean {
        thread.start()
        worker = Handler(thread.looper)
        val created = NativePredictor.nativeCreate()
        synchronized(lock) {
            handle = created
            released = false
        }
        return created != 0L
    }

    /**
     * Releases the native engine: zeroes the handle under the lock, then destroys the engine and
     * stops the thread from the prediction thread.
     */
    fun shutdown() {
        val toDestroy: Long
        synchronized(lock) {
            if (released) {
                return
            }
            released = true
            toDestroy = handle
            handle = 0L
        }
        queue.clear()
        if (::worker.isInitialized) {
            worker.removeCallbacksAndMessages(null)
            worker.post {
                if (toDestroy != 0L) {
                    NativePredictor.nativeDestroy(toDestroy)
                }
                thread.quitSafely()
            }
        } else if (toDestroy != 0L) {
            NativePredictor.nativeDestroy(toDestroy)
        }
        mainHandler.removeCallbacks(publishResults)
        // Drops the gesture and preview answers still in flight.
        previewRequests.cancel()
        gestureRequests.cancel()
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val current = synchronized(lock) { if (released) 0L else handle }
        return if (current == 0L) fallback else block(current)
    }

    // ---- configuration, all off the UI thread ------------------------------------------------

    /** Milliseconds spent loading packs since the last activation, published by it. */
    private var pendingPackLoadMillis = 0L

    fun loadLanguage(tag: String, descriptor: AssetFileDescriptor, weight: Float) {
        worker.post {
            withHandle(Unit) { current ->
                val started = android.os.SystemClock.elapsedRealtime()
                val status = NativePredictor.nativeLoadLanguage(
                    current, tag,
                    descriptor.parcelFileDescriptor.fd,
                    descriptor.startOffset,
                    descriptor.length,
                    weight,
                )
                pendingPackLoadMillis += android.os.SystemClock.elapsedRealtime() - started
                // The mapping keeps the file open; the descriptor is closed either way.
                runCatching { descriptor.close() }
                if (status != 0) {
                    lastLoadStatus = status
                }
            }
        }
    }

    @Volatile
    var lastLoadStatus: Int = 0
        private set

    fun setActiveLanguages(tags: Array<String>, weights: FloatArray) {
        val active = tags.toList()
        worker.post {
            activeTags = active
            val started = android.os.SystemClock.elapsedRealtime()
            withHandle(Unit) { current ->
                NativePredictor.nativeSetActiveLanguages(current, tags, weights)
            }
            KeyboardStats.packLoadMillis =
                pendingPackLoadMillis + android.os.SystemClock.elapsedRealtime() - started
            pendingPackLoadMillis = 0L
        }
    }

    /** Pushes the key centres and the key size to the engine. */
    fun setKeyGeometry(count: Int, keyWidth: Float, keyHeight: Float, fill: (IntArray, FloatArray, FloatArray) -> Int) {
        val written = fill(geometryCodes, geometryX, geometryY)
        if (written <= 0 || keyWidth <= 0f || keyHeight <= 0f) {
            return
        }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetKeyGeometry(
                    current, geometryCodes, geometryX, geometryY, keyWidth, keyHeight,
                )
            }
        }
        if (count != written) {
            lastGeometryKeyCount = written
        }
    }

    @Volatile
    var lastGeometryKeyCount: Int = 0
        private set

    /** Replaces the engine's personal words, also with an empty list. */
    fun loadUserWords(words: List<UserWord>) {
        val texts = Array(words.size) { words[it].word }
        val counts = IntArray(words.size) { words[it].count }
        val deliberateCapitals = IntArray(words.size) { words[it].deliberateCapitals }
        val asserted = IntArray(words.size) { words[it].asserted }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeLoadUserWords(
                    current, texts, counts, deliberateCapitals, asserted,
                )
            }
        }
    }

    /**
     * Replaces the engine's personal word pairs, also with an empty list. Call after
     * [loadUserWords].
     */
    fun loadUserBigrams(pairs: List<UserBigram>) {
        val previous = Array(pairs.size) { pairs[it].previousWord }
        val next = Array(pairs.size) { pairs[it].word }
        val counts = IntArray(pairs.size) { pairs[it].count }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeLoadUserBigrams(current, previous, next, counts)
            }
        }
    }

    /** Sets how readily the user's own words outrank the dictionaries. */
    fun setLearningSpeed(speed: Float) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetLearningSpeed(current, speed)
            }
        }
    }

    /** Sets how much evidence an edit needs before it outranks a word spelled as typed. */
    fun setCorrectionStrictness(scale: Float) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetCorrectionStrictness(current, scale)
            }
        }
    }

    /**
     * How much one-sided evidence is wanted before words from other languages stop being
     * offered. Zero never stops offering them.
     */
    fun setLanguageLock(minimumEvidence: Float, strict: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetLanguageLock(current, minimumEvidence, strict)
            }
        }
    }

    /** Which language answers while the engine has not recognised one yet. Empty means none. */
    fun setPreferredLanguage(tag: String) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetPreferredLanguage(current, tag.ifEmpty { null })
            }
        }
    }

    /** Forgets the language verdict, so the next field decides for itself. */
    fun resetLanguageEvidence() {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeResetLanguageEvidence(current)
            }
        }
    }

    /** The pack the conversation is currently considered written in, delivered on the UI thread. */
    override fun dominantPack(onResult: (Int) -> Unit) {
        worker.post {
            val pack = withHandle(-1) { current -> NativePredictor.nativeDominantPack(current) }
            mainHandler.post { onResult(pack) }
        }
    }

    /**
     * What [dominantPack] alone would spell each of [words] as, in the same order, null where it
     * has nothing different. Delivered on the UI thread.
     */
    override fun candidatesForPack(dominantPack: Int, words: List<String>, onResult: (List<String?>) -> Unit) {
        if (words.isEmpty()) {
            onResult(emptyList())
            return
        }
        worker.post {
            val results = words.map { word ->
                withHandle<String?>(null) { current ->
                    NativePredictor.nativeCandidateForPack(current, dominantPack, word)
                }
            }
            mainHandler.post { onResult(results) }
        }
    }

    /**
     * Replaces the engine's personal three-word sequences, also with an empty list. Call after
     * [loadUserBigrams].
     */
    fun loadUserTrigrams(triples: List<UserTrigram>) {
        val previous2 = Array(triples.size) { triples[it].previousWord2 }
        val previous1 = Array(triples.size) { triples[it].previousWord1 }
        val next = Array(triples.size) { triples[it].word }
        val counts = IntArray(triples.size) { triples[it].count }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeLoadUserTrigrams(current, previous2, previous1, next, counts)
            }
        }
    }

    fun setPhraseSuggestions(enabled: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetPhraseSuggestions(current, enabled)
            }
        }
    }

    /**
     * Loads tier B's weights from a `.bkw` file's bytes and warms the model, then reports on the
     * main thread whether they loaded. Always false in a `core` build.
     */
    fun loadSwipeWeights(bytes: ByteArray, onResult: (Boolean) -> Unit) {
        worker.post {
            val loaded = withHandle(false) { current ->
                if (!NativePredictor.nativeLoadSwipeWeights(current, bytes)) {
                    false
                } else {
                    NativePredictor.nativeWarmSwipeModel(current)
                    true
                }
            }
            mainHandler.post { onResult(loaded) }
        }
    }

    /** Switches tier B on or off. Off frees its weights; on again needs [loadSwipeWeights]. */
    fun setSwipeModelEnabled(enabled: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetSwipeModelEnabled(current, enabled)
            }
        }
    }

    /** Sets the words dropped from every answer. */
    fun setRefusedWords(words: RefusedWords) {
        refused = words
    }

    /**
     * Sets the words the engine treats as absent from every dictionary, also an empty list:
     * matched exactly, case aside.
     */
    fun setBlockedWords(words: Collection<String>) {
        val texts = words.toTypedArray()
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetBlockedWords(current, texts)
            }
        }
    }

    /** Whether the personal dictionary is consulted; off for a private field. */
    override fun setPersonalModelEnabled(enabled: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetPersonalModelEnabled(current, enabled)
            }
        }
    }

    /** [dominantPack] as a language tag, or null while undecided. Delivered on the UI thread. */
    override fun dominantLanguageTag(onResult: (String?) -> Unit) {
        worker.post {
            val tag = withHandle<String?>(null) { current ->
                NativePredictor.nativeDominantLanguageTag(current)
            }
            mainHandler.post { onResult(tag) }
        }
    }

    /**
     * The engine's account of [candidate]'s score for [typed], or null when it is not offered.
     * Delivered on the UI thread.
     */
    fun explain(typed: String, candidate: String, onResult: (ScoreExplanation?) -> Unit) {
        worker.post {
            val slots = FloatArray(ScoreExplanation.SLOTS)
            val offered = withHandle(false) { current ->
                NativePredictor.nativeExplainScore(current, typed, candidate, slots)
            }
            val explanation = if (offered) ScoreExplanation.fromSlots(slots) else null
            mainHandler.post { onResult(explanation) }
        }
    }

    override fun learn(updates: List<LearnedWord>, previous1: String?, previous2: String?) {
        if (updates.isEmpty()) {
            return
        }
        worker.post {
            withHandle(Unit) { current ->
                for (update in updates) {
                    NativePredictor.nativeLearn(
                        current, update.word, previous1, previous2, update.deliberateCapital,
                        update.asserted,
                    )
                }
            }
        }
    }

    // ---- the suggestion path ----------------------------------------------------------------

    /** Asks for suggestions. Returns immediately; the answer arrives on the UI thread. */
    override fun requestSuggestions(composing: String, previous1: String?, previous2: String?) {
        if (queue.submit(composing, previous1, previous2)) {
            worker.post(workerLoop)
        }
    }

    override fun cancelPending() {
        queue.clear()
        synchronized(resultLock) { latestAnswer = latestAnswer?.withoutRanking() }
        gestureRequests.cancel()
    }

    /** Drops a swipe decode that has not answered yet. */
    override fun cancelPendingGesture() {
        gestureRequests.cancel()
    }

    /** Requests superseded before being served. */
    val droppedRequests: Int get() = queue.droppedRequests

    /**
     * Decodes a swipe from a copy of its samples. Returns immediately; the answer arrives on the
     * UI thread.
     */
    override fun decodeGesture(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    ) {
        val points = count.coerceAtMost(MAX_GESTURE_POINTS)
        if (points < 2) {
            return
        }
        synchronized(gestureLock) {
            System.arraycopy(xs, 0, gestureX, 0, points)
            System.arraycopy(ys, 0, gestureY, 0, points)
            System.arraycopy(timestamps, 0, gestureTime, 0, points)
            gestureCount = points
        }
        val generation = gestureRequests.issue()
        worker.post {
            Trace.beginSection("PredictionEngine.decodeGesture")
            val started = System.nanoTime()
            val found = try {
                withHandle(0) { current ->
                    val samples = synchronized(gestureLock) { gestureCount }
                    val count = NativePredictor.nativeDecodeGesture(
                        current, gestureX, gestureY, gestureTime, samples,
                        previous1, previous2, decodeWords, decodeScores, decodeProperNoun,
                    )
                    lastGestureUsedNeural = NativePredictor.nativeLastDecodeUsedNeural(current)
                    count
                }
            } finally {
                lastGestureDecodeMicros = (System.nanoTime() - started) / 1_000L
                Trace.endSection()
            }
            synchronized(gestureResultLock) {
                System.arraycopy(decodeWords, 0, gestureNativeWords, 0, found)
                System.arraycopy(decodeScores, 0, gestureNativeScores, 0, found)
                System.arraycopy(decodeProperNoun, 0, gestureNativeProperNoun, 0, found)
                gestureNativeCount = found
            }
            mainHandler.post {
                if (gestureRequests.isNewest(generation)) {
                    publishGestureResult()
                }
            }
        }
    }

    /** Delivers the swipe's answer to the listener. */
    private fun publishGestureResult() {
        listener?.onGestureCandidates(copyAndFilterGesture())
    }

    /** The swipe's answer, copied out under its lock, with refused words dropped. */
    private fun copyAndFilterGesture(): List<Candidate> {
        val out = ArrayList<Candidate>(MAX_RESULTS)
        synchronized(gestureResultLock) {
            for (index in 0 until gestureNativeCount) {
                val word = gestureNativeWords[index] ?: continue
                out.add(
                    Candidate(word, gestureNativeProperNoun[index], gestureNativeScores[index]),
                )
            }
        }
        val refusedNow = refused
        if (!refusedNow.isEmpty) {
            out.removeAll { refusedNow.refuses(it.text) }
        }
        return out
    }

    /** Decodes a swipe still in progress, through its own buffers, lock and generation. */
    override fun decodeGesturePreview(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    ) {
        val points = count.coerceAtMost(MAX_GESTURE_POINTS)
        if (points < 2) {
            return
        }
        synchronized(previewGestureLock) {
            System.arraycopy(xs, 0, previewGestureX, 0, points)
            System.arraycopy(ys, 0, previewGestureY, 0, points)
            System.arraycopy(timestamps, 0, previewGestureTime, 0, points)
            previewGestureCount = points
        }
        val generation = previewRequests.issue()
        worker.post {
            val started = System.nanoTime()
            val found = withHandle(0) { current ->
                val samples = synchronized(previewGestureLock) { previewGestureCount }
                val count = NativePredictor.nativeDecodeGesture(
                    current, previewGestureX, previewGestureY, previewGestureTime, samples,
                    previous1, previous2, decodeWords, decodeScores, decodeProperNoun,
                )
                lastGestureUsedNeural = NativePredictor.nativeLastDecodeUsedNeural(current)
                count
            }
            lastGestureDecodeMicros = (System.nanoTime() - started) / 1_000L
            synchronized(previewResultLock) {
                System.arraycopy(decodeWords, 0, previewNativeWords, 0, found)
                System.arraycopy(decodeScores, 0, previewNativeScores, 0, found)
                System.arraycopy(decodeProperNoun, 0, previewNativeProperNoun, 0, found)
                previewNativeCount = found
            }
            mainHandler.post {
                if (previewRequests.isNewest(generation)) {
                    publishGesturePreviewResult()
                }
            }
        }
    }

    /** Drops a preview decode that has not answered yet. */
    override fun cancelPendingPreview() {
        previewRequests.cancel()
    }

    private fun publishGesturePreviewResult() {
        val out = ArrayList<Candidate>(MAX_RESULTS)
        synchronized(previewResultLock) {
            for (index in 0 until previewNativeCount) {
                val word = previewNativeWords[index] ?: continue
                out.add(Candidate(word, previewNativeProperNoun[index], previewNativeScores[index]))
            }
        }
        val refusedNow = refused
        if (!refusedNow.isEmpty) {
            out.removeAll { refusedNow.refuses(it.text) }
        }
        listener?.onGesturePreviewCandidates(out)
    }

    private fun serveRequests() {
        while (queue.take()) {
            val generation = queue.currentGeneration
            val query = queue.currentComposing
            Trace.beginSection("PredictionEngine.suggest")
            val searchStarted = android.os.SystemClock.elapsedRealtimeNanos()
            val answer = try {
                withHandle(PredictionAnswer.empty(query)) { current ->
                    answerRequest(
                        current, query, queue.currentPrevious1, queue.currentPrevious2, activeTags,
                        scratch,
                    )
                }
            } finally {
                Trace.endSection()
            }
            KeyboardStats.searchMillis.add(
                (android.os.SystemClock.elapsedRealtimeNanos() - searchStarted) / 1_000_000.0,
            )

            if (!queue.isCurrent(generation)) {
                continue
            }
            synchronized(resultLock) { latestAnswer = answer }
            mainHandler.removeCallbacks(publishResults)
            mainHandler.post(publishResults)
        }
    }

    /** Runs on the UI thread and delivers the last answer to the listener. */
    private fun publish() {
        val answer = synchronized(resultLock) { latestAnswer } ?: return
        listener?.onSuggestions(
            answer.candidates(refused), answer.knownWord, answer.query, answer.possessive,
            answer.inflection,
        )
    }

    companion object {
        /** Matches Engine::kMaxCandidates in engine.hpp. */
        const val MAX_RESULTS = 16
        private const val MAX_KEYS = 64
        /** Matches GESTURE_CAPACITY in KeyboardCanvasView and kMaxGesturePoints in the bridge. */
        private const val MAX_GESTURE_POINTS = 512
    }
}
