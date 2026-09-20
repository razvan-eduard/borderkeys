// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.AutoCorrection
import com.borderkeys.ime.RunningText
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * The whole correction path, off a device, over as many words as you care to hand it.
 *
 * `suggest_eval` reaches the engine and stops there. Everything decided afterwards -- the
 * known-word guard, the edit ceiling, the proper-noun rule, whether the word is running text at
 * all -- lives in Kotlin, and was reachable only by typing on a phone or by modelling it a
 * second time in C++. A second implementation is a thing that drifts, and it did: the harness
 * reported `it's` corrected to `its` for a fortnight while a device refused it.
 *
 * So this drives the shipping engine through the shipping JNI bridge and then the shipping
 * Kotlin, against the packs the application ships. Nothing here is a model of the pipeline; it
 * is the pipeline, with the editor and the touch surface left out.
 *
 * What is *not* covered, and would need a device: the composing region, delimiter handling,
 * field state, and everything [com.borderkeys.ime.BorderKeysService] decides around this.
 */
internal class Pipeline private constructor(private val handle: Long) {

    /** What a word would become, and why -- the reason being the point. An outcome without one
     *  can be right by accident, and a guard can stop working while another covers for it. */
    internal data class Outcome(val typed: String, val committed: String?, val situation: AutoCorrection.Situation)

    /**
     * Runs one word exactly as a delimiter would.
     *
     * [previous] is the word before it, which the engine's n-grams read; a phrase is fed through
     * here one word at a time, each carrying the one before it.
     */
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
        NativePredictor.nativeSuggest(handle, typed, previous, null, words, scores, properNoun)

        val isName = BooleanArray(1)
        val correction = NativePredictor.nativeBestCorrection(handle, isName)
        val spelling = NativePredictor.nativeKnownSpelling(handle, typed)
        val knownWord = if (spelling != null && spelling.equals(typed, ignoreCase = true)) typed else ""

        // The service asks this before it asks anything else: an address or a filename is not a
        // sentence, and correcting inside one is the failure the whole guard exists for.
        if (!RunningText.admits(typed) { null }) {
            return Outcome(typed, null, AutoCorrection.Situation.NothingOffered)
        }
        val cased = AutoCorrection.matchCase(typed, correction.orEmpty(), isName[0] && capitaliseNames)
        val situation = AutoCorrection.situationOf(
            typed, correction, typed, knownWord, cased, minimumLength, isName[0],
            AutoCorrection.maxEditsFor(typed.length, correctionDistance), capitaliseNames,
        )
        val committed = AutoCorrection.correctionFor(
            typed, correction, typed, knownWord, minimumLength, isName[0],
            AutoCorrection.maxEditsFor(typed.length, correctionDistance), capitaliseNames,
        )
        return Outcome(typed, committed, situation)
    }

    /** Each word of [phrase] in turn, every one carrying the word before it as context. */
    fun commitPhrase(phrase: String): List<Outcome> {
        var previous: String? = null
        return phrase.split(' ').filter { it.isNotEmpty() }.map { word ->
            commit(word, previous).also { previous = it.committed ?: word }
        }
    }

    fun close() = NativePredictor.nativeDestroy(handle)

    companion object {
        private const val MAX_CANDIDATES = 16
        private const val DEFAULT_DISTANCE = 0

        /** Where the compiled packs are, or null when they have not been built. The same files
         *  the application ships, produced by the `buildDictionaries` Gradle task. */
        fun packDirectory(): File? =
            System.getProperty("borderkeys.packs")?.let(::File)?.takeIf { it.isDirectory }

        /** Whether this machine can run the pipeline at all: the host bridge has to have been
         *  built (`cmake --build native-tests/build --target borderkeys`) and the packs
         *  compiled. Absent either, a caller skips rather than fails -- neither is produced by
         *  an ordinary `./gradlew test`. */
        fun available(): Boolean {
            val packs = packDirectory() ?: return false
            if (packs.listFiles { f -> f.name.endsWith(".bkd") }.isNullOrEmpty()) {
                return false
            }
            return runCatching { NativePredictor.nativeCreate() }
                .onSuccess { if (it != 0L) NativePredictor.nativeDestroy(it) }
                .getOrDefault(0L) != 0L
        }

        /**
         * An engine with [tags] loaded and a QWERTY geometry set.
         *
         * The geometry is not optional and its absence is silent: without it
         * `KeyGeometry::isSet()` is false, the walk never leaves exact-match mode, and the
         * harness measures prefix completion while reporting it as the engine.
         */
        fun open(vararg tags: String): Pipeline {
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
                handle, Array(tags.size) { tags[it] }, FloatArray(tags.size) { 1.0f },
            )
            // Undecided, as a corpus case is: one word with no sentence around it gives the
            // detector nothing to work from, and pinning a pack would measure a different
            // engine than the one someone types their first word into.
            NativePredictor.nativeSetLanguageLock(handle, 0.0f, false)
            qwerty(handle)
            return Pipeline(handle)
        }

        /** The JVM has no public way to a raw file descriptor, and nativeLoadLanguage takes one.
         *  Opened for the tests by `--add-opens java.base/java.io` in the build file. */
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
