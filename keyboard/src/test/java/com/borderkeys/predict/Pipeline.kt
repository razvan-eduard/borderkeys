// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.InMemoryDictionary
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.PhraseRecorder
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.Contractions
import com.borderkeys.ime.OffensiveWords
import com.borderkeys.typing.FieldPolicy
import com.borderkeys.typing.FieldSession
import com.borderkeys.typing.LearningFlow
import com.borderkeys.typing.QueuedEngine
import com.borderkeys.typing.SwipePath
import com.borderkeys.typing.TypingOrchestrator
import com.borderkeys.typing.TypingRig
import kotlinx.coroutines.runBlocking
import org.junit.AssumptionViolatedException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileWriter
import java.io.PrintWriter
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
    internal data class Outcome(val typed: String, val committed: String?, val reason: String)

    /** Whether the Learning switch is on; off, no case learns from the ones typed before it. */
    var learning = false

    private val rig = typingRig(settings())

    /** A rig of its own on this engine, with [settings], for typing a scenario key by key. */
    fun typingRig(settings: KeyboardPreferences): TypingRig =
        TypingRig(QueuedEngine(handle, languages), settings).also {
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
            if (word.isNotEmpty() && !TypingOrchestrator.isWordCharacter(code)) {
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

    /** The language whose learned pairs and triples a decode of a swipe through [word]'s keys read. */
    fun swipeLanguage(word: String): String? = decode(word, null).second

    /** The decoder's words for a swipe through [word]'s keys after [previous], and the language it read. */
    private fun decode(word: String, previous: String?): Pair<List<Candidate>, String?> {
        val path = SwipePath.through(word, rig.clock)
        val words = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
        val properNoun = BooleanArray(PredictionEngine.MAX_RESULTS)
        val texts = arrayOfNulls<String>(NativePredictor.TEXT_SLOTS)
        val found = NativePredictor.nativeDecodeGesture(
            handle, path.xs, path.ys, path.timestamps, path.count, previous, null,
            words, FloatArray(PredictionEngine.MAX_RESULTS), properNoun, texts,
        )
        val decoded = (0 until found).mapNotNull { index -> words[index]?.let { Candidate(it, properNoun[index]) } }
        return decoded to texts[NativePredictor.TEXT_LANGUAGE]
    }

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

    /** The settings the keyboard runs with, the three the corpora vary given. */
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
    )

    /** What the suggestion strip would show for [typed], in order. */
    fun strip(typed: String, previous: String? = null): List<String> =
        answerRequest(handle, typed, previous, null, languages, scratch).words.filterNotNull()

    /** The language whose learned pairs and triples the answer about [typed] read. */
    fun answerLanguage(typed: String): String? =
        answerRequest(handle, typed, null, null, languages, scratch).language

    /**
     * [outcome] as one tab-separated line: typed, committed or `-`, reason, then the engine's
     * ranking for the typed word, each entry `word:score`, a name marked `word*:score`.
     */
    fun readingLine(outcome: Outcome): String = readingLine(outcome, ranking(outcome.typed, null, null))

    private fun readingLine(outcome: Outcome, ranking: String): String =
        "${outcome.typed}\t${outcome.committed ?: "-"}\t${outcome.reason}\t$ranking"

    /** The engine's ranking for [typed] after [previous1] and [previous2], as [readingLine] writes it. */
    private fun ranking(typed: String, previous1: String?, previous2: String?): String {
        val answer = answerRequest(handle, typed, previous1, previous2, languages, scratch)
        return answer.words.indices.joinToString(" ") { index ->
            val name = if (answer.properNoun[index]) "*" else ""
            "${answer.words[index]}$name:" + String.format(Locale.ROOT, "%.4f", answer.scores[index])
        }
    }

    /** What a word typed after a context became, and its reading line there. */
    internal data class Reading(val outcome: Outcome, val line: String)

    /**
     * Learns [runs] as the orchestrator learns committed words: each word with the two before
     * it in its run, through a [LearningFlow] on this pipeline's engine and store, the engine
     * answering before the next word and the clock moving as if each word and a space were typed.
     * What it buffered is written at the end.
     */
    fun learnHistory(runs: List<List<String>>) {
        val learning = settings().copy(learningEnabled = true)
        val flow = LearningFlow(rig.engine, rig.host, rig.store, rig.clock)
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
        // The language the engine answers in, as a typed word's answer would name it.
        answerRequest(handle, "", null, null, languages, scratch).language?.let(flow::answeredIn)
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

    /**
     * [read] of the store after [learnHistory], each batch written as a flush writes it
     * ([com.borderkeys.typing.LearningBatch.writeTo]) into a database of its own, and of what
     * those writes gave up beyond the engine's. Needs Robolectric.
     */
    fun <T> fromStore(read: suspend (DictionaryRepository, PhraseRecorder.Dropped) -> T): T =
        InMemoryDictionary(ApplicationProvider.getApplicationContext()).use { dictionary ->
            runBlocking {
                var dropped = PhraseRecorder.Dropped()
                for (batch in rig.store.batches) {
                    dropped += batch.writeTo(dictionary.repository)
                }
                read(dictionary.repository, dropped)
            }
        }

    /**
     * The model the service loads at start from what [learnHistory] left in the store
     * ([PersonalModelLoad.read]), under this pipeline's settings, its languages' offensive words
     * as the loader reads them, and its clock. Needs Robolectric.
     */
    fun personalSnapshot(): PersonalModelLoad {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val offensiveWords = OffensiveWords.merge(languages.map { OffensiveWords.load(assets, it) })
        return fromStore { dictionary, _ ->
            PersonalModelLoad.read(dictionary, settings(), offensiveWords, rig.clock.now)
        }
    }

    /**
     * The words the engine holds after [previous] in [language]'s pairs, or after [before] and
     * [previous] in its triples when [before] is given, as the store keys them.
     */
    fun heldAfter(language: String, previous: String, before: String? = null): Set<String> {
        val held = NativePredictor.nativeContextLinks(
            handle, language, before, previous, emptyArray(), IntArray(0), LongArray(0),
            LongArray(NativePredictor.CONTEXT_SLOTS),
        )
        val words = arrayOfNulls<String>(held)
        NativePredictor.nativeContextLinks(
            handle, language, before, previous, words, IntArray(held), LongArray(held),
            LongArray(NativePredictor.CONTEXT_SLOTS),
        )
        return words.filterNotNull().toSet()
    }

    /**
     * The total and the observations ([NativePredictor.CONTEXT_TOTAL], [NativePredictor.CONTEXT_SEEN])
     * the engine holds for the context of [heldAfter]'s words; zeros for one not held.
     */
    fun contextCounts(language: String, previous: String, before: String? = null): List<Long> {
        val counted = LongArray(NativePredictor.CONTEXT_SLOTS)
        NativePredictor.nativeContextLinks(
            handle, language, before, previous, emptyArray(), IntArray(0), LongArray(0), counted,
        )
        return counted.toList()
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
     * Types [typed] and a space into a field holding [previous2], [previous1] and a space, and
     * reads back what the field kept, with the engine's ranking for [typed] there before it was
     * typed.
     */
    fun commitAfter(typed: String, previous2: String, previous1: String): Reading {
        rig.orchestrator.applySettings(settings())
        rig.startField("$previous2 $previous1 ")
        val ranking = ranking(typed, previous1, previous2)
        val outcome = typeWord(typed)
        return Reading(outcome, readingLine(outcome, ranking))
    }

    /** Autocorrect's list for [typed], best first, as the bridge hands it over. */
    fun corrections(typed: String, previous: String? = null): List<CorrectionOffer> =
        answerRequest(handle, typed, previous, null, languages, scratch).corrections

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

    /** The engine's account of [word]'s score for [typed], or null when it is not offered. */
    fun explain(typed: String, word: String): ScoreExplanation? = ScoreExplanation.read(handle, typed, word)

    /** The answer about [word] alone as if [packIndex] were the language, or null for no such pack. */
    fun answerAs(packIndex: Int, word: String): PredictionAnswer? =
        answerRequestAs(handle, packIndex, word, languages, scratch)

    /** Evidence thresholds, as the Languages screen sets them. */
    fun languageLock(minimumEvidence: Float, strict: Boolean = false) =
        NativePredictor.nativeSetLanguageLock(handle, minimumEvidence, strict)

    /** The "Rare words" step, as the keyboard sets it from KeyboardPreferences.rareWords. */
    fun rareWords(step: Int) =
        NativePredictor.nativeSetKnownWordReach(handle, KeyboardPreferences.rareWordsMinLogProb(step))

    /** The language searched first while none has been recognised; null clears it. */
    fun preferredLanguage(tag: String?) = NativePredictor.nativeSetPreferredLanguage(handle, tag)

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
     * Records [word] in the personal dictionary the way the service does. [asserted] is a tap
     * on the strip or a reverted correction; anything else is a delimiter typed past the word.
     */
    fun learn(word: String, asserted: Boolean = false, times: Int = 1) {
        repeat(times) {
            NativePredictor.nativeLearn(handle, word, null, null, false, asserted, null, true)
        }
    }

    /** Replaces the personal model with [model], as the loader hands one over ([PersonalModelLoad.loadInto]). */
    fun load(model: PersonalModelLoad) = model.loadInto(handle)

    /** Replaces the personal model with [words], each used [uses] times, and no pairs or triples ([load]). */
    fun loadWords(words: List<String>, uses: Int) =
        load(PersonalSnapshot.EMPTY.copy(words = words.map { UserWord(it, languages.first(), uses, rig.clock.now) }))

    /** Replaces the blocked words, the way the service pushes them. */
    fun block(vararg words: String) = NativePredictor.nativeSetBlockedWords(handle, arrayOf(*words))

    fun close() = NativePredictor.nativeDestroy(handle)

    companion object {
        /** The corpora the native tests share, relative to the module the tests run in. */
        const val CORPUS_DIRECTORY = "../native-tests/data"

        /** The shipped apostrophe maps, relative to the module the tests run in. */
        private const val CONTRACTIONS_DIRECTORY = "src/main/assets/contractions"

        /** KeyboardPreferences.CORRECTION_DISTANCE_NORMAL, the shipped default. */
        private const val DEFAULT_DISTANCE = 1

        /**
         * A writer for the readings file [name] in the `borderkeys.readings` directory, or null
         * when that property is unset; with [append], the file is added to rather than replaced.
         */
        fun readings(name: String, append: Boolean = false): PrintWriter? {
            val directory = System.getProperty("borderkeys.readings")?.let(::File) ?: return null
            directory.mkdirs()
            return PrintWriter(FileWriter(File(directory, name), append))
        }

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
            NativePredictor.nativeSetActiveLanguages(
                handle,
                Array(tags.size) { tags[it] },
                FloatArray(tags.size) { if (tags[it] == preferred) 2.0f else 1.0f },
            )
            // Undecided.
            NativePredictor.nativeSetLanguageLock(handle, 0.0f, false)
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

        /** Loads the shipped pack for [tag] into the engine at [handle]. */
        private fun loadPack(handle: Long, tag: String) {
            val packs = requireNotNull(packDirectory()) { "borderkeys.packs is not set" }
            val file = File(packs, tag.replace('-', '_') + ".bkd")
            check(file.isFile) { "no pack at $file" }
            FileInputStream(file).use { stream ->
                val status = NativePredictor.nativeLoadLanguage(
                    handle, tag, rawDescriptor(stream.fd), 0L, file.length(), 1.0f,
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
            val codes = ArrayList<Int>()
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (letters in QWERTY_ROWS) {
                for (letter in letters) {
                    val (x, y) = keyCentre(letter)
                    codes += letter.code
                    xs += x
                    ys += y
                }
            }
            NativePredictor.nativeSetKeyGeometry(
                handle, codes.toIntArray(), xs.toFloatArray(), ys.toFloatArray(),
                KEY_WIDTH, KEY_HEIGHT, IntArray(0), IntArray(0),
            )
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
