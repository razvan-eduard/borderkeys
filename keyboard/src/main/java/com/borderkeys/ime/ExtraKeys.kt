// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The characters and accent modifiers a subtype's language adds to a layout that lacks them, from
 * `assets/extra_keys.json`, each placed on a free flick direction of a key. An entry reads
 * `key[:alternative...][@next_to]`: an accent (`accent_<name>`) or a character; an accent whose
 * alternatives the layout holds is not added, and one with a single alternative adds that
 * character instead.
 */
object ExtraKeys {

    private const val ASSET = "extra_keys.json"
    private const val ACCENT_PREFIX = "accent_"

    /** The accent names of the entries, to the accent modifiers' key names. */
    private val ACCENTS = mapOf(
        "aigu" to "dead_acute",
        "grave" to "dead_grave",
        "circonflexe" to "dead_circumflex",
        "trema" to "dead_diaeresis",
        "tilde" to "dead_tilde",
        "caron" to "dead_caron",
        "cedille" to "dead_cedilla",
        "ogonek" to "dead_ogonek",
        "ring" to "dead_ring",
        "macron" to "dead_macron",
    )

    /** One entry as written. [key] is a character, or an accent modifier's key name. */
    class Entry(val key: String, val isAccent: Boolean, val alternatives: List<String>, val nextTo: String?)

    /**
     * One extra key in place: on the key of [keyCode], in flick [direction], typing [text] or,
     * for an accent modifier, pressing the key named [keyName]. [id] is what its switch is kept by.
     */
    class Placement(val keyCode: Int, val direction: Int, val text: String, val keyName: String?) {
        val id: String get() = keyName ?: text
    }

    /** [text] as an entry; null for an accent this keyboard has no modifier for, or an empty one. */
    fun parse(text: String): Entry? {
        val at = text.lastIndexOf('@')
        val nextTo = if (at > 0) text.substring(at + 1).takeIf { it.isNotEmpty() } else null
        val body = if (at > 0) text.substring(0, at) else text
        val parts = body.split(':')
        val head = parts.first()
        if (head.isEmpty()) {
            return null
        }
        if (head.startsWith(ACCENT_PREFIX)) {
            val name = ACCENTS[head.removePrefix(ACCENT_PREFIX)] ?: return null
            return Entry(name, isAccent = true, alternatives = parts.drop(1).filter { it.isNotEmpty() }, nextTo = nextTo)
        }
        return Entry(head, isAccent = false, alternatives = emptyList(), nextTo = nextTo)
    }

    /** The entries of every language tag in `assets/extra_keys.json`; none when it is missing. */
    fun load(assets: AssetManager): Map<String, List<Entry>> = runCatching {
        val json = JSONObject(assets.open(ASSET).use { it.readBytes().decodeToString() })
        buildMap {
            for (tag in json.keys()) {
                val list = json.getJSONArray(tag)
                put(tag, (0 until list.length()).mapNotNull { parse(list.getString(it)) })
            }
        }
    }.getOrElse { emptyMap() }

    /** What [entry] adds: its single alternative, else its accent modifier or character. */
    fun idOf(entry: Entry): String = if (entry.isAccent && entry.alternatives.size == 1) entry.alternatives[0] else entry.key

