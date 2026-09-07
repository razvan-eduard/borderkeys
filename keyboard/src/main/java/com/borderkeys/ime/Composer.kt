// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The draft box's versions, and the rules for moving between them.
 *
 * Its own class, with no Android in it, because this is where the feature can be got wrong in a
 * way nobody notices until work has been destroyed. The same argument as [AutoCorrection]: the
 * policy is worth being able to test without a view, an editor and a model.
 *
 * The shape is a line of nodes with a finger on one of them:
 *
 *  - node 0 is the original -- the text taken from the selection, or, for a box opened empty,
 *    whatever was in it when the model was first asked to do something;
 *  - a model's answer is appended, and running the model from a node that is not the last one
 *    **discards everything after it**. That is the only thing that destroys a version;
 *  - editing by hand changes the node you are standing on and discards nothing, so walking back
 *    to read something and fixing a typo while you are there does not cost you the rest.
 */
class Composer {

    private val versions = ArrayList<String>()

    /** Which node the box is showing. Meaningless while [hasHistory] is false. */
    var index: Int = 0
        private set

    /**
     * Where the flip returns to.
     *
     * The flip is a toggle between the original and the node you were reading -- not the newest
     * one, because reading node n-3 and flipping twice should put you back on node n-3.
     */
    private var flippedFrom: Int = -1

    val size: Int get() = versions.size

    /** True once the model has produced at least one version, which is when the line appears. */
    val hasHistory: Boolean get() = versions.size > 1

    val atOriginal: Boolean get() = versions.isNotEmpty() && index == 0

    val canGoBack: Boolean get() = index > 0

    val canGoForward: Boolean get() = index < versions.size - 1

    fun versionAt(position: Int): String? = versions.getOrNull(position)

    fun current(): String? = versions.getOrNull(index)

    /** Nothing has been asked of the model yet; the box is just a box. */
    fun isEmpty(): Boolean = versions.isEmpty()

    fun clear() {
        versions.clear()
        index = 0
        flippedFrom = -1
    }

    /**
     * Records what the text looked like before a model run, and returns the node it is at.
     *
     * Called with the box's live contents at the moment an action is tapped. The first call
     * establishes the original -- unless the caller already has, which is the normal case: a
     * selection seeds node 0 the moment the box opens, before anything can be typed into it, so
     * that a box opened empty is the only time this call is the one doing the establishing.
     * Later calls fold in whatever was edited by hand since the node was last written, through
     * [updateCurrent] and its rule about node 0.
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

    /**
     * Adds a model's answer after the node it was asked from, discarding anything beyond it.
     *
     * Running the model from the middle of the line is a decision to take that version forward,
     * and the versions that used to follow it are no longer reachable from anywhere.
     */
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

    /**
     * Keeps an edit made by hand.
     *
     * Every node but the original updates in place, discarding nothing -- reading an old
     * version and fixing a typo while you are there should not cost you the rest of the line.
     * The original is the one thing this class promises never changes: "the exact copy
     * gathered from the initial page selection, nothing else." Standing on it and typing does
     * not get to be the exception, so the edit opens a new node right after it instead of
     * overwriting it -- an insert, not the truncate-and-append a model result does from the
     * middle of the line, so whatever already followed the original keeps following, now one
     * position further along, rather than being taken as the thing this edit is replacing.
     */
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

    /**
     * Shows the original, or returns from it to wherever the flip was pressed.
     *
     * A toggle rather than a jump to the start: the point is to compare, and a comparison you
     * cannot come back from is a one-way trip.
     */
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

    /**
     * Drops the oldest version that is not the original when the line gets too long.
     *
     * Node 0 is never dropped. It is the one a user is promised they can always get back to, and
     * a promise that expires after twenty rewrites is not one worth making.
     */
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
        /** Twenty full copies of a bounded text is nothing; twenty dots in a row is already a lot. */
        const val MAX_VERSIONS = 20

        /**
         * A short name for a prompt, from the prompt itself.
         *
         * A button is about a dozen characters and no instruction worth writing fits in one, so
         * something has to be dropped. The leading verb goes first -- every prompt starts with
         * "rewrite", "make", "turn" -- and then the words that carry no meaning on their own, and
         * what is left is usually the two words that say what the prompt is for: "rewrite this as
         * a polite refusal" becomes "polite refusal".
         *
         * Deliberately not a job for the model. It would cost a few seconds, come back wrong
         * often enough to matter, and the user would want to edit it anyway -- which is what the
         * field this fills is for.
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
                // Nothing survived the filter, which means the prompt is all short words. Take
                // it as written rather than hand back an empty name.
                kept.addAll(words.take(NAME_WORDS))
            }
            return kept.joinToString(" ").take(MAX_NAME_CHARS).trim()
        }

        /** Mirrors SavedPrompt.MAX_NAME_CHARS; duplicated so this stays free of :data. */
        const val MAX_NAME_CHARS = 24

        private const val NAME_WORDS = 3

        private val NAME_SEPARATORS = charArrayOf(' ', '\n', '\t', ',', '.', ';', ':', '!', '?')

        /** What a prompt almost always opens with, and what therefore says nothing about it. */
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
