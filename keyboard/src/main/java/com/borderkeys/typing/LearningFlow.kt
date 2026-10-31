// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.PersonalModelLoad
import com.borderkeys.predict.RefusedWords

/**
 * What is learned from typing: each confirmed word, with the pair and the triple it makes, and
 * where its letters were tapped, buffered and written to the [store]. Where the field's policy
 * allows nothing personal, nothing is learned and the personal dictionary is not consulted; taps
 * are learned only where it allows the heatmap.
 */
class LearningFlow(
    private val engine: EnginePort,
    private val host: TypingHost,
    private val store: LearningStore,
    private val clock: TypingClock,
    /** The language a word is committed in, a primary language subtag ([LanguageFlow.language]). */
    private val commitLanguage: () -> String,
) : TypingFlow() {

    /** What is learned, until it is written to the [store]. */
    private val learning = LearningBuffer()

    /** The heatmap: the current bucket's stored totals and the samples not yet written. */
    private val touches = TouchLearning()

    private val flushRunnable = Runnable { flush() }

    /** The letter keys taps are lined up against; a new bucket loads its stored totals. */
    var geometry: KeyGeometrySnapshot? = null
        set(value) {
            val bucketChanged = value?.bucket?.key != field?.bucket?.key
            field = value
            if (bucketChanged) {
                reloadTouches()
            }
        }

    private val halfLifeMillis: Long
        get() = KeyTouches.halfLifeMillis(settings.heatmapHalfLifeDays)

    override fun onFieldStarted(field: FieldSession) {
        learnedIn = null
        wordsSinceChange = null
        applyGate()
        if (field.policy.privateField) {
            discardLearning()
        }
    }

    /** What was learned in the field is written. */
    override fun onFieldFinished() = flush()

    /** What of the settings a load of the personal model reads, as they stood last; null before any. */
    private var loadRead: PersonalModelLoad.Settings? = null

    /**
     * Learning switched off drops the words and taps not yet written, and the Heatmap switched off
     * drops the taps, at once. A change to what a load of the personal model reads reads it again
     * ([PersonalModelLoad.Settings]). With the views up, the gate follows the settings at once too;
     * otherwise at the next field.
     */
    override fun onSettingsChanged(settings: KeyboardPreferences) {
        val read = PersonalModelLoad.settingsOf(settings)
        if (loadRead != null && read != loadRead) {
            host.reloadPersonalModel()
        }
        loadRead = read
        if (!settings.learningEnabled) {
            discardLearning()
        }
        if ((!settings.learningEnabled || !settings.heatmapEnabled) && !touches.isBlank) {
            touches.discard()
            pushTouches()
        }
        if (host.viewAttached) {
            applyGateInField()
        }
    }

    /**
     * Switched off by hand mid-field: what the field gathered, its words, links and taps, is written,
     * and then the gate closes; switched on again, it opens on the same field.
     */
    override fun onPolicyChanged() {
        if (session.policy.privateField) {
            flush()
        }
        if (host.viewAttached) {
            applyGateInField()
        }
    }

    /** The gate opens for what the unlock allows; the bucket's stored totals are read now. */
    override fun onUserUnlocked() {
        if (host.viewAttached) {
            applyGateInField()
        }
        reloadTouches()
    }

    /** Writes what is left and waits, a bounded time, for the writes in flight. */
    override fun onShutdown() = store.persistBeforeShutdown(drain())

    /** Sets the words never learned. */
    fun setRefusedWords(words: RefusedWords) {
        learning.setRefusedWords(words)
    }

    /**
     * The language the last word in this field was learned under, and how many words have been
     * learned since the language last changed or the gate last opened in the field, null while
     * neither has happened in this field: a pair spans no change of language and no stretch the
     * gate was closed for, nor a triple. A word takes a pair once [WORDS_BEFORE_PAIR] words are
     * learned in the stretch before it, and a triple once [WORDS_BEFORE_TRIPLE] are.
     */
    private var learnedIn: String? = null
    private var wordsSinceChange: Int? = null

    /**
     * Drops [word], in any case, from what is waiting to be written, with its pairs and triples.
     * Returns whether the word itself was waiting.
     */
    fun forget(word: String): Boolean = learning.forget(word)

    /** The commits handed to the engine whose link rows have not come back yet. */
    private var commitsInFlight = 0

    /**
     * Whether the personal model's links may hold uses the personal dictionary must never get:
     * from a [linksStale] until the next [linksFresh], no commit's link rows are kept.
     */
    private var staleLinks = false

    /**
     * The personal model is about to be read again from the personal dictionary, which an edit may
     * then change: what waits is written, and the link rows still to come are kept out, computed as
     * they are from the model being replaced; returns once the write is done.
     */
    suspend fun linksStale() {
        val batch = drain()
        staleLinks = true
        batch?.let { store.persistNow(it) }
    }

    /** The personal model was read again, and every commit handed before it has come back. */
    fun linksFresh() {
        staleLinks = false
    }

    /**
     * Writes [writes], the link rows an edit of the personal dictionary changed in the personal
     * model, over the rows waiting, and waits for the write; dropped while the links are stale,
     * since the reload to come reads the edit from the personal dictionary.
     */
    suspend fun writeEdit(writes: LinkWrites) {
        if (staleLinks) {
            return
        }
        learning.recordLinks(writes, clock.currentTimeMillis())
        flushNow()
    }

    /** Drops the taps waiting to be written. */
    fun forgetTaps() {
        if (!touches.isBlank) {
            touches.discard()
            pushTouches()
        }
    }

    /**
     * Drops what is waiting to be written. The personal model counted those uses already, so when
     * anything was dropped, or a commit is still to come back, the model is read again from the
     * personal dictionary ([TypingHost.reloadPersonalModel]), no link row kept until then.
     */
    private fun discardLearning() {
        if (learning.discard() || commitsInFlight > 0) {
            staleLinks = true
            host.reloadPersonalModel()
        }
    }

    /** Learns nothing until the gate is next set, at a field start or a settings change. */
    fun stop() {
        learning.enabled = false
    }

    /**
     * Records a confirmed word, and the pair and triple it makes with [contextWord] and
     * [grandContextWord], read before the commit, within the stretch [wordsSinceChange] counts, all
     * three under the language it is committed in; a word of one letter is not counted, but its pair and triple are learned.
     * [deliberateCapital] is whether its first letter is a capital the user chose
     * ([ComposingWord.capitalisedByUser]); a word written in capitals throughout is learned with
     * none. [asserted] is whether the user chose it on purpose, and [assertsPair]
     * and [assertsTriple] whether they chose the pair it makes with [contextWord] and the triple
     * it makes with both, in the engine and in what is written. [taps] is the word as it was
     * typed, with where each letter was tapped, lined up with [word], or with its first letters
     * when [completion].
     */
    fun record(
        word: String,
        contextWord: String?,
        grandContextWord: String?,
        deliberateCapital: Boolean = false,
        asserted: Boolean = false,
        assertsPair: Boolean = false,
        assertsTriple: Boolean = false,
        taps: TypedTaps? = null,
        completion: Boolean = false,
    ) {
        val touched = taps != null && recordTouches(taps, word, completion)
        val nameCapital = deliberateCapital && !writtenInCapitals(word)
        var learned = false
        if (learning.enabled) {
            // The word, its pair and its triple go under the language it is committed in.
            val language = commitLanguage()
            val now = clock.currentTimeMillis()
            if (learnedIn != null && language != learnedIn) {
                wordsSinceChange = 0
            }
            val since = wordsSinceChange
            learnedIn = language
            wordsSinceChange = since?.inc()
            // A word with nothing before it is paired with the sentence start.
            val pairContext = contextWord ?: UserBigram.SENTENCE_START
            val pairKept = (since == null || since >= WORDS_BEFORE_PAIR) && learning.acceptsPair(pairContext, word)
            val tripleKept = (since == null || since >= WORDS_BEFORE_TRIPLE) && contextWord != null &&
                grandContextWord != null && learning.acceptsTriple(grandContextWord, contextWord, word)
            val counted = learning.record(word, language, now, nameCapital, asserted)
            if (counted) {
                host.playEffect(EffectEvent.LearnedWord, word)
            }
            learned = counted || pairKept
            if (learned) {
                // The engine learns the pair and the triple the buffer takes; the buffer keeps the
                // link rows the commit changed.
                val update = LearnedWord(word, language, if (counted) 1 else 0, now, nameCapital, if (asserted) 1 else 0)
                commitsInFlight++
                engine.commit(
                    Commit(
                        update, pairContext.takeIf { pairKept }, grandContextWord.takeIf { tripleKept }, language,
                        assertsPair, assertsTriple,
                    ),
                ) { writes ->
                    commitsInFlight--
                    if (!writes.isEmpty && !staleLinks) {
                        learning.recordLinks(writes, clock.currentTimeMillis())
                        // A commit made before the gate closed is written at once.
                        if (learning.enabled) scheduleFlush() else flush()
                    }
                }
            }
        }
        if (learned || touched) {
            scheduleFlush()
        }
    }

    /** Writes the buffer now when it is due, else after the debounce, while the views are up. */
    private fun scheduleFlush() {
        if (!host.viewAttached) {
            return
        }
        host.removeCallbacks(flushRunnable)
        if (learning.isDue(clock.currentTimeMillis())) {
            flush()
        } else {
            host.postDelayed(flushRunnable, LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        }
    }

    /** Reads the current bucket's stored totals again, as after an edit on the settings screen. */
    fun reloadTouches() {
        if (!session.policy.userUnlocked) {
            return
        }
        val bucket = geometry?.bucket?.key ?: return
        store.loadTouches(bucket) { rows ->
            if (geometry?.bucket?.key == bucket) {
                touches.load(bucket, rows, clock.currentTimeMillis(), halfLifeMillis)
                pushTouches()
            }
        }
    }

    /**
     * Lines [taps] up with [kept] and adds the samples, where the field allows the heatmap.
     * Returns whether any were added.
     */
    private fun recordTouches(taps: TypedTaps, kept: String, completion: Boolean): Boolean {
        val geometry = geometry ?: return false
        if (!session.policy.heatmapAllowed) {
            return false
        }
        val samples = TapAlignment.samples(taps, kept, completion, geometry)
        if (samples.isEmpty()) {
            return false
        }
        touches.add(samples, geometry, clock.currentTimeMillis())
        pushTouches()
        return true
    }

    /** Hands the engine the current bucket's patterns. */
    private fun pushTouches() {
        engine.setTouchPatterns(touches.patterns(halfLifeMillis))
    }

    /**
     * Whether anything is learned, whether the personal dictionary is consulted and from how many
     * uses, whether learned phrases are offered, and whether the learned touch patterns count: off
     * with their switches or in a private field.
     */
    private fun applyGate() {
        learning.enabled = session.policy.personalAllowed
        engine.setPersonalModelEnabled(learning.enabled)
        engine.setLearnAfterUses(settings.learnAfter)
        engine.setPhraseSuggestions(settings.phraseSuggestions)
        engine.setTouchModel(
            session.policy.heatmapAllowed, settings.heatmapWeight, settings.heatmapMinTaps,
        )
    }

    /**
     * [applyGate] in the open field: once it opens the gate, the words before, written while it was
     * closed, give the next word no pair and the one after it no triple.
     */
    private fun applyGateInField() {
        val wasOpen = learning.enabled
        applyGate()
        if (!wasOpen && learning.enabled) {
            wordsSinceChange = 0
        }
    }

    /** Writes the buffered learning to the [store]. */
    private fun flush() {
        val batch = drain() ?: return
        store.persist(batch)
    }

    /** Writes what the buffer holds and waits for the write. */
    suspend fun flushNow() {
        val batch = drain() ?: return
        store.persistNow(batch)
    }

    /** Empties [learning] and [touches], or returns null when there was nothing in them. */
    private fun drain(): LearningBatch? {
        val updates = learning.drain()
        val links = learning.drainLinks()
        val taps = touches.drain(halfLifeMillis)
        if (updates.isEmpty() && links.isEmpty && taps.isEmpty()) {
            return null
        }
        return LearningBatch(updates, links, taps, halfLifeMillis)
    }
}

/** Whether [word] holds two letters or more, each a capital. */
private fun writtenInCapitals(word: String): Boolean {
    var letters = 0
    for (character in word) {
        if (character.isLetter()) {
            if (!character.isUpperCase()) {
                return false
            }
            letters++
        }
    }
    return letters >= MIN_CAPITALS_WORD
}

/** The fewest letters a word written in capitals throughout has. */
private const val MIN_CAPITALS_WORD = 2

/** The words a pair spans before its last: its previous word. */
private const val WORDS_BEFORE_PAIR = 1

/** The words a triple spans before its last: its two previous words. */
private const val WORDS_BEFORE_TRIPLE = 2
