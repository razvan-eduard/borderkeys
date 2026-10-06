// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.Contractions
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
    private val languages: List<String>,
) {

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
        val path = SwipePath.through(word, rig.clock)
        val words = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
        val properNoun = BooleanArray(PredictionEngine.MAX_RESULTS)
        val found = NativePredictor.nativeDecodeGesture(
            handle, path.xs, path.ys, path.timestamps, path.count, previous, null,
            words, FloatArray(PredictionEngine.MAX_RESULTS), properNoun,
        )
        val decoded = (0 until found).mapNotNull { index -> words[index]?.let { Candidate(it, properNoun[index]) } }
        rig.swipe(word)
        rig.press(' '.code)
        rig.settle()
        val written = rig.editor.text.substring(start, rig.editor.selectionEnd).removeSuffix(" ")
        val offered = rig.orchestrator.caseSwipedWords(decoded).map { it.text }
        return Swiped(written, decoded.map { it.text }, offered)
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
        autoCapitalise = true,
        minCorrectionLength = minimumLength,
        correctionDistance = correctionDistance,
        capitaliseNames = capitaliseNames,
    )

    /** What the suggestion strip would show for [typed], in order. */
    fun strip(typed: String, previous: String? = null): List<String> =
        answerRequest(handle, typed, previous, null, languages, scratch).words.filterNotNull()

    /**
     * [outcome] as one tab-separated line: typed, committed or `-`, reason, then the engine's
     * ranking for the typed word, each entry `word:score`, a name marked `word*:score`.
     */
    fun readingLine(outcome: Outcome): String {
        val answer = answerRequest(handle, outcome.typed, null, null, languages, scratch)
        val ranking = answer.words.indices.joinToString(" ") { index ->
            val name = if (answer.properNoun[index]) "*" else ""
            "${answer.words[index]}$name:" + String.format(Locale.ROOT, "%.4f", answer.scores[index])
        }
        return "${outcome.typed}\t${outcome.committed ?: "-"}\t${outcome.reason}\t$ranking"
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

    /** What one pack alone would spell [word] as. */
    fun candidateForPack(packIndex: Int, word: String): String? =
        NativePredictor.nativeCandidateForPack(handle, packIndex, word)

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
            NativePredictor.nativeLearn(handle, word, null, null, false, asserted)
        }
    }

    /** Replaces the blocked words, the way the service pushes them. */
    fun block(vararg words: String) = NativePredictor.nativeSetBlockedWords(handle, arrayOf(*words))

    fun close() = NativePredictor.nativeDestroy(handle)

    companion object {
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
            val packs = requireNotNull(packDirectory()) { "borderkeys.packs is not set" }
            val handle = NativePredictor.nativeCreate()
            check(handle != 0L) { "the engine would not start" }
            for (tag in tags) {
                val file = File(packs, tag.replace('-', '_') + ".bkd")
                check(file.isFile) { "no pack at $file" }
                FileInputStream(file).use { stream ->
                    val status = NativePredictor.nativeLoadLanguage(
                        handle, tag, rawDescriptor(stream.fd), 0L, file.length(), 1.0f,
                    )
                    check(status == 0) { "loadLanguage($tag) refused the pack: $status" }
                }
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
