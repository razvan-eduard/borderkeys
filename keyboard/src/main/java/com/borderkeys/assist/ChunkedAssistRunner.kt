// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask

/**
 * Runs a task over a selection, split at sentence boundaries into requests that fit the model's
 * window when the task [AssistTask.isChunkable], one request at a time, and joins the answers.
 * Owns [client]'s listener; every request goes through [run].
 */
class ChunkedAssistRunner(private val client: AssistClient) {

    interface Listener {
        /** `truncated` is true when any chunk of [resultText] was cut short. */
        fun onChunkedResult(id: Int, resultText: String, modelName: String?, truncated: Boolean)
        fun onChunkedError(id: Int, error: Int)
        fun onAssistAvailability(available: Boolean, modelName: String?)
    }

    var listener: Listener? = null

    private class Job(
        val jobId: Int,
        val task: AssistTask,
        val instruction: String,
        val chunks: List<String>,
    ) {
        val results = arrayOfNulls<String>(chunks.size)
        var nextIndex = 0
        var inFlightRequestId = -1
        var modelName: String? = null
        var truncated = false
    }

    private var job: Job? = null
    private var nextJobId = 1

    // The loaded model's measured characters per token, once known.
    private var charsPerToken = DEFAULT_CHARS_PER_TOKEN

    init {
        client.listener = object : AssistClient.Listener {
            override fun onAssistResult(
                requestId: Int,
                text: String,
                modelName: String?,
                truncated: Boolean,
            ) {
                val current = job ?: return
                if (requestId != current.inFlightRequestId) {
                    return
                }
                current.results[current.nextIndex] = text
                if (modelName != null) {
                    current.modelName = modelName
                }
                current.truncated = current.truncated || truncated
                current.nextIndex++
                advance(current)
            }

            override fun onAssistError(requestId: Int, error: Int) {
                val current = job ?: return
                if (requestId != current.inFlightRequestId) {
                    return
                }
                job = null
                listener?.onChunkedError(current.jobId, error)
            }

            override fun onAssistAvailability(
                available: Boolean,
                modelName: String?,
                charsPerToken: Float,
            ) {
                if (charsPerToken > 0f) {
                    this@ChunkedAssistRunner.charsPerToken = charsPerToken
                }
                listener?.onAssistAvailability(available, modelName)
            }
        }
    }

    fun isAvailable(): Boolean = client.isAvailable()

    fun queryAvailability() = client.queryAvailability()

    /**
     * Runs [task] over [text], chunked for [contextTokens], the active model's window. Returns a
     * job id, or -1 when [AssistClient.run] would refuse the whole text.
     */
    fun run(task: AssistTask, text: String, contextTokens: Int, instruction: String = ""): Int {
        if (text.isEmpty() || text.length > AssistProtocol.MAX_SELECTION_CHARS) {
            return -1
        }
        if (task == AssistTask.CUSTOM &&
            (instruction.isBlank() || instruction.length > AssistTask.MAX_INSTRUCTION_CHARS)
        ) {
            return -1
        }
        val chunks = if (task.isChunkable) {
            splitIntoChunks(text, maxChunkChars(contextTokens, charsPerToken))
        } else {
            listOf(text)
        }
        val newJob = Job(nextJobId++, task, instruction, chunks)
        job = newJob
        if (!dispatch(newJob)) {
            job = null
            return -1
        }
        return newJob.jobId
    }

    /** Stops the job in flight, if any; a reply to it that still arrives is ignored. */
    fun cancel() {
        job = null
        client.cancel()
    }

    fun disconnect() {
        job = null
        client.disconnect()
    }

    private fun advance(current: Job) {
        if (current.nextIndex >= current.chunks.size) {
            job = null
            val joined = current.results.joinToString(" ") { it.orEmpty() }
            listener?.onChunkedResult(current.jobId, joined, current.modelName, current.truncated)
            return
        }
        if (!dispatch(current)) {
            job = null
            listener?.onChunkedError(current.jobId, AssistProtocol.ERROR_FAILED)
        }
    }

    private fun dispatch(current: Job): Boolean {
        val requestId = client.run(current.task, current.chunks[current.nextIndex],
                                   current.instruction, continueJob = current.nextIndex > 0)
        if (requestId < 0) {
            return false
        }
        current.inFlightRequestId = requestId
        return true
    }

    companion object {
        // Characters per token until the loaded model's own ratio is known.
        private const val DEFAULT_CHARS_PER_TOKEN = 4f

        // An upper bound on a prompt's tokens besides the chunk: the instruction, the template's
        // turn markers, "/no_think" and the text wrapper.
        private const val FIXED_OVERHEAD_TOKENS = 80

        // Tokens per chunk token for the costliest chunkable task: the input once, plus the answer
        // at the largest AssistTask.outputRatio (1.5).
        private const val WORST_CASE_TOKENS_PER_CHUNK_TOKEN = 1.0 + 1.5

        // The share of the model's window a request may fill.
        private const val CONTEXT_SAFETY_FRACTION = 0.85

        private const val MIN_CHUNK_CHARS = 256

        /** The chunk size in characters for a model with [contextTokens] of window. */
        fun maxChunkChars(contextTokens: Int, charsPerToken: Float = DEFAULT_CHARS_PER_TOKEN): Int {
            val safeTokens = (contextTokens * CONTEXT_SAFETY_FRACTION - FIXED_OVERHEAD_TOKENS) /
                WORST_CASE_TOKENS_PER_CHUNK_TOKEN
            val chars = (safeTokens * charsPerToken).toInt()
            return chars.coerceIn(MIN_CHUNK_CHARS, AssistProtocol.MAX_SELECTION_CHARS)
        }

        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
        private val WORD_BOUNDARY = Regex("\\s+")

        /**
         * Splits [text] at sentence boundaries into pieces no longer than [maxChars]; [text]
         * itself when it fits.
         */
        fun splitIntoChunks(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) {
                return listOf(text)
            }
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in text.split(SENTENCE_BOUNDARY)) {
                if (sentence.isEmpty()) {
                    continue
                }
                // A sentence longer than a chunk is split at word boundaries.
                val pieces = if (sentence.length > maxChars) {
                    packWords(sentence, maxChars)
                } else {
                    listOf(sentence)
                }
                for (piece in pieces) {
                    if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                        chunks += current.toString()
                        current.setLength(0)
                    }
                    if (current.isNotEmpty()) {
                        current.append(' ')
                    }
                    current.append(piece)
                }
            }
            if (current.isNotEmpty()) {
                chunks += current.toString()
            }
            return chunks
        }

        private fun packWords(text: String, maxChars: Int): List<String> {
            val pieces = mutableListOf<String>()
            val current = StringBuilder()
            for (word in text.split(WORD_BOUNDARY)) {
                if (word.isEmpty()) {
                    continue
                }
                if (current.isNotEmpty() && current.length + 1 + word.length > maxChars) {
                    pieces += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) {
                    current.append(' ')
                }
                current.append(word)
            }
            if (current.isNotEmpty()) {
                pieces += current.toString()
            }
            return pieces
        }
    }
}
