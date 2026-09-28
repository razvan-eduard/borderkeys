// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The diacritics the enabled languages lend to the letter keys' long press, from
 * `assets/accents/<tag>.json`. A missing or malformed file lends none.
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
