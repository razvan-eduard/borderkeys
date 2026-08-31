// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * The Kotlin side of the JNI boundary, and nothing else.
 *
 * Every declaration here has an exact counterpart in `kMethods` in `jni_bridge.cpp`. The native
 * side binds them through `RegisterNatives` in `JNI_OnLoad`, so a signature that drifts out of
 * sync fails at [System.loadLibrary] -- at service creation, in the open -- rather than as an
 * `UnsatisfiedLinkError` on the first keystroke with the keyboard already on screen.
 *
 * An `object` rather than a class: there is exactly one native library in the process and
 * loading it twice is not a thing that can happen, so the singleton initialiser is the correct
 * place for [System.loadLibrary] and there is no instance state to justify anything else.
 *
 * **Threading.** The engine is not thread safe, deliberately: it is a single-writer structure
 * with a bump allocator and no locks, because a lock on the suggestion path would be paid on
 * every keystroke to protect against a second caller that does not exist. Everything below must
 * be called from one thread -- the dedicated prediction thread created in step 5 -- with the
 * single exception of [nativeCreate] and [nativeDestroy], which the service calls from its own
 * lifecycle callbacks while no request is in flight.
 *
 * **Allocation.** [nativeSuggest] fills arrays the caller owns and reuses. The only objects it
 * creates are the result strings, which only the VM can make, and it runs off the UI thread.
 */
internal object NativePredictor {

    init {
        System.loadLibrary("borderkeys")
    }

    /** Returns an opaque handle, or 0 if the engine could not be created. */
    external fun nativeCreate(): Long

    /**
     * Releases the engine and everything it mapped. The caller must guarantee no other native
     * call is in flight or will follow; the handle is dangling afterwards.
     */
    external fun nativeDestroy(handle: Long)

    /**
     * Maps and validates a `.bkd` language pack from an open file descriptor.
     *
     * The descriptor is not taken over -- the mapping keeps the file alive on its own, so the
     * caller closes it either way. `offset` and `length` describe a window, which is what lets a
     * pack be read straight out of the APK's asset region without being copied out first.
     *
     * Returns 0 on success, or a negative `BkdStatus` code. Failure is a returned value, not an
     * exception: the native side is built with exceptions disabled.
     */
    external fun nativeLoadLanguage(
        handle: Long,
        tag: String,
        fd: Int,
        offset: Long,
        length: Long,
        weight: Float,
    ): Int

    /**
     * Describes a `.bkd` without loading it into an engine.
     *
     * Fills [out] with `{ status, formatVersion, wordCount }` and returns the pack's BCP-47
     * language tag, or null when the pack was refused -- in which case `out[0]` carries the
     * negative `BkdStatus` saying why. The same validation the engine applies before it will
     * read a pack: magic, version, header checksum, every section offset against the real file
     * size, and the content checksum.
     *
     * Unlike everything else here this touches no engine, so it has no handle and no thread
     * restriction beyond not being called on the UI thread -- it checksums the whole file.
     */
    external fun nativeInspectPack(fd: Int, offset: Long, length: Long, out: IntArray): String?

    /**
     * Sets which of the loaded packs take part in scoring, and with what weight.
     *
     * All of them at once, not one "current language". Someone writing Romanian and English in
     * the same sentence is the normal case; a keyboard that makes them switch has already lost.
     */
    external fun nativeSetActiveLanguages(handle: Long, tags: Array<String>, weights: FloatArray)

    /**
     * Pushes the physical layout down to the engine so that it can correct finger slips.
     *
     * This is the whole of what the engine knows about how the keyboard is drawn: key centres
     * and a key size, in the same pixel space the view uses. It never learns that a Canvas
     * exists, and the view never learns that a trie does.
     */
    external fun nativeSetKeyGeometry(
        handle: Long,
        codes: IntArray,
        centersX: FloatArray,
        centersY: FloatArray,
        keyWidth: Float,
        keyHeight: Float,
    )

    /**
     * Fills [outWords] and [outScores] with the best candidates and returns how many were
     * written, best first.
     *
     * Both arrays are allocated once by the caller and reused for every request. An empty
     * [composing] is legitimate and asks for a next-word prediction from the context alone.
     */
    external fun nativeSuggest(
        handle: Long,
        composing: String,
        prev1: String?,
        prev2: String?,
        outWords: Array<String?>,
        outScores: FloatArray,
    ): Int

    /**
     * Records that the user confirmed [word] in this context.
     *
     * This is the entire learning rule of the project: a count goes up. Nothing is retrained and
     * no gradient exists, which is also why the keyboard cannot slowly learn the user's typos --
     * a count only moves when a word was deliberately chosen.
     */
    external fun nativeLearn(handle: Long, word: String, prev1: String?, prev2: String?)

    /**
     * Decodes a swipe into candidates, best first, and returns how many were written.
     *
     * The arrays carry the raw touch samples in view pixels, exactly as the driver reported
     * them -- including the historical samples inside each motion event, which are the ones
     * carrying the curvature. Smoothing and resampling happen on the native side so the
     * geometric and neural tiers cannot disagree about how the features were produced.
     *
     * [outWords] and [outScores] are the same reused buffers the tap path uses; this runs on
     * the prediction thread, never on the UI thread.
     */
    external fun nativeDecodeGesture(
        handle: Long,
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        prev1: String?,
        prev2: String?,
        outWords: Array<String?>,
        outScores: FloatArray,
    ): Int

    /** Replaces the in-memory personal dictionary. Called once at start, from Room. */
    external fun nativeLoadUserWords(handle: Long, words: Array<String>, counts: IntArray)

    /**
     * Writes the personal dictionary to [path] in the app's private storage. Returns 0 on
     * success. Called on a debounce and at `onFinishInput`, never on a keystroke: Kotlin decides
     * when, the native side only executes.
     */
    external fun nativeSnapshotUserModel(handle: Long, path: String): Int
}
