// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

/**
 * The JNI surface of the assistant, loaded in the `:assist` process only. Every call blocks,
 * [nativeRun] for as long as generation takes; all are called from the service's worker thread,
 * never from a binder thread or a main looper.
 */
internal object AssistNative {

    init {
        System.loadLibrary("borderkeysassist")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    /** Maps a GGUF model the caller has already re-hashed. Returns 0, or a negative status. */
    external fun nativeLoad(handle: Long, path: String, contextTokens: Int, threads: Int): Int

    /**
     * Replaces the sampler's temperature and nucleus (top-p); safe before or after [nativeLoad].
     * An out-of-range value becomes the built-in default.
     */
    external fun nativeSetSamplingParams(handle: Long, temperature: Float, topP: Float)

    /** Frees the model. Called on the idle timeout. */
    external fun nativeUnload(handle: Long)

    external fun nativeIsLoaded(handle: Long): Boolean

    external fun nativeContextTokens(handle: Long): Int

    /**
     * The loaded model's chars-per-token ratio, measured against a fixed sample at load time, or 0
     * before anything has been loaded.
     */
    external fun nativeCharsPerToken(handle: Long): Float

    /**
     * Runs one instruction over one piece of text. Returns null on failure, with the reason in
     * `outStatus[0]`. `outTruncated[0]`, meaningful only with a non-null result, is set to whether
     * the answer was cut short of where the model would have stopped.
     *
     * `cleanFormatting` is false for [com.borderkeys.data.assist.AssistTask.CUSTOM] and true for
     * every built-in task. `outputRatio`, `minOutputTokens`, `maxOutputTokensCeiling` and
     * `useRemainingContext` are the task's; the native side turns them into a token budget.
     * `reuseSharedPrefix` is true only for a chunk after the first within one
     * [com.borderkeys.assist.ChunkedAssistRunner] job.
     */
    external fun nativeRun(
        handle: Long,
        /** UTF-8 bytes, not a String, in both directions. */
        instruction: ByteArray,
        text: ByteArray,
        outputRatio: Float,
        minOutputTokens: Int,
        maxOutputTokensCeiling: Int,
        useRemainingContext: Boolean,
        reuseSharedPrefix: Boolean,
        cleanFormatting: Boolean,
        outStatus: IntArray,
        outTruncated: BooleanArray,
    ): ByteArray?

    /** Asks the running generation to stop at the next token. Safe from another thread. */
    external fun nativeCancel(handle: Long)
}
