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

    /** Frees the model. Called on the idle timeout, so a finished session costs nothing. */
    external fun nativeUnload(handle: Long)

    external fun nativeIsLoaded(handle: Long): Boolean

    external fun nativeContextTokens(handle: Long): Int

    /**
     * Runs one instruction over one piece of text. Returns null on failure, with the reason in
     * `outStatus[0]`.
     *
     * The status travels in a caller-supplied array rather than a second call, so that a failure
     * and its reason cannot be separated by another request.
     */
    external fun nativeRun(
        handle: Long,
        instruction: String,
        text: String,
        maxOutputTokens: Int,
        outStatus: IntArray,
    ): String?

    /** Asks the running generation to stop at the next token. Safe from another thread. */
    external fun nativeCancel(handle: Long)
}
