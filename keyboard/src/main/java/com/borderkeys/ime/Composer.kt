// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The draft box's versions, a line of nodes with one showing. Node 0 is the original. A model's
 * answer is appended after the node it was asked from, discarding the nodes beyond it; an edit by
 * hand changes the node showing, or on the original inserts a node after it.
 */
class Composer {

    private val versions = ArrayList<String>()

    /** Which node the box is showing. Meaningless while [hasHistory] is false. */
    var index: Int = 0
        private set

    /** The node [flip] returns to from the original. */
    private var flippedFrom: Int = -1

    val size: Int get() = versions.size

    /** True once the model has produced at least one version. */
    val hasHistory: Boolean get() = versions.size > 1

    val atOriginal: Boolean get() = versions.isNotEmpty() && index == 0

    val canGoBack: Boolean get() = index > 0

    val canGoForward: Boolean get() = index < versions.size - 1

    fun versionAt(position: Int): String? = versions.getOrNull(position)

    fun current(): String? = versions.getOrNull(index)

    /** True until the model has been asked for anything. */
    fun isEmpty(): Boolean = versions.isEmpty()

    fun clear() {
        versions.clear()
        index = 0
        flippedFrom = -1
    }

    /**
     * Records the box's text before a model run and returns its node: the original on the first
     * call, else through [updateCurrent].
     */
    fun captureBeforeRun(text: String): Int {
        if (versions.isEmpty()) {
            versions.add(text)
            index = 0
            flippedFrom = -1
            return index
        }
        updateCurrent(text)
        return index
    }

    /** Adds a model's answer after the node it was asked from, discarding anything beyond it. */
    fun addResult(result: String) {
        if (versions.isEmpty()) {
            versions.add(result)
            index = 0
            return
        }
        while (versions.size > index + 1) {
            versions.removeAt(versions.size - 1)
        }
        versions.add(result)
        index = versions.size - 1
        flippedFrom = -1
        trim()
    }

    /** Keeps an edit made by hand: in place, or, on the original, as a new node right after it. */
    fun updateCurrent(text: String) {
        if (versions.isEmpty() || text == versions[index]) {
            return
        }
        if (index == 0) {
            versions.add(1, text)
            index = 1
            flippedFrom = -1
            trim()
            return
        }
        versions[index] = text
        flippedFrom = -1
    }

    fun back(): String? {
        if (!canGoBack) {
            return null
        }
        index -= 1
        flippedFrom = -1
        return current()
    }

    fun forward(): String? {
        if (!canGoForward) {
            return null
        }
        index += 1
        flippedFrom = -1
        return current()
    }

    /** Jumps to a node, for a tap or a drag along the line. */
    fun goTo(position: Int): String? {
        if (position !in versions.indices || position == index) {
            return current()
        }
        index = position
        flippedFrom = -1
        return current()
    }

    /** Shows the original, or returns from it to the node the flip was pressed on. */
    fun flip(): String? {
        if (versions.isEmpty()) {
            return null
        }
        if (index == 0) {
            val back = flippedFrom
            flippedFrom = -1
            return goTo(if (back in versions.indices) back else versions.size - 1)
        }
        val from = index
        val text = goTo(0)
        flippedFrom = from
        return text
    }

    /** Drops the oldest versions after the original while there are more than [MAX_VERSIONS]. */
    private fun trim() {
        while (versions.size > MAX_VERSIONS) {
            versions.removeAt(1)
            index -= 1
        }
        if (index < 0) {
            index = 0
        }
    }

    companion object {
        /** The most versions kept, the original included. */
        const val MAX_VERSIONS = 20

        /**
         * A short name for a prompt: up to [NAME_WORDS] of its words after a leading verb, filler
         * words skipped, cut to [MAX_NAME_CHARS].
         */
        fun suggestedName(prompt: String): String {
            val words = prompt.lowercase()
                .split(*NAME_SEPARATORS)
                .filter { it.isNotBlank() }
            var start = 0
            if (start < words.size && words[start] in LEADING_VERBS) {
                start += 1
            }
            val kept = ArrayList<String>(NAME_WORDS)
            var index = start
            while (index < words.size && kept.size < NAME_WORDS) {
                val word = words[index]
                if (word !in STOP_WORDS) {
                    kept.add(word)
                }
                index += 1
            }
            if (kept.isEmpty()) {
                // With every word filtered out, the first words as written.
                kept.addAll(words.take(NAME_WORDS))
            }
            return kept.joinToString(" ").take(MAX_NAME_CHARS).trim()
        }

        /** Same as CustomAction.MAX_NAME_CHARS. */
        const val MAX_NAME_CHARS = 24

        private const val NAME_WORDS = 3

        private val NAME_SEPARATORS = charArrayOf(' ', '\n', '\t', ',', '.', ';', ':', '!', '?')

        /** Verbs dropped from the start of a prompt. */
        private val LEADING_VERBS = setOf(
            "rewrite", "write", "make", "turn", "change", "convert", "fix", "correct",
            "translate", "shorten", "summarise", "summarize", "reword", "rephrase", "put",
        )

        private val STOP_WORDS = setOf(
            "the", "this", "that", "it", "a", "an", "as", "into", "in", "to", "of", "for",
            "with", "and", "or", "but", "be", "is", "are", "was", "were", "more", "less",
            "text", "please",
        )
    }
}