    /**
     * Where [entries] go on [layout], in order, each once: those switched [off] by [idOf], those
     * whose script is not the layout's, and those the layout already holds are left out. [taken]
     * says whether a key code's flick direction is already the user's.
     */
    fun place(
        entries: List<Entry>,
        layout: KeyboardLayout,
        off: Set<String>,
        taken: (Int, Int) -> Boolean,
    ): List<Placement> {
        val letterRows = layout.rows.filter { row -> row.keys.any { KeyFlags.has(it.flags, KeyFlags.LETTER) } }
        if (letterRows.isEmpty()) {
            return emptyList()
        }
        val held = HashSet<String>()
        for (row in layout.rows) {
            for (key in row.keys) {
                if (KeyCodes.isCharacter(key.code)) {
                    held += String(Character.toChars(key.code)).lowercase()
                } else if (key.code != KeyCodes.NONE) {
                    held += "#${key.code}"
                }
                key.alternatives.codePoints().forEach { held += String(Character.toChars(it)).lowercase() }
                key.flicks.forEach { if (it.isNotEmpty()) held += it.lowercase() }
            }
        }
        val script = scriptOf(letterRows)
        val used = HashSet<Long>()
        val placed = ArrayList<Placement>()
        val seen = HashSet<String>()
        for (entry in entries) {
            val id = idOf(entry)
            if (!seen.add(id) || id in off) {
                continue
            }
            if (entry.isAccent && entry.alternatives.size > 1 && entry.alternatives.all { it.lowercase() in held }) {
                continue
            }
            val asKey = entry.isAccent && entry.alternatives.size != 1
            val text = if (asKey) DeadKeys.cap(KeyCodes.named(id)).orEmpty() else id
            if (asKey && "#${KeyCodes.named(id)}" in held) {
                continue
            }
            if (!asKey && id.lowercase() in held) {
                continue
            }
            if (!asKey && !sameScript(id, script)) {
                continue
            }
            if (asKey && script != Character.UnicodeScript.LATIN) {
                continue
            }
            val slot = slotFor(entry.nextTo, letterRows, used, taken) ?: continue
            used += slot
            placed += Placement((slot shr 3).toInt(), (slot and 7).toInt(), text, if (asKey) id else null)
        }
        return placed
    }

    /** The first free slot, as `code shl 3 or direction`: on [nextTo]'s key, else the defaults. */
    private fun slotFor(
        nextTo: String?,
        letterRows: List<KeyboardLayout.Row>,
        used: Set<Long>,
        taken: (Int, Int) -> Boolean,
    ): Long? {
        fun free(key: KeyboardLayout.Key, direction: Int): Long? {
            val slot = (key.code.toLong() shl 3) or direction.toLong()
            val empty = key.flicks.getOrElse(direction) { "" }.isEmpty()
            return if (empty && slot !in used && !taken(key.code, direction)) slot else null
        }
        if (nextTo != null) {
            val target = letterRows.flatMap { it.keys }.firstOrNull { it.label.equals(nextTo, ignoreCase = true) }
            if (target != null) {
                for (direction in CORNERS) {
                    free(target, direction)?.let { return it }
                }
            }
        }
        for ((rowIndex, directions) in DEFAULT_POSITIONS) {
            val row = letterRows.getOrNull(rowIndex) ?: continue
            for (key in row.keys) {
                if (!KeyFlags.has(key.flags, KeyFlags.LETTER)) {
                    continue
                }
                for (direction in directions) {
                    free(key, direction)?.let { return it }
                }
            }
        }
        return null
    }

    private fun scriptOf(letterRows: List<KeyboardLayout.Row>): Character.UnicodeScript {
        val counts = HashMap<Character.UnicodeScript, Int>()
        for (row in letterRows) {
            for (key in row.keys) {
                if (KeyFlags.has(key.flags, KeyFlags.LETTER)) {
                    val script = Character.UnicodeScript.of(key.code)
                    counts[script] = (counts[script] ?: 0) + 1
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: Character.UnicodeScript.LATIN
    }

    /** Whether [text]'s first letter is in [script]; a text with no letter fits every script. */
    private fun sameScript(text: String, script: Character.UnicodeScript): Boolean {
        val letter = text.codePoints().filter { Character.isLetter(it) }.findFirst()
        return !letter.isPresent || Character.UnicodeScript.of(letter.asInt) == script
    }

    private const val NE = 1
    private const val SE = 3
    private const val SW = 5
    private const val NW = 7

    /** A key's corners, in the order an extra key beside it takes them. */
    private val CORNERS = intArrayOf(SE, SW, NE, NW)

    /** Where an extra key with no key to sit beside goes: the second letter row's lower corners, then the third's upper. */
    private val DEFAULT_POSITIONS = listOf(1 to intArrayOf(SE, SW), 2 to intArrayOf(NE, NW))
}
