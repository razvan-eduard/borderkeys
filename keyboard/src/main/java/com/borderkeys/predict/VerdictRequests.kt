// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * The requests for learnable verdicts the engine has accepted and not yet answered. Each request
 * is answered exactly once: by the work [submit] posts, with its verdicts, or with every word
 * learnable when the post is refused or the holder is closed first. Answers are called outside
 * the lock.
 */
internal class VerdictRequests {

    private class Request(val words: List<String>, val onResult: (List<Boolean>) -> Unit)

    private val held = HashSet<Request>()
    private var closed = false

    /**
     * Asks for the verdicts on [words], delivered once to [onResult]. The work handed to [post]
     * answers with [judge]'s verdicts if the request is still unanswered when it runs. When the
     * holder is closed, or [post] returns false, every word is answered learnable at once.
     */
    fun submit(
        words: List<String>,
        onResult: (List<Boolean>) -> Unit,
        post: (Runnable) -> Boolean,
        judge: (List<String>) -> List<Boolean>,
    ) {
        val request = Request(words, onResult)
        if (!hold(request)) {
            answerLearnable(request)
            return
        }
        val posted = post(Runnable {
            if (claim(request)) {
                request.onResult(judge(request.words))
            }
        })
        if (!posted && claim(request)) {
            answerLearnable(request)
        }
    }

    /** Closes the holder and answers every request still held, every word learnable. */
    fun close() {
        closeHeld().forEach(::answerLearnable)
    }

    /** Marks the holder closed, empties it and returns the requests it held. */
    @Synchronized
    private fun closeHeld(): List<Request> {
        closed = true
        return held.toList().also { held.clear() }
    }

    /** Holds [request]; false once the holder is closed. */
    @Synchronized
    private fun hold(request: Request): Boolean {
        if (closed) {
            return false
        }
        held += request
        return true
    }

    /** Whether [request] was still held; true for one caller only. */
    @Synchronized
    private fun claim(request: Request): Boolean = held.remove(request)

    private fun answerLearnable(request: Request) {
        request.onResult(List(request.words.size) { true })
    }
}
