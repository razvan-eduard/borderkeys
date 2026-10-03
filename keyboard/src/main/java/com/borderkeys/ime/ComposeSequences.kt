// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The compose key's table: a sequence of typed characters to the text it spells, from
 * `assets/compose/<name>.json`. No sequence is the start of another, so a full match is final.
 */
class ComposeSequences(private val table: Map<String, String>) {

    /** Where a sequence typed so far stands. */
    sealed interface Step {
        class Done(val text: String) : Step

        object More : Step

        object NoMatch : Step
    }

    private val prefixes: Set<String> = buildSet {
        for (sequence in table.keys) {
            var end = sequence.offsetByCodePoints(0, 1)
            while (end < sequence.length) {
                add(sequence.substring(0, end))
                end = sequence.offsetByCodePoints(end, 1)
            }
        }
    }

    val size: Int get() = table.size

    fun step(typed: String): Step {
        table[typed]?.let { return Step.Done(it) }
        return if (typed in prefixes) Step.More else Step.NoMatch
    }

    companion object {
        private const val DIRECTORY = "compose"

        val EMPTY = ComposeSequences(emptyMap())

        /** The table in [json]'s "sequences" object; malformed text gives none. */
        fun parse(json: String): ComposeSequences = runCatching {
            val sequences = JSONObject(json).getJSONObject("sequences")
            val table = HashMap<String, String>()
            for (sequence in sequences.keys()) {
                val text = sequences.optString(sequence)
                if (sequence.isNotEmpty() && text.isNotEmpty()) {
                    table[sequence] = text
                }
            }
            ComposeSequences(table)
        }.getOrElse { EMPTY }

        /** Every table under `assets/compose`, merged; none when the directory is missing. */
        fun load(assets: AssetManager): ComposeSequences {
            val merged = HashMap<String, String>()
            for (name in assets.list(DIRECTORY).orEmpty().sorted()) {
                val text = runCatching {
                    assets.open("$DIRECTORY/$name").use { it.readBytes().decodeToString() }
                }.getOrNull() ?: continue
                merged.putAll(parse(text).table)
            }
            return ComposeSequences(merged)
        }
    }
}
