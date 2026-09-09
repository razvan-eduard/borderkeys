// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.assist

import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask

/**
 * Runs a task over text too long for one request by splitting it and stitching the answers back
 * together, one request at a time.
 *
 * The model has one context window, shared between the prompt and the answer. A selection too
 * long for it fails outright without this -- [AssistClient.run] returning -1, the same answer it
 * gives a genuinely absent assistant, for a request that is simply too big rather than
 * impossible. Splitting at sentence boundaries and running each piece as its own request answers
 * a long selection instead, at the cost of the pieces never seeing each other's context --
 * acceptable for [AssistTask.isChunkable] tasks, where each sentence is transformed close to on
 * its own terms anyway, and refused for the ones where it is not (see that property's own doc).
 *
 * Owns [client]'s listener rather than sharing it: a caller that wants both raw single-request
 * results and chunked ones would have to arbitrate between two things claiming the same reply,
 * which is a problem this avoids by being the only thing that talks to the client. Every request
 * -- long or short, chunked or not -- goes through [run].
 *
 * A chunk after the first tells the service it may decode against whatever memory the previous
 * chunk left rather than starting over (see [dispatch]'s `continueJob`), since consecutive chunks
 * of one job share a prompt prefix byte for byte -- the same task's fixed instruction and
 * template wrapper, nothing about either chunk's own text. This does not change what a chunk's
 * answer can be transformed from: the pieces still never see each other's actual content, only
 * the same fixed words every chunk of every job of that task starts from.
 */
class ChunkedAssistRunner(private val client: AssistClient) {

    interface Listener {
        /**
         * `truncated` is true when [resultText] -- the whole job's stitched-together answer --
         * has at least one chunk that stopped short of where the model itself would have
         * stopped. One chunk cut short is enough to make the whole answer incomplete, whatever
         * the other chunks did.
         */
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

    // The measured ratio for whatever model is currently loaded on the other side of the
    // process boundary, replacing the fixed guess in maxChunkChars's default as soon as one
    // becomes known. Stays at the guess for the very first request of a session, before any
    // status query or run has had a model loaded to measure.
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
     * Runs [task] over [text]. Returns a job id, or -1 for the same reasons [AssistClient.run]
     * would refuse the whole text as a single request -- chunking changes how a request that
     * would have been accepted anyway gets sent, it does not relax what gets accepted at all.
     *
     * [contextTokens] is the active model's own window (see [maxChunkChars]'s doc for why the
     * chunk size is derived from it rather than fixed), and only asked for -- never assumed --
     * because a caller with the wrong number would silently oversize every chunk it produces.
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

    /** Stops the job in flight, if any. A reply to it that still arrives is simply not current. */
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
            // A single space between pieces: each one already ends at a sentence boundary (or,
            // for the one-chunk case that is almost every request, is the whole answer), so a
            // space is what separates two sentences that were never going to touch anyway.
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
        // Every chunk after the first shares this job's task and instruction with the one
        // before it -- the one case two requests are allowed to share anything of each other's
        // state at all. See AssistClient.run's continueJob doc.
        val requestId = client.run(current.task, current.chunks[current.nextIndex],
                                   current.instruction, continueJob = current.nextIndex > 0)
        if (requestId < 0) {
            return false
        }
        current.inFlightRequestId = requestId
        return true
    }

    companion object {
        // A rule of thumb, used only until the loaded model's own measured ratio is known (see
        // the charsPerToken field) -- being wrong about it costs a chunk boundary landing a
        // little early or late, not a request that fails, which is what makes a rough default
        // safe to fall back on for the first request of a session.
        private const val DEFAULT_CHARS_PER_TOKEN = 4f

        // Rough token count of the fixed parts of a prompt that are not the chunk itself: the
        // task's own instruction sentence, the chat template's turn markers, "/no_think", and
        // the "Text:\n\"\"\"\n"..."\n\"\"\"" wrapper applyChatTemplate adds. Overestimated on
        // purpose -- this is subtracted from the budget, so guessing high costs a chunk a few
        // tokens smaller than it needed to be, guessing low risks the one thing this exists to
        // avoid.
        private const val FIXED_OVERHEAD_TOKENS = 80

        // The most expensive per-chunk-token cost among the chunkable tasks: the chunk's own
        // tokens, once for the input and again for AssistTask.outputRatio's highest value among
        // them (1.5, every TRANSLATE_TO_* task) for the answer. A chunk sized against this ratio
        // is safe for every chunkable task, including the cheaper ones.
        private const val WORST_CASE_TOKENS_PER_CHUNK_TOKEN = 1.0 + 1.5

        // What fraction of the model's own window this leaves as margin, on top of the fixed
        // overhead already subtracted -- context accounting from characters is an estimate, not
        // a token count, and this is the room for that estimate to be wrong in either direction
        // without the request still overflowing the window it was sized against.
        private const val CONTEXT_SAFETY_FRACTION = 0.85

        private const val MIN_CHUNK_CHARS = 256

        /**
         * A safe chunk size in characters for a model with [contextTokens] of window.
         *
         * Proportional to the model actually loaded, not a fixed number: a chunk sized for the
         * smallest context this application will run with (512 tokens, see kMinContextTokens in
         * text_assist.cpp) would split a selection into far more requests than a model with
         * sixteen times that window ever needed, and the reverse -- a fixed size picked for a
         * generous model -- would overflow a smaller one's context outright, the exact failure
         * this class exists to stop happening.
         *
         * [charsPerToken] defaults to a generic rule of thumb, and should be the loaded model's
         * own measured ratio whenever one is known -- see the field of the same name's own doc.
         */
        fun maxChunkChars(contextTokens: Int, charsPerToken: Float = DEFAULT_CHARS_PER_TOKEN): Int {
            val safeTokens = (contextTokens * CONTEXT_SAFETY_FRACTION - FIXED_OVERHEAD_TOKENS) /
                WORST_CASE_TOKENS_PER_CHUNK_TOKEN
            val chars = (safeTokens * charsPerToken).toInt()
            return chars.coerceIn(MIN_CHUNK_CHARS, AssistProtocol.MAX_SELECTION_CHARS)
        }

        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
        private val WORD_BOUNDARY = Regex("\\s+")

        /**
         * Splits [text] into pieces no longer than [maxChars], each ending at a sentence
         * boundary where one exists within reach -- never mid-sentence, which is the whole
         * point: a translation or a correction asked to work from half a sentence has no way to
         * know how the other half will read.
         *
         * A single chunk, unsplit, when [text] already fits -- true of nearly every request this
         * application makes, so the common case costs one length comparison and no regex at all.
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
                // A "sentence" that alone exceeds the chunk size -- run-on text with no
                // punctuation the regex above could have split on -- is packed by word instead,
                // rather than sent as one oversized chunk or, worse, cut at an arbitrary
                // character offset in the middle of a word.
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
