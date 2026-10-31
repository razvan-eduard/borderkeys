// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.content.res.AssetFileDescriptor
import android.os.Trace
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.WorkingCopy
import com.borderkeys.typing.Commit
import com.borderkeys.typing.EnginePort
import com.borderkeys.typing.SearchAnswer
import com.borderkeys.typing.TouchPatterns

/**
 * Owns the native engine and the one thread it runs on, started by [startThread]. Every native
 * call goes through [withHandle] on that thread, answers are delivered on [main], and an answer
 * older than the newest request is dropped.
 */
class PredictionEngine(
    private val main: EngineThread = LooperThread.main(),
    private val startThread: () -> EngineThread = { LooperThread.started(THREAD_NAME) },
) : EnginePort {
    /** Receives the engine's answers on the UI thread. */
    interface ResultListener {
        /**
         * [query] is the composing text the answer is about, as it was asked. [knownWord] is
         * [query] when the dictionaries spell it, or empty; [knownWordExact] whether one spells it
         * exactly, case aside, and [knownWordIsName] whether that spelling is a name.
         * [corrections] is autocorrect's list for [query], best first; [answerPack] the tag of the
         * pack the answer leaned on.
         */
        fun onSuggestions(
            candidates: List<Candidate>,
            knownWord: String,
            knownWordExact: Boolean,
            knownWordIsName: Boolean,
            query: String,
            possessive: String?,
            corrections: List<CorrectionOffer>,
            answerPack: String?,
        )

        /** A decoded swipe, best first; [answerPack] the tag of the pack the decode leaned on. */
        fun onGestureCandidates(candidates: List<Candidate>, answerPack: String?)

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

    /**
     * The personal model of the engine at [handle] and the lookup it is handed from, used on the
     * worker; guarded by [lock], read with the handle ([withWorkingCopy]).
     */
    private var workingCopy: EngineWorkingCopy? = null

    private lateinit var worker: EngineThread

    private val queue = PredictionRequestQueue()

    private val verdictRequests = VerdictRequests()

    /** The tags handed to [setActiveLanguages], read on the worker. */
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
    private val aliasCodes = IntArray(MAX_ALIAS_PAIRS)
    private val aliasBases = IntArray(MAX_ALIAS_PAIRS)

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
    private var gestureAnswerPack: String? = null

    // The decode's own scratch, filled without a lock and copied under [gestureResultLock].
    private val decodeWords = arrayOfNulls<String>(MAX_RESULTS)
    private val decodeScores = FloatArray(MAX_RESULTS)
    private val decodeProperNoun = BooleanArray(MAX_RESULTS)
    private val decodeTexts = arrayOfNulls<String>(NativePredictor.TEXT_SLOTS)

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
        worker = startThread()
        val created = NativePredictor.nativeCreate()
        synchronized(lock) {
            handle = created
            released = false
            workingCopy = if (created != 0L) EngineWorkingCopy(created) else null
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
            workingCopy = null
        }
        verdictRequests.close()
        queue.clear()
        if (::worker.isInitialized) {
            worker.clear()
            worker.post {
                if (toDestroy != 0L) {
                    NativePredictor.nativeDestroy(toDestroy)
                }
                worker.quit()
            }
        } else if (toDestroy != 0L) {
            NativePredictor.nativeDestroy(toDestroy)
        }
        main.remove(publishResults)
        // Drops the gesture and preview answers still in flight.
        previewRequests.cancel()
        gestureRequests.cancel()
    }

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val current = synchronized(lock) { if (released) 0L else handle }
        return if (current == 0L) fallback else block(current)
    }

    /** [block] on the working copy of the live engine, read with its handle; [fallback] once released. */
    private inline fun <T> withWorkingCopy(fallback: T, block: (EngineWorkingCopy) -> T): T {
        val current = synchronized(lock) { if (released || handle == 0L) null else workingCopy }
        return if (current == null) fallback else block(current)
    }

    /**
     * The key of [language]'s context after [previous1] and [previous2], its slice handed to the
     * engine ([EngineWorkingCopy.contextFor]); a key naming none once released. On the worker.
     */
    private fun personalContext(language: String, previous1: String?, previous2: String?): IntArray =
        withWorkingCopy(EngineWorkingCopy.noContext()) { it.contextFor(language, previous1, previous2) }

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

    /** Marks [tags] as loaded and active in [PackLoad], after every load already posted. */
    fun markPacksLoaded(tags: List<String>) {
        worker.post { PackLoad.set(tags) }
    }

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

    /**
     * Pushes the key centres and the key size to the engine, and the long-press letters a swipe
     * may reach, from [aliases] (a letter and its key's code at the same index).
     */
    fun setKeyGeometry(
        count: Int,
        keyWidth: Float,
        keyHeight: Float,
        aliases: (IntArray, IntArray) -> Int = { _, _ -> 0 },
        fill: (IntArray, FloatArray, FloatArray) -> Int,
    ) {
        val written = fill(geometryCodes, geometryX, geometryY)
        if (written <= 0 || keyWidth <= 0f || keyHeight <= 0f) {
            return
        }
        val aliasCount = aliases(aliasCodes, aliasBases)
        val codes = aliasCodes.copyOf(aliasCount)
        val bases = aliasBases.copyOf(aliasCount)
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetKeyGeometry(
                    current, geometryCodes, geometryX, geometryY, keyWidth, keyHeight, codes, bases,
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

    /** Replaces the engine's personal model with [model] ([EngineWorkingCopy.load]), also an empty one. */
    internal fun loadPersonal(model: PersonalModelLoad) {
        worker.post {
            withWorkingCopy(Unit) { it.load(model) }
        }
    }

    override fun editPersonal(change: (WorkingCopy) -> LinkWrites, onEdited: (LinkWrites?) -> Unit) {
        worker.post {
            val writes = withWorkingCopy<LinkWrites?>(null) { if (it.loaded) change(it) else null }
            main.post { onEdited(writes) }
        }
    }

    override fun settle(onSettled: () -> Unit) {
        worker.post { main.post(onSettled) }
    }

    /** Sets how many uses a word, pair or triple needs before it is learned. */
    override fun setLearnAfterUses(uses: Int) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetLearnAfterUses(current, uses)
            }
        }
    }

    /**
     * Sets how common a word a pack knows but never offers must be to count as spelled: its
     * unigram log-probability at least [minimumLogProb]. Positive infinity counts none.
     */
    fun setKnownWordReach(minimumLogProb: Float) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetKnownWordReach(current, minimumLogProb)
            }
        }
    }

    /** Sets how much evidence an edit needs before it outranks a word spelled as typed. */
    override fun setCorrectionStrictness(scale: Float) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetCorrectionStrictness(current, scale)
            }
        }
    }

    override fun setLanguageLock(minimumEvidence: Float, strict: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetLanguageLock(current, minimumEvidence, strict)
            }
        }
    }

    override fun setPreferredLanguage(tag: String) {
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

    /**
     * The evidence gathered for each active language, by tag, or null once the engine is shut
     * down. Delivered on the UI thread.
     */
    fun languageEvidence(onResult: (Map<String, Float>?) -> Unit) {
        worker.post {
            val evidence = withHandle<Map<String, Float>?>(null) { current ->
                activeTags.associateWith { tag -> NativePredictor.nativeLanguageEvidence(current, tag) }
            }
            main.post { onResult(evidence) }
        }
    }

    /**
     * Puts back evidence [languageEvidence] delivered, and decides the language from it. A tag
     * with no open pack is ignored.
     */
    fun restoreLanguageEvidence(evidence: Map<String, Float>) {
        worker.post {
            withHandle(Unit) { current ->
                for ((tag, value) in evidence) {
                    NativePredictor.nativeSetLanguageEvidence(current, tag, value)
                }
            }
        }
    }

    /** The pack the conversation is currently considered written in, delivered on the UI thread. */
    override fun dominantPack(onResult: (Int) -> Unit) {
        worker.post {
            val pack = withHandle(NativePredictor.NO_PACK) { current ->
                NativePredictor.nativeDominantPack(current)
            }
            main.post { onResult(pack) }
        }
    }

    /** See [EnginePort.answersAs]; delivered on the UI thread. */
    override fun answersAs(pack: Int, words: List<String>, onResult: (List<SearchAnswer?>) -> Unit) {
        if (words.isEmpty()) {
            onResult(emptyList())
            return
        }
        worker.post {
            val results = words.map { word ->
                withHandle<SearchAnswer?>(null) { current ->
                    answerRequestAs(current, pack, word, activeTags, scratch) { language ->
                        personalContext(language, null, null)
                    }?.searchAnswer(refused)
                }
            }
            main.post { onResult(results) }
        }
    }

    /**
     * Whether each of [words] may be part of a learned pair or triple. Each answer comes once: on
     * the prediction thread; at once when the engine cannot take the request; at shutdown, on the
     * thread that shuts it down. Every word is learnable when the engine is gone.
     */
    fun learnableWords(words: List<String>, onResult: (List<Boolean>) -> Unit) {
        verdictRequests.submit(words, onResult, post = { ::worker.isInitialized && worker.post(it) }) { asked ->
            val verdicts = BooleanArray(asked.size) { true }
            withHandle(Unit) { current -> NativePredictor.nativeLearnable(current, asked.toTypedArray(), verdicts) }
            verdicts.toList()
        }
    }

    /** Whether the dictionaries hold each of [words], delivered on the UI thread. */
    override fun knownWords(words: List<String>, onResult: (List<Boolean>) -> Unit) {
        worker.post {
            val known = words.map { word ->
                withHandle(false) { current ->
                    NativePredictor.nativeKnownSpelling(current, word) != null
                }
            }
            main.post { onResult(known) }
        }
    }

    override fun setPhraseSuggestions(enabled: Boolean) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetPhraseSuggestions(current, enabled)
            }
        }
    }

    /**
     * Makes [script]'s model the one the layout decodes with, loading it first from [read] when
     * it is not held, and warms it; reports on the main thread whether it is ready. A null
     * [script] decodes with tier A. Always false in a `core` build.
     */
    fun useSwipeModel(script: Int?, read: () -> ByteArray?, onResult: (Boolean) -> Unit) {
        worker.post {
            val ready = withHandle(false) { current ->
                if (script == null) {
                    NativePredictor.nativeSelectSwipeScript(current, NO_SWIPE_SCRIPT)
                    return@withHandle false
                }
                NativePredictor.nativeSelectSwipeScript(current, script)
                if (NativePredictor.nativeHasSwipeModel(current, script)) {
                    return@withHandle true
                }
                val bytes = read() ?: return@withHandle false
                if (!NativePredictor.nativeLoadSwipeWeights(current, script, bytes)) {
                    false
                } else {
                    NativePredictor.nativeSelectSwipeScript(current, script)
                    NativePredictor.nativeWarmSwipeModel(current)
                    true
                }
            }
            main.post { onResult(ready) }
        }
    }

    /** Switches tier B on or off. Off frees its weights; on again needs [useSwipeModel]. */
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

    override fun setTouchModel(learned: Boolean, weight: Float, minTaps: Int) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetTouchModel(current, learned, weight, minTaps)
            }
        }
    }

    override fun setTouchPatterns(patterns: TouchPatterns) {
        worker.post {
            withHandle(Unit) { current ->
                NativePredictor.nativeSetTouchPatterns(
                    current, patterns.codes, patterns.taps, patterns.meanX, patterns.meanY,
                    patterns.varianceX, patterns.varianceY, patterns.covariance,
                )
            }
        }
    }

    /** [dominantPack] as a language tag, or null while undecided. Delivered on the UI thread. */
    override fun dominantLanguageTag(onResult: (String?) -> Unit) {
        worker.post {
            val tag = withHandle<String?>(null) { current ->
                NativePredictor.nativeDominantLanguageTag(current)
            }
            main.post { onResult(tag) }
        }
    }

    /**
     * The engine's account of [candidate]'s score for [typed], the learned pairs and triples read
     * in [language]'s store, or null when it is not offered. Delivered on the UI thread.
     */
    fun explain(
        typed: String,
        candidate: String,
        language: String,
        onResult: (ScoreExplanation?) -> Unit,
    ) {
        worker.post {
            val explanation = withHandle<ScoreExplanation?>(null) { current ->
                ScoreExplanation.read(current, typed, candidate, personalContext(language, null, null))
            }
            main.post { onResult(explanation) }
        }
    }

    override fun commit(commit: Commit, onCommitted: (LinkWrites) -> Unit) {
        worker.post {
            val writes = withWorkingCopy<LinkWrites?>(null) { it.commit(commit) }
            if (writes != null) {
                main.post { onCommitted(writes) }
            }
        }
    }

    // ---- the suggestion path ----------------------------------------------------------------

    /** Asks for suggestions. Returns immediately; the answer arrives on the UI thread. */
    override fun requestSuggestions(
        composing: String,
        previous1: String?,
        previous2: String?,
        language: String,
        tapXs: FloatArray?,
        tapYs: FloatArray?,
    ) {
        if (queue.submit(composing, previous1, previous2, language, tapXs, tapYs)) {
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
        language: String,
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
            decodeTexts.fill(null)
            val found = try {
                withHandle(0) { current ->
                    val samples = synchronized(gestureLock) { gestureCount }
                    val count = NativePredictor.nativeDecodeGesture(
                        current, gestureX, gestureY, gestureTime, samples, previous1, previous2,
                        personalContext(language, previous1, previous2), decodeWords, decodeScores,
                        decodeProperNoun, decodeTexts,
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
                gestureAnswerPack = decodeTexts[NativePredictor.TEXT_ANSWER_PACK]
            }
            main.post {
                if (gestureRequests.isNewest(generation)) {
                    publishGestureResult()
                }
            }
        }
    }

    /** Delivers the swipe's answer, and the pack it leaned on, to the listener. */
    private fun publishGestureResult() {
        val candidates = copyAndFilterGesture()
        val answerPack = synchronized(gestureResultLock) { gestureAnswerPack }
        listener?.onGestureCandidates(candidates, answerPack)
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
        language: String,
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
            decodeTexts.fill(null)
            val found = withHandle(0) { current ->
                val samples = synchronized(previewGestureLock) { previewGestureCount }
                val count = NativePredictor.nativeDecodeGesture(
                    current, previewGestureX, previewGestureY, previewGestureTime, samples, previous1,
                    previous2, personalContext(language, previous1, previous2), decodeWords,
                    decodeScores, decodeProperNoun, decodeTexts,
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
            main.post {
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
                    val previous1 = queue.currentPrevious1
                    val previous2 = queue.currentPrevious2
                    answerRequest(
                        current, query, previous1, previous2,
                        personalContext(queue.currentLanguage, previous1, previous2), activeTags, scratch,
                        queue.currentTapXs, queue.currentTapYs,
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
            main.remove(publishResults)
            main.post(publishResults)
        }
    }

    /** Runs on the UI thread and delivers the last answer to the listener. */
    private fun publish() {
        val answer = synchronized(resultLock) { latestAnswer } ?: return
        listener?.onSuggestions(
            answer.candidates(refused), answer.knownWord, answer.knownWordExact,
            answer.knownWordIsName, answer.query, answer.possessive, answer.offers(refused),
            answer.answerPack,
        )
    }

    companion object {
        /** The script id that decodes with tier A. */
        const val NO_SWIPE_SCRIPT = -1

        /** The name of the prediction thread. */
        private const val THREAD_NAME = "borderkeys-predict"

        /** Matches Engine::kMaxCandidates in engine.hpp. */
        const val MAX_RESULTS = 16
        private const val MAX_KEYS = 64
        /** Matches kMaxAliasPairs in the bridge. */
        private const val MAX_ALIAS_PAIRS = 1024
        /** Matches GESTURE_CAPACITY in KeyboardCanvasView and kMaxGesturePoints in the bridge. */
        private const val MAX_GESTURE_POINTS = 512
    }
}
