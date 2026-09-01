// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONException
import org.json.JSONObject
import com.borderkeys.i18n.Keys

/**
 * Reads a layout asset into a [KeyboardLayout].
 *
 * `org.json` because it is in the framework: no dependency, no annotation processor, nothing on
 * the classpath. It allocates while parsing, which is fine -- this runs once, at service start,
 * on a background thread, and the result is compiled into primitive arrays before anything is
 * drawn.
 *
 * Every failure returns the fallback rather than throwing. A malformed asset should mean a
 * plain QWERTY, not an input method that cannot draw -- because a keyboard that crashes on
 * start is one the user cannot replace without already having another one installed.
 */
object LayoutLoader {

    private const val DIRECTORY = "layouts"

    fun load(assets: AssetManager, id: String): KeyboardLayout =
        runCatching {
            val text = assets.open("$DIRECTORY/$id.json").use { input ->
                input.readBytes().decodeToString()
            }
            parse(text)
        }.getOrElse { KeyboardLayout.fallbackQwerty() }

    fun parse(text: String): KeyboardLayout {
        val root = JSONObject(text)
        val rowsJson = root.getJSONArray("rows")
        val rows = ArrayList<KeyboardLayout.Row>(rowsJson.length())

        for (rowIndex in 0 until rowsJson.length()) {
            val rowJson = rowsJson.getJSONObject(rowIndex)
            val keysJson = rowJson.getJSONArray("keys")
            val keys = ArrayList<KeyboardLayout.Key>(keysJson.length())

            for (keyIndex in 0 until keysJson.length()) {
                keys += parseKey(keysJson.getJSONObject(keyIndex))
            }
            if (keys.isEmpty()) {
                continue
            }
            rows += KeyboardLayout.Row(
                indent = rowJson.optDouble("indent", 0.0).toFloat().coerceIn(0f, 8f),
                heightScale = rowJson.optDouble("height", 1.0).toFloat().coerceIn(0.5f, 2f),
                keys = keys,
            )
        }

        if (rows.isEmpty()) {
            throw JSONException("a layout needs at least one row with at least one key")
        }
        return KeyboardLayout(
            id = root.optString("id", "unnamed"),
            // A catalogue key, not text: the loader runs off the UI thread with no catalogue
            // in hand, and the label is only ever shown by something that has one.
            label = root.optString("label", Keys.LAYOUT_KEYBOARD_2),
            languageTag = root.optString("languageTag", "und"),
            rows = rows,
        )
    }

    private fun parseKey(json: JSONObject): KeyboardLayout.Key {
        // Two spellings: "c" for a character key, which is the overwhelming majority, and
        // "code" for a named action.
        val character = json.optString("c", "")
        val code = if (character.isNotEmpty()) {
            character.codePointAt(0)
        } else {
            KeyCodes.named(json.optString("code", ""))
        }

        val label = json.optString("label").ifEmpty {
            if (KeyCodes.isCharacter(code)) String(Character.toChars(code)) else defaultLabel(code)
        }
        val alternatives = json.optString("alt", "")

        var flags = KeyFlags.NONE
        if (KeyCodes.isCharacter(code) && code != KeyCodes.SPACE) {
            // Space is a character but neither a swipe letter nor worth a preview bubble.
            flags = flags or KeyFlags.LETTER or KeyFlags.PREVIEW
        }
        if (!KeyCodes.isCharacter(code)) {
            flags = flags or KeyFlags.MODIFIER
        }
        if (code == KeyCodes.DELETE) {
            flags = flags or KeyFlags.REPEATABLE
        }
        if (alternatives.isNotEmpty()) {
            flags = flags or KeyFlags.HAS_ALTERNATIVES
        }
        // Punctuation is a character but not a letter: a swipe should not pass through a comma.
        if (KeyCodes.isCharacter(code) && !Character.isLetter(code)) {
            flags = flags and KeyFlags.LETTER.inv()
        }

        return KeyboardLayout.Key(
            code = code,
            label = label,
            alternatives = alternatives,
            widthUnits = json.optDouble("w", 1.0).toFloat().coerceIn(0.25f, 12f),
            flags = flags,
        )
    }

    private fun defaultLabel(code: Int): String = when (code) {
        KeyCodes.SHIFT -> "⇧"
        KeyCodes.DELETE -> "⌫"
        KeyCodes.ENTER -> "⏎"
        KeyCodes.SYMBOLS -> "?123"
        KeyCodes.LANGUAGE -> "🌐"
        KeyCodes.EMOJI -> "☺"
        KeyCodes.SETTINGS -> "⚙"
        else -> ""
    }
}
