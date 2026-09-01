// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.content.res.AssetFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.Trace
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord

/**
 * Owns the native engine, the thread it runs on, and the rule that the UI never waits for it.
 *
 * Three things this class exists to guarantee:
 *
 *  * **The handle cannot outlive the engine.** It is read and written under one lock and set to
 *    zero on release, so a request that arrives while the service is being destroyed returns
 *    nothing instead of calling into freed memory. Every native call goes through [withHandle].
 *  * **The UI thread never blocks on JNI.** Requests are posted to a dedicated thread and
 *    answered on the main looper. There is no path from a touch event into the engine.
 *  * **Only the newest request matters.** [PredictionRequestQueue] keeps one pending request and
 *    a generation number; a slow answer that arrives after a newer one is dropped rather than
 *    shown, which is what stops the strip flickering back to a stale word.
 *
 * A dedicated [HandlerThread], not `Dispatchers.Default`. The native engine is single-writer by
 * construction -- a bump allocator, no locks -- so it needs one thread that is always the same
 * thread. A shared pool would give it a different one per call and would put prediction behind
 * whatever else the application had queued.
 */
class PredictionEngine(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    /** Delivered on the UI thread, already filtered for staleness. */
    interface ResultListener {
        fun onSuggestions(words: Array<String?>, count: Int)

        /**
         * A decoded swipe. Separate from [onSuggestions] because the service treats it
         * differently: the first candidate is committed immediately rather than offered.
         */
        fun onGestureCandidates(words: Array<String?>, count: Int)
    }

    var listener: ResultListener? = null

    private val lock = Any()
    private var handle: Long = 0L
    private var released = false

    private val thread = HandlerThread("borderkeys-predict", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var worker: Handler

    private val queue = PredictionRequestQueue()

    // Written by the prediction thread, copied out by the UI thread under [resultLock]. Both
    // are allocated once: the suggestion path may not allocate per keystroke, and JNI fills
    // these in place.
    private val resultLock = Any()
    private val nativeWords = arrayOfNulls<String>(MAX_RESULTS)
    private val nativeScores = FloatArray(MAX_RESULTS)
    private var nativeCount = 0

    private val displayWords = arrayOfNulls<String>(MAX_RESULTS)
    private var displayCount = 0

    private val blocked = HashSet<String>()

    /** Scratch for pushing key geometry down. Sized once for the largest layout. */
    private val geometryCodes = IntArray(MAX_KEYS)
    private val geometryX = FloatArray(MAX_KEYS)
    private val geometryY = FloatArray(MAX_KEYS)

    // The gesture is copied out of the view's capture buffers before it crosses threads: the
    // view reuses those arrays for the next swipe, and the finger can start one while the
    // decoder is still working on the last.
    private val gestureX = FloatArray(MAX_GESTURE_POINTS)
    private val gestureY = FloatArray(MAX_GESTURE_POINTS)
    private val gestureTime = LongArray(MAX_GESTURE_POINTS)
    private var gestureCount = 0
    private val gestureLock = Any()

    private val workerLoop = Runnable { serveRequests() }
    private val publishResults = Runnable { publish() }
    private val publishGesture = Runnable { publishGestureResult() }
    private var gestureResultCount = 0

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
     * Releases the native engine.
     *
     * The handle is zeroed under the lock before anything is freed, so a call already in flight
     * on the prediction thread finishes against a live engine and any call after this one sees
     * zero and returns. The thread is stopped after, not before, for the same reason.
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
        mainHandler.removeCallbacks(publishGesture)
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val current = synchronized(lock) { if (released) 0L else handle }
        return if (current == 0L) fallback else block(current)
    }

    // ---- configuration, all off the UI thread ------------------------------------------------

    fun loadLanguage(tag: String, descriptor: AssetFileDescriptor, weight: Float) {
        worker.post {
            withHandle(Unit) { current ->
                val status = NativePredictor.nativeLoadLanguage(
                    current, tag,
                    descriptor.parcelFileDescriptor.fd,
                    descriptor.startOffset,
                    descriptor.length,
                    weight,
                )
                // The mapping keeps the file alive on its own, so the descriptor is ours to
                // close either way; leaving it open would leak one per language pack.
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
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetActiveLanguages(current, tags, weights)
            }
        }
    }

    /**
     * Pushes the key geometry so the engine can correct finger slips.
     *
     * Called after every layout pass. The engine learns key centres and a key size; it never
     * learns that a Canvas exists, and the view never learns that a trie does.
     */
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
        // `count` is the caller's own idea of how many keys it has; the fill function is the
        // authority and its return value is what was used.
        if (count != written) {
            lastGeometryKeyCount = written
        }
    }

    @Volatile
    var lastGeometryKeyCount: Int = 0
        private set

    fun loadUserWords(words: List<UserWord>) {
        if (words.isEmpty()) {
            return
        }
        val texts = Array(words.size) { words[it].word }
        val counts = IntArray(words.size) { words[it].count }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeLoadUserWords(current, texts, counts)
            }
        }
    }

    /**
     * Pushes the remembered word pairs. Posted after [loadUserWords] on the same single-threaded
     * worker, which is what guarantees the words are in place before the pairs that name them.
     */
    fun loadUserBigrams(pairs: List<UserBigram>) {
        if (pairs.isEmpty()) {
            return
        }
        val previous = Array(pairs.size) { pairs[it].previousWord }
        val next = Array(pairs.size) { pairs[it].word }
        val counts = IntArray(pairs.size) { pairs[it].count }
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeLoadUserBigrams(current, previous, next, counts)
            }
        }
    }

    /** Applied whenever the preference changes, not only at start. */
    fun setLearningSpeed(speed: Float) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetLearningSpeed(current, speed)
            }
        }
    }

    /** Posted after [loadUserBigrams], so the words a triple names are already held. */
    fun loadUserTrigrams(triples: List<UserTrigram>) {
        if (triples.isEmpty()) {
            return
        }
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

    fun setBlockedWords(words: Set<String>) {
        synchronized(blocked) {
            blocked.clear()
            blocked.addAll(words)
        }
    }

    fun snapshotUserModel(path: String) {
        worker.post {
            withHandle(Unit) { current -> NativePredictor.nativeSnapshotUserModel(current, path) }
        }
    }

    fun learn(updates: List<LearnedWord>, previous1: String?, previous2: String?) {
        if (updates.isEmpty()) {
            return
        }
        worker.post {
            withHandle(Unit) { current ->
                for (update in updates) {
                    NativePredictor.nativeLearn(current, update.word, previous1, previous2)
                }
            }
        }
    }

    // ---- the suggestion path ----------------------------------------------------------------

    /**
     * Asks for suggestions. Returns immediately; the answer arrives on the UI thread.
     *
     * Called from a touch event, so it does nothing but record the request and possibly post a
     * runnable.
     */
    fun requestSuggestions(composing: String, previous1: String?, previous2: String?) {
        if (queue.submit(composing, previous1, previous2)) {
            worker.post(workerLoop)
        }
    }

    fun cancelPending() {
        queue.clear()
        synchronized(resultLock) { nativeCount = 0 }
    }

    /** Requests superseded before being served. Exposed for tracing and tests. */
    val droppedRequests: Int get() = queue.droppedRequests

    /**
     * Decodes a swipe. Returns immediately; the answer arrives on the UI thread.
     *
     * The samples are copied under a lock rather than handed over, because they belong to the
     * view and the view will overwrite them on the next gesture.
     */
    fun decodeGesture(
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
        worker.post {
            Trace.beginSection("PredictionEngine.decodeGesture")
            val found = try {
                withHandle(0) { current ->
                    synchronized(resultLock) {
                        val samples = synchronized(gestureLock) { gestureCount }
                        NativePredictor.nativeDecodeGesture(
                            current, gestureX, gestureY, gestureTime, samples,
                            previous1, previous2, nativeWords, nativeScores,
                        )
                    }
                }
            } finally {
                Trace.endSection()
            }
            synchronized(resultLock) { nativeCount = found }
            mainHandler.removeCallbacks(publishGesture)
            mainHandler.post(publishGesture)
        }
    }

    private fun publishGestureResult() {
        val written = copyAndFilterResults()
        gestureResultCount = written
        listener?.onGestureCandidates(displayWords, written)
    }

    private fun serveRequests() {
        while (queue.take()) {
            val generation = queue.currentGeneration
            Trace.beginSection("PredictionEngine.suggest")
            val count = try {
                withHandle(0) { current ->
                    synchronized(resultLock) {
                        NativePredictor.nativeSuggest(
                            current,
                            queue.currentComposing,
                            queue.currentPrevious1,
                            queue.currentPrevious2,
                            nativeWords,
                            nativeScores,
                        )
                    }
                }
            } finally {
                Trace.endSection()
            }

            // A newer request landed while this one was running: its answer is the one that
            // matters, and showing this one would be a visible flicker backwards.
            if (!queue.isCurrent(generation)) {
                continue
            }
            synchronized(resultLock) { nativeCount = count }
            mainHandler.removeCallbacks(publishResults)
            mainHandler.post(publishResults)
        }
    }

    /**
     * Runs on the UI thread. Copies the shared buffer into the display buffer under the lock --
     * sixteen references, uncontended -- so the prediction thread can start overwriting it the
     * moment this returns.
     */
    private fun publish() {
        listener?.onSuggestions(displayWords, copyAndFilterResults())
    }

    /**
     * Moves the shared buffer into the display buffer and drops refused words.
     *
     * The copy happens under the lock -- sixteen references, uncontended -- so the prediction
     * thread can start overwriting the moment this returns. Blocked words are filtered here
     * rather than in the engine: the native side has no notion of a word the user refused, and
     * this is a set lookup on at most sixteen strings, once per answer.
     */
    private fun copyAndFilterResults(): Int {
        var count: Int
        synchronized(resultLock) {
            count = nativeCount
            for (index in 0 until count) {
                displayWords[index] = nativeWords[index]
            }
        }
        var written = 0
        synchronized(blocked) {
            for (index in 0 until count) {
                val word = displayWords[index] ?: continue
                if (blocked.isEmpty() || word !in blocked) {
                    displayWords[written] = word
                    written++
                }
            }
        }
        for (index in written until MAX_RESULTS) {
            displayWords[index] = null
        }
        displayCount = written
        return written
    }

    companion object {
        /** Matches Engine::kMaxCandidates in engine.hpp. */
        const val MAX_RESULTS = 16
        private const val MAX_KEYS = 64
        /** Matches GESTURE_CAPACITY in KeyboardCanvasView and kMaxGesturePoints in the bridge. */
        private const val MAX_GESTURE_POINTS = 512
    }
}
