// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserWord

/**
 * The personal dictionary as CSV, in both directions.
 *
 * Split out of the repository so it can be tested without a database. It is also the only
 * "sync" this application has: an app with no network cannot move a dictionary between phones
 * except by handing the user a file and letting them carry it.
 *
 * RFC 4180 rather than split-on-comma. A learned word can legitimately contain a comma, a quote
 * or, through an import someone assembled by hand, a newline -- and an export that cannot be
 * read back is worse than no export.
 */
object DictionaryCsv {

    const val HEADER = "word,locale,count,lastUsedAt"

    private const val MAX_WORD_LENGTH = 64

    fun encode(words: List<UserWord>): String = buildString {
        append(HEADER).append('\n')
        for (entry in words) {
            append(escape(entry.word)).append(',')
            append(escape(entry.locale)).append(',')
            append(entry.count).append(',')
            append(entry.lastUsedAt).append('\n')
        }
    }

    /**
     * Parses an export back into learning updates.
     *
     * Malformed rows are skipped, not fatal. The reason to have a text export at all is that
     * someone can edit it, and a single bad line should cost that line rather than the import.
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
