// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The compose key's table: a trie of typed characters, by code point, to the text a sequence
 * spells, from `assets/compose/<name>.json`. No sequence is the start of another; a full match
 * is final.
 */
class ComposeSequences private constructor(private val root: Node, val size: Int) {

    /** Where a sequence typed so far stands. */
    sealed interface Step {
        class Done(val text: String) : Step

        object More : Step

        object NoMatch : Step
    }

    private class Node {
        val children = HashMap<Int, Node>()
        var text: String? = null
    }

    fun step(typed: String): Step {
        var node = root
        var index = 0
        while (index < typed.length) {
            val codePoint = typed.codePointAt(index)
            node = node.children[codePoint] ?: return Step.NoMatch
            index += Character.charCount(codePoint)
        }
        node.text?.let { return Step.Done(it) }
        return if (node.children.isEmpty()) Step.NoMatch else Step.More
    }

    companion object {
        private const val DIRECTORY = "compose"

        val EMPTY = build(emptyMap())

        /** The trie of [table]'s sequences. */
        private fun build(table: Map<String, String>): ComposeSequences {
            val root = Node()
            for ((sequence, text) in table) {
                var node = root
                var index = 0
                while (index < sequence.length) {
                    val codePoint = sequence.codePointAt(index)
                    node = node.children.getOrPut(codePoint) { Node() }
                    index += Character.charCount(codePoint)
                }
                node.text = text
            }
            return ComposeSequences(root, table.size)
        }

        /** The sequences in [json]'s "sequences" object; malformed text gives none. */
        private fun entries(json: String): Map<String, String> = runCatching {
            val sequences = JSONObject(json).getJSONObject("sequences")
            val table = HashMap<String, String>()
            for (sequence in sequences.keys()) {
                val text = sequences.optString(sequence)
                if (sequence.isNotEmpty() && text.isNotEmpty()) {
                    table[sequence] = text
                }
            }
            table
        }.getOrElse { emptyMap() }

        /** The table in [json]'s "sequences" object; malformed text gives none. */
        fun parse(json: String): ComposeSequences = build(entries(json))

        /** Every table under `assets/compose`, merged; none when the directory is missing. */
        fun load(assets: AssetManager): ComposeSequences {
            val merged = HashMap<String, String>()
            for (name in assets.list(DIRECTORY).orEmpty().sorted()) {
                val text = runCatching {
                    assets.open("$DIRECTORY/$name").use { it.readBytes().decodeToString() }
                }.getOrNull() ?: continue
                merged.putAll(entries(text))
            }
            return build(merged)
        }
    }
}
