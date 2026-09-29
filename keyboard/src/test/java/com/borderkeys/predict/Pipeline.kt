// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.Contractions
import com.borderkeys.ime.WordCommit
import com.borderkeys.ime.WordStems
import org.junit.AssumptionViolatedException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.PrintWriter
import java.util.Locale

/**
 * The engine, the JNI bridge and [WordCommit] run off a device against the shipped packs, with
 * corrections and auto-capitalise on and no shortcuts. The editor, the composing region,
 * delimiter handling and field state are not covered.
 */
internal class Pipeline private constructor(
    private val handle: Long,
    private val contractions: Map<String, String>,
    private val languages: List<String>,
) {

    /** What a word would become; [reason] is [WordCommit.Outcome.reason]. */
    internal data class Outcome(val typed: String, val committed: String?, val reason: String)

    /** Runs one word as a delimiter would; [previous] is the word before it. */
    fun commit(
        typed: String,
        previous: String? = null,
        minimumLength: Int = 3,
        correctionDistance: Int = DEFAULT_DISTANCE,
        capitaliseNames: Boolean = true,
    ): Outcome {
        val words = arrayOfNulls<String>(MAX_CANDIDATES)
        val scores = FloatArray(MAX_CANDIDATES)
        val properNoun = BooleanArray(MAX_CANDIDATES)
        NativePredictor.nativeSuggest(
            handle, typed, previous, null, words, scores, properNoun, IntArray(1),
        )

        val isName = BooleanArray(1)
        val correction = NativePredictor.nativeBestCorrection(handle, isName)
        val spelling = NativePredictor.nativeKnownSpelling(handle, typed)
        val knownWord = if (spelling != null && spelling.equals(typed, ignoreCase = true)) typed else ""
        val possessive = NativePredictor.nativePossessive(handle, typed)
        val inflection = correction != null &&
            WordStems.shields(typed, correction, knownStems(typed), languages)

        val outcome = WordCommit.decide(
            typed = typed,
            fromGesture = false,
            runningText = true,
            shortcuts = emptyList(),
            contractions = contractions,
            possessive = possessive,
            suggestion = correction,
            suggestionQuery = typed,
            knownWord = knownWord,
            isProperNoun = isName[0],
            inflection = inflection,
            settings = WordCommit.Settings(
                autoCorrectOnSpace = true,
                autoCapitalise = true,
                minimumLength = minimumLength,
                correctionDistance = correctionDistance,
                capitaliseNames = capitaliseNames,
            ),
        )
        return Outcome(typed, outcome.text, outcome.reason)
    }

    /** The stems of [typed] the engine holds. */
    private fun knownStems(typed: String): Set<String> {
        val stems = WordStems.candidates(typed, languages).take(NativePredictor.MAX_STEMS_QUERY)
        if (stems.isEmpty()) {
            return emptySet()
        }
        val known = BooleanArray(stems.size)
        NativePredictor.nativeKnownStems(handle, stems.toTypedArray(), known)
        return stems.filterIndexed { index, _ -> known[index] }.toSet()
    }

    /** What the suggestion strip would show for [typed], in order. */
    fun strip(typed: String, previous: String? = null): List<String> {
        val words = arrayOfNulls<String>(MAX_CANDIDATES)
        val scores = FloatArray(MAX_CANDIDATES)
        val properNoun = BooleanArray(MAX_CANDIDATES)
        val n = NativePredictor.nativeSuggest(
            handle, typed, previous, null, words, scores, properNoun, IntArray(1),
        )
        return (0 until n).mapNotNull { words[it] }
    }

    /**
     * [outcome] as one tab-separated line: typed, committed or `-`, reason, then the engine's
     * ranking for the typed word, each entry `word:score`, a name marked `word*:score`.
     */
    fun readingLine(outcome: Outcome): String {
        val words = arrayOfNulls<String>(MAX_CANDIDATES)
        val scores = FloatArray(MAX_CANDIDATES)
        val properNoun = BooleanArray(MAX_CANDIDATES)
        val n = NativePredictor.nativeSuggest(
            handle, outcome.typed, null, null, words, scores, properNoun, IntArray(1),
        )
        val ranking = (0 until n).joinToString(" ") { index ->
            val name = if (properNoun[index]) "*" else ""
            "${words[index]}$name:" + String.format(Locale.ROOT, "%.4f", scores[index])
        }
        return "${outcome.typed}\t${outcome.committed ?: "-"}\t${outcome.reason}\t$ranking"
    }

    /** The ranked words for [typed], and the index of the engine's correction among them, or -1. */
    fun stripWithCorrection(typed: String, previous: String? = null): CorrectionView {
        val words = arrayOfNulls<String>(MAX_CANDIDATES)
        val scores = FloatArray(MAX_CANDIDATES)
        val properNoun = BooleanArray(MAX_CANDIDATES)
        val at = IntArray(1)
        val n = NativePredictor.nativeSuggest(
            handle, typed, previous, null, words, scores, properNoun, at,
        )
        val isName = BooleanArray(1)
        return CorrectionView(
            (0 until n).mapNotNull { words[it] },
            at[0],
            NativePredictor.nativeBestCorrection(handle, isName),
        )
    }

    /** The engine's ranking, the index of its correction in it, and the correction itself. */
    data class CorrectionView(val ranked: List<String>, val correctionAt: Int, val correction: String?)

    /** Each word of [phrase] in turn, every one carrying the word before it as context. */
    fun commitPhrase(phrase: String): List<Outcome> {
        var previous: String? = null
        return phrase.split(' ').filter { it.isNotEmpty() }.map { word ->
            commit(word, previous).also { previous = it.committed ?: word }
        }
    }

    /** The pack the conversation is taken to be in, or -1 while undecided; moved by [commit]. */
    fun dominantPack(): Int = NativePredictor.nativeDominantPack(handle)

    /** What one pack alone would spell [word] as. */
    fun candidateForPack(packIndex: Int, word: String): String? =
        NativePredictor.nativeCandidateForPack(handle, packIndex, word)

    /** Evidence thresholds, as the Languages screen sets them. */
    fun languageLock(minimumEvidence: Float, strict: Boolean = false) =
        NativePredictor.nativeSetLanguageLock(handle, minimumEvidence, strict)

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
        private const val MAX_CANDIDATES = 16

        /** The shipped apostrophe maps, relative to the module the tests run in. */
        private const val CONTRACTIONS_DIRECTORY = "src/main/assets/contractions"

        /** KeyboardPreferences.CORRECTION_DISTANCE_NORMAL, the shipped default. */
        private const val DEFAULT_DISTANCE = 1

        /**
         * A writer for the readings file [name] in the `borderkeys.readings` directory, or null
         * when that property is unset.
         */
        fun readings(name: String): PrintWriter? {
            val directory = System.getProperty("borderkeys.readings")?.let(::File) ?: return null
            directory.mkdirs()
            return File(directory, name).printWriter()
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
            return Pipeline(handle, contractions, tags.toList())
        }

        /** The raw descriptor behind [descriptor], through `--add-opens java.base/java.io`. */
        private fun rawDescriptor(descriptor: FileDescriptor): Int =
            FileDescriptor::class.java.getDeclaredField("fd")
                .apply { isAccessible = true }
                .getInt(descriptor)

        private fun qwerty(handle: Long) {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val indents = listOf(0f, 0.5f, 1.5f)
            val keyWidth = 108f
            val keyHeight = 160f
            val codes = ArrayList<Int>()
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            rows.forEachIndexed { row, letters ->
                letters.forEachIndexed { column, letter ->
                    codes += letter.code
                    xs += (indents[row] + column + 0.5f) * keyWidth
                    ys += (row + 0.5f) * keyHeight
                }
            }
            NativePredictor.nativeSetKeyGeometry(
                handle, codes.toIntArray(), xs.toFloatArray(), ys.toFloatArray(),
                keyWidth, keyHeight,
            )
        }
    }
}
