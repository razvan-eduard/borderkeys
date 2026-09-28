// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserWord

/** The personal dictionary as RFC 4180 CSV, in both directions. */
object DictionaryCsv {

    const val HEADER = "word,locale,count,lastUsedAt,deliberateCapitals,asserted"

    /** The longest word accepted, here and by `:keyboard`'s `LearningBuffer`. */
    const val MAX_WORD_LENGTH = 64

    fun encode(words: List<UserWord>): String = buildString {
        append(HEADER).append('\n')
        for (entry in words) {
            append(escape(entry.word)).append(',')
            append(escape(entry.locale)).append(',')
            append(entry.count).append(',')
            append(entry.lastUsedAt).append(',')
            append(entry.deliberateCapitals).append(',')
            append(entry.asserted).append('\n')
        }
    }

    /**
     * Parses an export back into learning updates, skipping malformed rows. Every row comes back
     * asserted (see `UserWord.asserted`); the two trailing columns are optional.
     */
    fun decode(csv: String, now: Long): List<LearnedWord> {
        val updates = ArrayList<LearnedWord>()
        for ((index, rawLine) in csv.lineSequence().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                continue
            }
            if (index == 0 && line.startsWith("word,")) {
                continue
            }
            val fields = parseLine(line)
            if (fields.size < 3) {
                continue
            }
            val word = fields[0]
            val count = fields[2].toIntOrNull() ?: continue
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH || count <= 0) {
                continue
            }
            updates += LearnedWord(
                word = word,
                locale = fields[1],
                delta = count,
                lastUsedAt = fields.getOrNull(3)?.toLongOrNull() ?: now,
                deliberateCapital = (fields.getOrNull(4)?.toIntOrNull() ?: 0) > 0,
                asserted = true,
            )
        }
        return updates
    }

    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun parseLine(line: String): List<String> {
        val fields = ArrayList<String>(4)
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                inQuotes && character == '"' &&
                    index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                character == '"' -> inQuotes = !inQuotes
                character == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
