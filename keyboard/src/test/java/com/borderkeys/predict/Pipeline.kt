// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.WordShape
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.personal.PersonalSpelling
import com.borderkeys.data.personal.SliceRows
import com.borderkeys.data.personal.WordRows
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.Contractions
import com.borderkeys.ime.OffensiveWords
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import com.borderkeys.typing.CommitLanguage
import com.borderkeys.typing.FieldPolicy
import com.borderkeys.typing.FieldSession
import com.borderkeys.typing.LearningFlow
import com.borderkeys.typing.QueuedEngine
import com.borderkeys.typing.SwipePath
import com.borderkeys.typing.TypingOrchestrator
import com.borderkeys.typing.TypingRig
import org.junit.AssumptionViolatedException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.io.Writer
import java.util.Locale

/**
 * Words typed into a [TypingOrchestrator] off a device, the engine on the shipped packs through
 * the JNI bridge: corrections on space, auto-capitalise on, no shortcuts, and Learning off unless
 * [learning] is set. The field is plain text.
 */
internal class Pipeline private constructor(
    private val handle: Long,
    private val contractions: Map<String, String>,
    private val twins: Map<String, Contractions.Twin>,
    languages: List<String>,
) {

    /** The active tags, in the order they were enabled. */
    var languages: List<String> = languages
        private set

    /** What a word became, and why. */
    internal data class Outcome(val typed: String, val committed: String?, val reason: String) {
        /**
         * Whether the field kept what a row meant, case aside: the word left alone when [meant] is
         * what was typed, else [meant] committed in its place. With [caseSensitive], whether the
         * field holds [meant] exactly, capitals included: what was committed, else what was typed.
         */
        fun rightFor(meant: String, caseSensitive: Boolean = false): Boolean = when {
            caseSensitive -> (committed ?: typed) == meant
            meant == typed -> committed == null
            else -> committed.equals(meant, ignoreCase = true)
        }
    }

    /** Whether the Learning switch is on; off, no case learns from the ones typed before it. */
    var learning = false

    /** The language lock, one of KeyboardPreferences' LANGUAGE_LOCK_ steps; set by [languageLock]. */
    private var lock = KeyboardPreferences.LANGUAGE_LOCK_OFF

    /** The preferred language's tag, empty for none; set by [preferredLanguage]. */
    private var preferred = ""

    /**
     * The engine's personal model and its lookup, shared by every rig on this engine, loaded with
     * none as the service loads an empty personal dictionary at its start.
     */
    val workingCopy = EngineWorkingCopy(handle).also { it.load(PersonalSnapshot.EMPTY) }

    private val rig = typingRig(settings())

    /**
     * A rig of its own on this engine, with [settings] and the [offensiveWords] its loads leave out
     * under the switch, for typing a scenario key by key.
     */
    fun typingRig(settings: KeyboardPreferences, offensiveWords: Set<String> = emptySet()): TypingRig =
        TypingRig(QueuedEngine(handle, languages, workingCopy = workingCopy), settings, offensiveWords).also {
            it.orchestrator.contractions = contractions
            it.orchestrator.twins = twins
            it.orchestrator.languageTags = languages
        }

    /**
     * Types [typed] and a space into a field holding [previous] and a space, or nothing, and
     * reads back what the field kept.
     */
    fun commit(
        typed: String,
        previous: String? = null,
        minimumLength: Int = 3,
        correctionDistance: Int = DEFAULT_DISTANCE,
        capitaliseNames: Boolean = true,
    ): Outcome {
        rig.orchestrator.applySettings(settings(minimumLength, correctionDistance, capitaliseNames))
        val before = if (previous != null) "$previous " else ""
        rig.startField(before)
        return typeWord(typed)
    }

    /**
     * Types [typed] letter by letter at ([xs], [ys]) on the harness's layout, in an empty field,
     * then a space, and reads back what the field kept. A letter with no key, or a NaN position,
     * is pressed without a position.
     */
    fun commitTapped(typed: String, xs: FloatArray, ys: FloatArray): Outcome {
        rig.orchestrator.applySettings(settings())
        rig.startField("")
        val start = rig.editor.selectionEnd
        for ((index, letter) in typed.withIndex()) {
            val key = keyIndex(letter)
            if (key < 0 || index >= xs.size || xs[index].isNaN()) {
                rig.press(letter.code)
            } else {
                rig.tap(letter.code, key, xs[index], ys[index])
            }
        }
        val word = rig.orchestrator.composingText
        val reason = if (word.isNotEmpty()) rig.orchestrator.commitOutcome(word, ' '.code).reason else ""
        rig.press(' '.code)
        val written = rig.editor.text.substring(start, rig.editor.selectionEnd).removeSuffix(" ")
        return Outcome(typed, written.takeIf { it != typed }, reason)
    }

    /**
     * Types [typed] and a space where the caret is. [Outcome.committed] is null when the field
     * kept what was typed; [Outcome.reason] is the decision of the key that ended the last word.
     */
    private fun typeWord(typed: String): Outcome {
        val start = rig.editor.selectionEnd
        val keys = "$typed "
        var reason: String? = null
        var index = 0
        while (index < keys.length) {
            val code = keys.codePointAt(index)
            index += Character.charCount(code)
            val word = rig.orchestrator.composingText
            if (word.isNotEmpty() && !WordShape.isWordCharacter(code)) {
                reason = rig.orchestrator.commitOutcome(word, code).reason
            }
            rig.press(code)
        }
        val written = rig.editor.text.substring(start, rig.editor.selectionEnd).removeSuffix(" ")
        return Outcome(
            typed,
            written.takeIf { it != typed },
            reason ?: rig.orchestrator.commitOutcome("", ' '.code).reason,
        )
    }

    /**
     * What a swipe wrote, the space after it left off; the decoder's own words, best first; and
     * the list the keyboard made of them, twins spliced in, as a swipe's ring and strip get it.
     */
    internal data class Swiped(val written: String, val decoded: List<String>, val offered: List<String>)

    /**
     * Swipes [word] through its keys' centres on the harness's layout ([TypingRig.swipe]), in a
     * field holding [previous] and a space, or nothing, ends it with a space, and reads back what
     * the field kept; the decoder's words come from the same path.
     */
    fun swipe(word: String, previous: String? = null): Swiped {
        rig.orchestrator.applySettings(settings())
        rig.startField(if (previous != null) "$previous " else "")
        val start = rig.editor.selectionEnd
        val decoded = decode(word, previous).first
        rig.swipe(word)
        rig.press(' '.code)
        rig.settle()
        val written = rig.editor.text.substring(start, rig.editor.selectionEnd).removeSuffix(" ")
        val offered = rig.orchestrator.caseSwipedWords(decoded).map { it.text }
        return Swiped(written, decoded.map { it.text }, offered)
    }

    /** The tag of the pack a decode of a swipe through [word]'s keys leaned on. */
    fun swipeAnswerPack(word: String): String? = decode(word, null).second

    /**
     * The decoder's words, best first, for a swipe through [word]'s keys after [previous], the
     * learned pairs and triples read in [language]'s store.
     */
    fun decoded(word: String, previous: String?, language: String): List<String> =
        decode(word, previous, language).first.map { it.text }

    /**
     * The decoder's words for a swipe through [word]'s keys after [previous], the learned pairs
     * and triples read in [language]'s store, by default the rig's, and the tag of the pack it
     * leaned on.
     */
    private fun decode(
        word: String,
        previous: String?,
        language: String = rig.orchestrator.language,
    ): Pair<List<Candidate>, String?> {
        val path = SwipePath.through(word, rig.clock)
        return decodePoints(path.xs, path.ys, path.timestamps, previous, null, language)
    }

    /**
     * The decoder's words, best first, for the recorded swipe through ([xs], [ys]) at
     * [timestamps], in a field holding [previous2], [previous1] and a space, as the keyboard hands
     * a swipe its context, under this pipeline's settings; the learned pairs and triples read in
     * the rig's language's store.
     */
    fun decodeRecorded(xs: FloatArray, ys: FloatArray, timestamps: LongArray, previous1: String, previous2: String): List<String> {
        rig.orchestrator.applySettings(settings())
        rig.startField("$previous2 $previous1 ")
        rig.settle()
        return decodePoints(xs, ys, timestamps, previous1, previous2, rig.orchestrator.language).first.map { it.text }
    }

    /**
     * The decoder's words for the swipe through ([xs], [ys]) at [timestamps] after [previous2]
     * and [previous1], the learned pairs and triples read in [language]'s store, and the tag of
     * the pack it leaned on.
     */
    private fun decodePoints(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        previous1: String?,
        previous2: String?,
        language: String,
    ): Pair<List<Candidate>, String?> {
        val words = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
        val properNoun = BooleanArray(PredictionEngine.MAX_RESULTS)
        val texts = arrayOfNulls<String>(NativePredictor.TEXT_SLOTS)
        val found = NativePredictor.nativeDecodeGesture(
            handle, xs, ys, timestamps, xs.size, previous1, previous2,
            workingCopy.contextFor(language, previous1, previous2), words, FloatArray(PredictionEngine.MAX_RESULTS),
            properNoun, texts,
        )
        val decoded = (0 until found).mapNotNull { index -> words[index]?.let { Candidate(it, properNoun[index]) } }
        return decoded to texts[NativePredictor.TEXT_ANSWER_PACK]
    }

    /** Whether the last swipe decoded went through the neural decoder. */
    fun lastDecodeUsedNeural(): Boolean = NativePredictor.nativeLastDecodeUsedNeural(handle)

    /** Whether each of [words] may be part of a learned pair or triple, as the engine judges it. */
    fun learnable(words: List<String>): List<Boolean> {
        val verdicts = BooleanArray(words.size)
        NativePredictor.nativeLearnable(handle, words.toTypedArray(), verdicts)
        return verdicts.toList()
    }

    /** Whether an active pack spells [word] exactly, case aside. */
    fun spells(word: String): Boolean =
        NativePredictor.nativeKnownSpelling(handle, word)?.equals(word, ignoreCase = true) == true

    /** The apostrophe twin [word] has on this pipeline's languages, or null. */
    fun twinOf(word: String): String? = Contractions.twinOf(word, twins)?.written

    /**
     * Decodes swipes with the Latin model from the `plus` assets as well as the geometric
     * decoder; false, changing nothing, when the model is missing or the host library was built
     * without it.
     */
    fun useLatinModel(): Boolean {
        val file = File(LATIN_MODEL)
        if (!file.isFile) {
            return false
        }
        if (!NativePredictor.nativeLoadSwipeWeights(handle, com.borderkeys.ime.SwipeModels.LATIN.script, file.readBytes())) {
            return false
        }
        NativePredictor.nativeSelectSwipeScript(handle, com.borderkeys.ime.SwipeModels.LATIN.script)
        NativePredictor.nativeSetSwipeModelEnabled(handle, true)
        return true
    }

    /**
     * The settings the keyboard runs with, the three the corpora vary given, with the language
     * lock and the preferred language this pipeline was given.
     */
    private fun settings(
        minimumLength: Int = 3,
        correctionDistance: Int = DEFAULT_DISTANCE,
        capitaliseNames: Boolean = true,
    ) = KeyboardPreferences(
        learningEnabled = learning,
        autoCorrectOnSpace = true,
        autoCorrectOnEnter = true,
        autoCapitalise = true,
        minCorrectionLength = minimumLength,
        correctionDistance = correctionDistance,
        capitaliseNames = capitaliseNames,
        languageLock = lock,
        preferredLanguageTag = preferred,
    )

    /**
     * What the suggestion strip would show for [typed] after [previous], in order, the learned
     * pairs and triples read in [language]'s store, by default the rig's.
     */
    fun strip(
        typed: String,
        previous: String? = null,
        language: String = rig.orchestrator.language,
    ): List<String> = answer(typed, previous, null, language).words.filterNotNull()

    /**
     * The engine's word table entry for [word], in any case, as the lookup holds its words: its id,
     * whether an active pack knows it, its counts and its spelling; null for a word it does not hold.
     */
    fun engineWord(word: String): PersonalLookup.HeldWord? {
        val fields = IntArray(WordRows.FIELDS)
        val spelling = arrayOfNulls<String>(1)
        val known = BooleanArray(1)
        val id = NativePredictor.nativeWordEntry(handle, word, fields, spelling, known)
        if (id == WordRows.NEW_WORD) {
            return null
        }
        val entry = PersonalSpelling.Merged(
            PhraseKey.word(word), spelling[0].orEmpty(), fields[WordRows.COUNT], fields[WordRows.DELIBERATE_CAPITALS],
            fields[WordRows.ASSERTED], fields[WordRows.ADDED],
        )
        return PersonalLookup.HeldWord(id, known[0], entry)
    }

    /** The words of [strip] for [typed] the engine offers as names, in order. */
    fun namesOnStrip(typed: String): List<String> {
        val answer = answer(typed, null, null)
        return answer.words.indices.filter { answer.properNoun[it] }.mapNotNull { answer.words[it] }
    }

    /** The tag of the pack the answer about [typed] leaned on. */
    fun answerPack(typed: String): String? = answer(typed, null, null).answerPack

    /**
     * The engine's answer about [typed] after [previous1] and [previous2], the learned pairs and
     * triples read in [language]'s store, by default the rig's, its context's slice handed first.
     */
    private fun answer(
        typed: String,
        previous1: String?,
        previous2: String?,
        language: String = rig.orchestrator.language,
    ): PredictionAnswer =
        answerRequest(
            handle, typed, previous1, previous2, workingCopy.contextFor(language, previous1, previous2), languages,
            scratch,
        )

    /**
     * [outcome] as one tab-separated line: typed, committed or `-`, reason, then the engine's
     * ranking for the typed word, each entry `word:score`, a name marked `word*:score`.
     */
    fun readingLine(outcome: Outcome): String = readingLine(outcome, ranking(outcome.typed, null, null))

    private fun readingLine(outcome: Outcome, ranking: String): String =
        "${outcome.typed}\t${outcome.committed ?: "-"}\t${outcome.reason}\t$ranking"

    /**
     * The engine's ranking for [typed] after [previous1] and [previous2], the learned pairs and
     * triples read in the rig's language, as [readingLine] writes it.
     */
    private fun ranking(typed: String, previous1: String?, previous2: String?): String =
        rankingOf(answer(typed, previous1, previous2))

    /** [answer]'s ranking as [readingLine] writes it. */
    private fun rankingOf(answer: PredictionAnswer): String =
        answer.words.indices.joinToString(" ") { index ->
            val name = if (answer.properNoun[index]) "*" else ""
            "${answer.words[index]}$name:" + String.format(Locale.ROOT, "%.4f", answer.scores[index])
        }

    /**
     * What a word typed after a context became, its reading line there, and the words of the
     * engine's ranking for it there, best first.
     */
    internal data class Reading(val outcome: Outcome, val line: String, val ranked: List<String>)

    /**
     * Learns [runs] as the orchestrator learns committed words: each word with the two before
     * it in its run, through a [LearningFlow] on this pipeline's engine and store, under the
     * language the engine's answer names, the engine answering before the next word and the
     * clock moving as if each word and a space were typed. What it buffered is written at the end.
     */
    fun learnHistory(runs: List<List<String>>) {
        val learning = settings().copy(learningEnabled = true)
        // The language a typed word's answer would name.
        val answerPack = answer("", null, null).answerPack
        val language = CommitLanguage.decide(answerPack, rig.host.subtypeTag())
        val flow = LearningFlow(rig.engine, rig.host, rig.store, rig.clock) { language }
        flow.applySettings(learning)
        flow.startField(
            FieldSession(
                generation = rig.orchestrator.session.generation + 1,
                policy = FieldPolicy.of(
                    passwordField = false,
                    privateField = false,
                    learningEnabled = learning.learningEnabled,
                    heatmapEnabled = learning.heatmapEnabled,
                ),
                addressField = false,
                terminalField = false,
            ),
        )
        for (run in runs) {
            for ((at, word) in run.withIndex()) {
                // A capital after a run's first word is typed with shift.
                flow.record(
                    word, run.getOrNull(at - 1), run.getOrNull(at - 2),
                    deliberateCapital = at > 0 && word.first().isUpperCase(),
                )
                rig.settle()
                rig.clock.advance((word.length + 1) * TypingRig.KEY_INTERVAL_MILLIS)
            }
        }
        flow.finishField()
        rig.settle()
    }

    /** [read] of the personal dictionary the rig's flushes wrote, after [learnHistory]. */
    fun <T> fromStore(read: suspend (DictionaryRepository) -> T): T = rig.read(read)

    /**
     * The model the service loads at start from what [learnHistory] left in the personal dictionary
     * ([PersonalModelLoad.read]), under this pipeline's settings, its languages' offensive words as
     * the loader reads them from the shipped lists, and its clock.
     */
    fun personalSnapshot(): PersonalModelLoad {
        val offensiveWords = OffensiveWords.merge(
            languages.map { tag ->
                val file = File(OFFENSIVE_DIRECTORY, "$tag.txt")
                if (file.isFile) OffensiveWords.parse(file.readText()) else emptySet()
            },
        )
        return fromStore { dictionary ->
            PersonalModelLoad.read(dictionary, settings(), offensiveWords, rig.clock.now)
        }
    }

    /**
     * The words the engine is handed after [previous] in [language]'s pairs, or after [before] and
     * [previous] in its triples when [before] is given, as the store keys them ([sliceAfter]).
     */
    fun heldAfter(language: String, previous: String, before: String? = null): Set<String> =
        sliceAfter(language, previous, before).links.mapTo(LinkedHashSet()) { it.word }

    /** How many times the engine is handed each of [heldAfter]'s words chosen on purpose after its context. */
    fun choicesAfter(language: String, previous: String, before: String? = null): Map<String, Int> =
        sliceAfter(language, previous, before).links.associate { it.word to it.asserted }

    /** The total and the observations the engine is handed for the context of [heldAfter]'s words; zeros for one not held. */
    fun contextCounts(language: String, previous: String, before: String? = null): List<Long> =
        sliceAfter(language, previous, before).let { listOf(it.total, it.seen) }

    /** Each word the lookup holds, as it keys it, by the id the engine's word table gave it. */
    fun wordKeys(): Map<Int, String> = workingCopy.lookup.heldWords().associate { it.id to it.entry.key }

    /** The links the lookup holds after the context of [heldAfter]'s words, in the order it offers them. */
    fun linksAfter(language: String, previous: String, before: String? = null): PersonalLookup.ContextLinks {
        val lookup = workingCopy.lookup
        val previous1 = PhraseKey.word(previous)
        return if (before == null) {
            lookup.pairsAfter(language, previous1)
        } else {
            lookup.triplesAfter(language, PhraseKey.word(before), previous1)
        }
    }

    /**
     * The links the engine holds after [previous], or after [before] and [previous] when [before]
     * is given, in [language]'s store, read back from the slice a request there hands it
     * ([EngineWorkingCopy.contextFor], [NativePredictor.nativeReadSlice]): each link's word as the
     * lookup keys it ([keys], each word's by its id), its count, choices and observations at its
     * last use, in the order handed, and the context's total and observations; the language's clock
     * is not handed and reads zero.
     */
    fun sliceAfter(
        language: String,
        previous: String,
        before: String? = null,
        keys: Map<Int, String> = wordKeys(),
    ): PersonalLookup.ContextLinks {
        workingCopy.contextFor(language, previous, before)
        val header = IntArray(SliceRows.HEADER_FIELDS)
        NativePredictor.nativeReadSlice(handle, header, IntArray(0), IntArray(0), LongArray(0))
        val pairs = header[SliceRows.PAIRS]
        val links = pairs + header[SliceRows.TRIPLES]
        val linkInts = IntArray(links * SliceRows.LINK_FIELDS)
        val seen = LongArray(SliceRows.CONTEXT_SEEN_FIELDS + links)
        NativePredictor.nativeReadSlice(handle, header, linkInts, IntArray(0), seen)
        val triples = before != null
        val held = (if (triples) pairs until links else 0 until pairs).map { at ->
            val row = at * SliceRows.LINK_FIELDS
            PersonalLookup.HeldLink(
                keys[linkInts[row + SliceRows.LINK_WORD]].orEmpty(), linkInts[row + SliceRows.LINK_COUNT],
                linkInts[row + SliceRows.LINK_ASSERTED], seen[SliceRows.CONTEXT_SEEN_FIELDS + at], 0L,
            )
        }
        val total = header[if (triples) SliceRows.TRIPLE_TOTAL else SliceRows.PAIR_TOTAL].toLong()
        return PersonalLookup.ContextLinks(held, total, seen[if (triples) SliceRows.TRIPLE_SEEN else SliceRows.PAIR_SEEN])
    }

    /**
     * Runs [block] with the engine's personal model replaced by [personal] and held there: the
     * words typed meanwhile are learned into the store but not into the engine, and their pairs
     * and triples into neither.
     */
    fun <T> withPersonal(personal: PersonalModelLoad, block: () -> T): T {
        rig.settle()
        load(personal)
        rig.engine.learns = false
        try {
            return block()
        } finally {
            rig.engine.learns = true
        }
    }

    /**
     * Types [typed] and a space into a field holding [previous2], [previous1] and a space, each
     * left out when null, nothing when both are, and reads back what the field kept, with the
     * engine's ranking for [typed] there before it was typed.
     */
    fun commitAfter(typed: String, previous2: String?, previous1: String?): Reading {
        rig.orchestrator.applySettings(settings())
        rig.startField(listOfNotNull(previous2, previous1).joinToString("") { "$it " })
        val answer = answer(typed, previous1, previous2)
        val ranking = rankingOf(answer)
        val ranked = answer.words.filterNotNull()
        val outcome = typeWord(typed)
        return Reading(outcome, readingLine(outcome, ranking), ranked)
    }

    /** Autocorrect's list for [typed], best first, as the bridge hands it over. */
    fun corrections(typed: String, previous: String? = null): List<CorrectionOffer> =
        answer(typed, previous, null).corrections

    /** The buffers the engine's answers are read through. */
    private val scratch = AnswerScratch()

    /** Each word of [phrase] typed in turn, with a space after it, into one field. */
    fun commitPhrase(phrase: String): List<Outcome> {
        rig.orchestrator.applySettings(settings())
        rig.startField()
        return phrase.split(' ').filter { it.isNotEmpty() }.map { typeWord(it) }
    }

    /** The pack the conversation is taken to be in, or -1 while undecided; moved by [commit]. */
    fun dominantPack(): Int = NativePredictor.nativeDominantPack(handle)

    /** The slot the engine holds [tag]'s pack in, or [NativePredictor.NO_PACK]. */
    fun packOf(tag: String): Int =
        (0 until LanguagePackRepository.MAX_ENABLED).firstOrNull { NativePredictor.nativePackTag(handle, it) == tag }
            ?: NativePredictor.NO_PACK

    /**
     * Closes [old]'s pack and opens [new]'s, enabled after the others, as the loader does when the
     * enabled languages change.
     */
    fun swapLanguage(old: String, new: String) {
        val kept = languages - old
        NativePredictor.nativeSetActiveLanguages(handle, kept.toTypedArray(), FloatArray(kept.size) { 1.0f })
        loadPack(handle, new)
        languages = kept + new
        NativePredictor.nativeSetActiveLanguages(handle, languages.toTypedArray(), FloatArray(languages.size) { 1.0f })
    }

    /**
     * The engine's account of [word]'s score for [typed], the learned pairs and triples read in
     * [language]'s store, by default the rig's, or null when it is not offered.
     */
    fun explain(typed: String, word: String, language: String = rig.orchestrator.language): ScoreExplanation? =
        ScoreExplanation.read(handle, typed, word, workingCopy.contextFor(language, null, null))

    /** The answer about [word] alone as if [packIndex] were the language, or null for no such pack. */
    fun answerAs(packIndex: Int, word: String): PredictionAnswer? =
        answerRequestAs(handle, packIndex, word, languages, scratch) { workingCopy.contextFor(it, null, null) }

    /** The language lock, one of KeyboardPreferences' LANGUAGE_LOCK_ steps, as the Languages screen sets it. */
    fun languageLock(step: Int) {
        lock = step
        NativePredictor.nativeSetLanguageLock(
            handle, KeyboardPreferences.languageLockEvidence(step), KeyboardPreferences.languageLockStrict(step),
        )
    }

    /** The "Rare words" step, as the keyboard sets it from KeyboardPreferences.rareWords. */
    fun rareWords(step: Int) =
        NativePredictor.nativeSetKnownWordReach(handle, KeyboardPreferences.rareWordsMinLogProb(step))

    /** The language searched first while none has been recognised; empty clears it. */
    fun preferredLanguage(tag: String) {
        preferred = tag
        NativePredictor.nativeSetPreferredLanguage(handle, tag.ifEmpty { null })
    }

    /** Forgets which language is being written, as a field that starts undecided does. */
    fun forgetLanguage() = NativePredictor.nativeResetLanguageEvidence(handle)

    /** The evidence gathered for each open language, by tag. */
    fun languageEvidence(): Map<String, Float> =
        languages.associateWith { NativePredictor.nativeLanguageEvidence(handle, it) }

    /** Puts back evidence [languageEvidence] read, and the verdict it decides. */
    fun restoreLanguageEvidence(evidence: Map<String, Float>) {
        for ((tag, value) in evidence) {
            NativePredictor.nativeSetLanguageEvidence(handle, tag, value)
        }
    }

    /**
     * Records [word] in the personal dictionary the way the service does. [asserted] is a commit
     * the keyboard reads as a choice; anything else is a delimiter typed past the word.
     */
    fun learn(word: String, asserted: Boolean = false, times: Int = 1) {
        repeat(times) {
            workingCopy.commit(
                Commit(
                    LearnedWord(word, rig.orchestrator.language, 1, rig.clock.now, asserted = if (asserted) 1 else 0),
                    null, null, rig.orchestrator.language,
                ),
            )
        }
    }

    /** Replaces the personal model with [model], as the loader hands one over ([EngineWorkingCopy.load]). */
    fun load(model: PersonalModelLoad) = workingCopy.load(model)

    /** Replaces the personal model with [words], each used [uses] times, and no pairs or triples ([load]). */
    fun loadWords(words: List<String>, uses: Int) =
        load(PersonalSnapshot.EMPTY.copy(words = words.map { UserWord(it, LanguageTags.primary(languages.first()), uses, rig.clock.now) }))

    /** Replaces the blocked words, the way the service pushes them. */
    fun block(vararg words: String) = NativePredictor.nativeSetBlockedWords(handle, arrayOf(*words))

    fun close() = NativePredictor.nativeDestroy(handle)

    companion object {
        /** The corpora the native tests share, relative to the module the tests run in. */
        const val CORPUS_DIRECTORY = "../native-tests/data"

        /** The shipped apostrophe maps, relative to the module the tests run in. */
        private const val CONTRACTIONS_DIRECTORY = "src/main/assets/contractions"

        /** The shipped offensive-word lists, relative to the module the tests run in. */
        private const val OFFENSIVE_DIRECTORY = "src/main/assets/offensive"

        /** KeyboardPreferences.CORRECTION_DISTANCE_NORMAL, the shipped default. */
        private const val DEFAULT_DISTANCE = 1

        /** The weight a language the harness opens takes. */
        private const val EQUAL_WEIGHT = 1.0f

        /** The weight of [open]'s preferred language, above the others'. */
        private const val PREFERRED_WEIGHT = 2.0f

        /** The language lock's evidence floor with no language decided. */
        private const val UNDECIDED_EVIDENCE = 0.0f

        /**
         * A writer for the readings file [name] in the `borderkeys.readings` directory, or null
         * when that property is unset; with [append], the file is added to rather than replaced.
         * With [measures], the file is a rows file for `scripts/compare_readings.py`: it opens
         * with the line naming them, and each row is written by [row].
         */
        fun readings(name: String, append: Boolean = false, measures: List<String> = emptyList()): PrintWriter? {
            val directory = System.getProperty("borderkeys.readings")?.let(::File) ?: return null
            directory.mkdirs()
            return rowsFile(FileWriter(File(directory, name), append), measures)
        }

        /**
         * [out] as a rows file, in the format `native-tests/rows_file.hpp` describes: it opens with
         * the line naming [measures], when there are any.
         */
        fun rowsFile(out: Writer, measures: List<String>): PrintWriter =
            PrintWriter(out).also { writer ->
                if (measures.isNotEmpty()) {
                    writer.println("# measures: ${measures.joinToString(" ")}")
                }
            }

        /** The measure of a rows file whose one verdict is whether the row came out as it expects. */
        const val RIGHT = "right"

        /** The measures of a rows file that places a word in a ranking: first, and among the first [TOP_THREE]. */
        const val FIRST = "first"
        const val IN_TOP_THREE = "top-three"

        /** The places a rank counts as the top three, as `native-tests/rows_file.hpp` counts them. */
        const val TOP_THREE = 3

        /** What a reading writes for a word the ranking does not hold. */
        const val ABSENT = "absent"

        /** What a reading writes for the zero-based [rank], -1 for a word not held: its place from 1, else [ABSENT]. */
        fun placeOf(rank: Int): String = if (rank < 0) ABSENT else (rank + 1).toString()

        /** A row of a rows file ([rowsFile]): [verdicts] as 1 or 0, then [key] and [reading], tab-separated. */
        fun row(verdicts: List<Boolean>, key: String, reading: String): String =
            verdicts.joinToString("\t") { if (it) "1" else "0" } + "\t$key\t$reading"

        /** Where the compiled packs are, or null when they have not been built. */
        fun packDirectory(): File? =
            System.getProperty("borderkeys.packs")?.let(::File)?.takeIf { it.isDirectory }

        /** Skips when the host bridge or the packs are missing; fails instead under CI. */
        fun require() {
            if (available()) {
                return
            }
            val missing = "the pipeline harness needs the host bridge (cmake --build " +
                "native-tests/build --target borderkeys) and compiled packs " +
                "(gradlew :keyboard:buildDictionaries)"
            if (System.getenv("CI") != null) {
                throw AssertionError("$missing -- and CI is expected to have built both")
            }
            throw AssumptionViolatedException(missing)
        }

        /**
         * Whether the host bridge (`cmake --build native-tests/build --target borderkeys`) and the
         * compiled packs are present.
         */
        fun available(): Boolean {
            val packs = packDirectory() ?: return false
            if (packs.listFiles { f -> f.name.endsWith(".bkd") }.isNullOrEmpty()) {
                return false
            }
            return runCatching { NativePredictor.nativeCreate() }
                .onSuccess { if (it != 0L) NativePredictor.nativeDestroy(it) }
                .getOrDefault(0L) != 0L
        }

        /** An engine with [tags] loaded, weighted equally, and a QWERTY geometry set. */
        fun open(vararg tags: String): Pipeline = open(null, *tags)

        /** [open], with [preferred] weighted above the other languages; null leaves them equal. */
        fun open(preferred: String?, vararg tags: String): Pipeline {
            val handle = NativePredictor.nativeCreate()
            check(handle != 0L) { "the engine would not start" }
            for (tag in tags) {
                loadPack(handle, tag)
            }
            NativePredictor.nativeSetActiveLanguages(handle, Array(tags.size) { tags[it] }, weightsOf(tags, preferred))
            // Undecided.
            NativePredictor.nativeSetLanguageLock(handle, UNDECIDED_EVIDENCE, false)
            qwerty(handle)
            // The shipped apostrophe maps of these languages, merged as on a phone.
            val contractions = Contractions.of(
                tags.map { tag ->
                    val file = File(CONTRACTIONS_DIRECTORY, "$tag.txt")
                    if (file.isFile) Contractions.parse(file.readText()) else emptyList()
                },
                tags.toList(),
            )
            // And their pairs, as DictionaryLoader reads them.
            val twins = Contractions.twinsOf(
                tags.map { tag ->
                    val file = File(CONTRACTIONS_DIRECTORY, "$tag.pairs.txt")
                    tag to (if (file.isFile) Contractions.parsePairs(file.readText()) else emptyList())
                },
            )
            return Pipeline(handle, contractions, twins, tags.toList())
        }

        /**
         * Opens [tags]' shipped packs in [engine] through its own calls, set up as [open] sets up
         * a handle: loaded and active, equally weighted, undecided, on the harness's layout.
         * [open]'s apostrophe maps belong to the harness and are not loaded. The packs are in
         * place once [engine]'s thread has run what was posted.
         */
        fun openPacks(engine: PredictionEngine, vararg tags: String) {
            for (tag in tags) {
                val file = packFile(tag)
                val stream = FileInputStream(file)
                engine.loadLanguage(tag, PackDescriptor(stream, rawDescriptor(stream.fd), file.length()), EQUAL_WEIGHT)
            }
            engine.setActiveLanguages(Array(tags.size) { tags[it] }, weightsOf(tags, null))
            // Undecided.
            engine.setLanguageLock(UNDECIDED_EVIDENCE, false)
            val keys = qwertyKeys()
            engine.setKeyGeometry(keys.size, KEY_WIDTH, KEY_HEIGHT) { codes, xs, ys ->
                for ((index, key) in keys.withIndex()) {
                    codes[index] = key.first
                    xs[index] = key.second
                    ys[index] = key.third
                }
                keys.size
            }
        }

        /** [tags]' weights: [preferred] at [PREFERRED_WEIGHT], every other one at [EQUAL_WEIGHT]. */
        private fun weightsOf(tags: Array<out String>, preferred: String?): FloatArray =
            FloatArray(tags.size) { if (tags[it] == preferred) PREFERRED_WEIGHT else EQUAL_WEIGHT }

        /** The shipped pack for [tag]. */
        private fun packFile(tag: String): File {
            val packs = requireNotNull(packDirectory()) { "borderkeys.packs is not set" }
            val file = File(packs, tag.replace('-', '_') + ".bkd")
            check(file.isFile) { "no pack at $file" }
            return file
        }

        /** Loads the shipped pack for [tag] into the engine at [handle]. */
        private fun loadPack(handle: Long, tag: String) {
            val file = packFile(tag)
            FileInputStream(file).use { stream ->
                val status = NativePredictor.nativeLoadLanguage(
                    handle, tag, rawDescriptor(stream.fd), 0L, file.length(), EQUAL_WEIGHT,
                )
                check(status == 0) { "loadLanguage($tag) refused the pack: $status" }
            }
        }

        /**
         * What the engine's validator says of the pack at [file]: `nativeInspectPack`'s numbers,
         * or null when it refused the pack.
         */
        fun inspect(file: File): IntArray? {
            val out = IntArray(LanguagePackInspector.INSPECT_SLOTS)
            val tag = FileInputStream(file).use { stream ->
                NativePredictor.nativeInspectPack(rawDescriptor(stream.fd), 0L, file.length(), out)
            }
            return if (tag != null) out else null
        }

        /** The raw descriptor behind [descriptor], through `--add-opens java.base/java.io`. */
        private fun rawDescriptor(descriptor: FileDescriptor): Int =
            FileDescriptor::class.java.getDeclaredField("fd")
                .apply { isAccessible = true }
                .getInt(descriptor)

        private fun qwerty(handle: Long) {
            val keys = qwertyKeys()
            NativePredictor.nativeSetKeyGeometry(
                handle, keys.map { it.first }.toIntArray(), keys.map { it.second }.toFloatArray(),
                keys.map { it.third }.toFloatArray(), KEY_WIDTH, KEY_HEIGHT, IntArray(0), IntArray(0),
            )
        }

        /** Each key of the harness's layout, in the order [keyIndex] counts them: its letter's code and centre. */
        private fun qwertyKeys(): List<Triple<Int, Float, Float>> =
            QWERTY_ROWS.flatMap { letters ->
                letters.map { letter -> keyCentre(letter).let { (x, y) -> Triple(letter.code, x, y) } }
            }

        /** [letter]'s key in the order [qwerty] hands the engine its geometry, or -1. */
        fun keyIndex(letter: Char): Int {
            var index = 0
            for (letters in QWERTY_ROWS) {
                val column = letters.indexOf(letter)
                if (column >= 0) {
                    return index + column
                }
                index += letters.length
            }
            return -1
        }

        /** The centre of [letter]'s key on the harness's layout, in its pixels. */
        fun keyCentre(letter: Char): Pair<Float, Float> {
            val row = QWERTY_ROWS.indexOfFirst { letter in it }
            require(row >= 0) { "no key for '$letter'" }
            val column = QWERTY_ROWS[row].indexOf(letter)
            return (ROW_INDENTS[row] + column + 0.5f) * KEY_WIDTH to (row + 0.5f) * KEY_HEIGHT
        }

        private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val ROW_INDENTS = listOf(0f, 0.5f, 1.5f)
        private const val KEY_WIDTH = 108f
        private const val KEY_HEIGHT = 160f

        /** The Latin swipe model, relative to the module the tests run in. */
        private const val LATIN_MODEL = "src/plus/assets/model.bkw"
    }
}

/**
 * A whole pack file as the asset descriptor [PredictionEngine.loadLanguage] reads: [raw], the
 * descriptor of [stream], from offset zero for [size] bytes; closing it closes [stream].
 */
private class PackDescriptor(private val stream: FileInputStream, private val raw: Int, private val size: Long) :
    AssetFileDescriptor(null, 0L, size) {

    override fun getParcelFileDescriptor(): ParcelFileDescriptor = object : ParcelFileDescriptor(null) {
        override fun getFd(): Int = raw
    }

    override fun getStartOffset(): Long = 0L

    override fun getLength(): Long = size

    override fun close() = stream.close()
}
