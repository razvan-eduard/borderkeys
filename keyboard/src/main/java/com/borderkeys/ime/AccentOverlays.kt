// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The diacritics the enabled languages, and the ones the user adds, lend to the letter keys'
 * long press, from `assets/accents/<tag>.json`. A missing or malformed file lends none.
 */
object AccentOverlays {

    private const val DIRECTORY = "accents"

    /** Lowercase letter to its accented forms, for one language tag. */
    fun load(assets: AssetManager, tag: String): Map<Char, String> = runCatching {
        val text = assets.open("$DIRECTORY/$tag.json").use { it.readBytes().decodeToString() }
        val keys = JSONObject(text).getJSONObject("keys")
        buildMap {
            for (name in keys.keys()) {
                val letter = name.firstOrNull()?.lowercaseChar() ?: continue
                val forms = keys.optString(name)
                if (forms.isNotEmpty()) {
                    put(letter, forms)
                }
            }
        }
    }.getOrElse { emptyMap() }

    /** Every accents file, by its language tag; a malformed one lends none. */
    fun loadAll(assets: AssetManager): Map<String, Map<Char, String>> =
        assets.list(DIRECTORY).orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
            .associateWith { load(assets, it) }

    /**
     * The tags of [overlays] that lend an accent to one of [letters], a layout's letter keys, in
     * [overlays]' order.
     */
    fun lendingTo(letters: Set<Char>, overlays: Map<String, Map<Char, String>>): List<String> {
        val lower = letters.mapTo(HashSet()) { it.lowercaseChar() }
        return overlays.filter { (_, map) -> map.any { (letter, forms) -> letter in lower && forms.isNotEmpty() } }
            .keys.toList()
    }

    /**
     * The overlay a layout's long press takes: [enabled], the enabled packs' own, then the
     * forms of each of [extra] that is not among [enabledTags], in order.
     */
    fun withExtra(
        enabled: Map<Char, String>,
        enabledTags: Collection<String>,
        extra: List<String>,
        overlays: Map<String, Map<Char, String>>,
    ): Map<Char, String> {
        val added = extra.filter { it !in enabledTags }.mapNotNull { overlays[it] }
        return if (added.isEmpty()) enabled else merge(listOf(enabled) + added)
    }

    /** One overlay from several, in order, each letter's forms concatenated without repeats. */
    fun merge(perLanguage: List<Map<Char, String>>): Map<Char, String> {
        val out = LinkedHashMap<Char, StringBuilder>()
        for (map in perLanguage) {
            for ((letter, forms) in map) {
                val builder = out.getOrPut(letter) { StringBuilder() }
                for (character in forms) {
                    if (builder.indexOf(character.toString()) < 0) {
                        builder.append(character)
                    }
                }
            }
        }
        return out.mapValues { it.value.toString() }
    }
}
