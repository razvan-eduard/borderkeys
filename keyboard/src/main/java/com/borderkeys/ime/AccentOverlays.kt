// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The diacritics an enabled language pack lends to the letter keys.
 *
 * The base layout carries no language-specific accents -- see `assets/layouts/qwerty.json`.
 * Each bundled language has a small `assets/accents/<tag>.json` instead, mapping a plain letter
 * to its accented forms, and the keyboard merges the ones for the languages the user has turned
 * on. Enable Romanian and `a` holds ă â; turn it off and it does not. Accents follow the
 * Languages screen, not the layout.
 *
 * Every failure is empty, not an exception: a missing or malformed overlay means a key without
 * that accent, never a keyboard that will not draw.
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

    /**
     * One overlay from several, in the given order, each letter's forms concatenated with the
     * duplicates dropped. The order is the order the packs are enabled in, so the first
     * language's accent is the one the corner hint shows.
     */
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
