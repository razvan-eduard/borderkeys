// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

/**
 * The JNI surface of the assistant, and the only Kotlin that touches llama.cpp.
 *
 * Loaded in the `:assist` process and nowhere else. The keyboard process never links this
 * library, never maps a model, and has no way to: the class does not exist in the free build at
 * all, and in the `plus` build it lives behind a process boundary.
 *
 * Every call blocks. [nativeRun] blocks for as long as generation takes, which is seconds. It is
 * called from the service's worker thread, never from a binder thread and never from a main
 * looper.
 */
internal object AssistNative {

    init {
        System.loadLibrary("borderkeysassist")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    /**
     * Maps a GGUF model. Returns 0 on success, or a negative status.
     *
     * The caller has already re-hashed the file. This is the point of no return: after it, the
     * process holds hundreds of megabytes and is the largest thing on the device.
     */
    external fun nativeLoad(handle: Long, path: String, contextTokens: Int, threads: Int): Int

    /**
     * Replaces the sampler's temperature and nucleus (top-p). Safe before or after [nativeLoad]
     * -- see `TextAssist::setSamplingParams`'s own doc, in text_assist.hpp, for what each case
     * does. Out-of-range values fall back to the built-in default rather than being rejected.
     */
    external fun nativeSetSamplingParams(handle: Long, temperature: Float, topP: Float)

    /** Frees the model. Called on the idle timeout, so a finished session costs nothing. */
    external fun nativeUnload(handle: Long)

    external fun nativeIsLoaded(handle: Long): Boolean

    external fun nativeContextTokens(handle: Long): Int

    /**
     * Runs one instruction over one piece of text. Returns null on failure, with the reason in
     * `outStatus[0]`.
     *
     * `outTruncated[0]`, meaningful only when a non-null result comes back, is set to whether the
     * answer was cut short of where the model itself would have stopped rather than reaching it.
     * A separate array from `outStatus` on purpose -- a status code and a truncation flag are
     * different kinds of thing, and sharing one array by position is how a later change quietly
     * breaks what a given slot means. Both travel back through their own caller-supplied array
     * rather than a second call, so neither can be separated from the request that produced it.
     *
     * `cleanFormatting` should be false for [com.borderkeys.data.assist.AssistTask.CUSTOM] and
     * true for every built-in task -- see `TextAssist::cleanResult`'s own doc, in text_assist.cpp,
     * for why a custom instruction is the one case the native side's own formatting cleanup has
     * to stay out of.
     *
     * `maxOutputTokens` should be [com.borderkeys.data.assist.AssistTask.outputTokenBudget], and
     * `useRemainingContext` should be the same task's `usesRemainingContext` -- see that
     * property's own doc for which tasks want the real space left in the context window to
     * govern generation instead of the length-based guess.
     */
    external fun nativeRun(
        handle: Long,
        instruction: String,
        text: String,
        maxOutputTokens: Int,
        useRemainingContext: Boolean,
        cleanFormatting: Boolean,
        outStatus: IntArray,
        outTruncated: BooleanArray,
    ): String?

    /** Asks the running generation to stop at the next token. Safe from another thread. */
    external fun nativeCancel(handle: Long)
}
