// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import org.json.JSONException
import org.json.JSONObject

/** Reads a layout asset into a [KeyboardLayout]; any failure loads the fallback QWERTY. */
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
        // The asset's "label" is not read.
        return KeyboardLayout(
            id = root.optString("id", "unnamed"),
            languageTag = root.optString("languageTag", "und"),
            rows = rows,
        )
    }

    private fun parseKey(json: JSONObject): KeyboardLayout.Key {
        // "c" for a character key, "code" for a named action.
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
        // Delete and the modifier row's repeating keys repeat while held.
        if (code == KeyCodes.DELETE || KeyCodes.repeatsOnModifierRow(code)) {
            flags = flags or KeyFlags.REPEATABLE
        }
        if (alternatives.isNotEmpty()) {
            flags = flags or KeyFlags.HAS_ALTERNATIVES
        }
        // "absorb": this key, not the space bar, takes the width of a dropped optional key.
        if (json.optBoolean("absorb", false)) {
            flags = flags or KeyFlags.ABSORBS_FREED_WIDTH
        }
        // "secondary": drawn in the modifier fill.
        if (json.optBoolean("secondary", false)) {
            flags = flags or KeyFlags.SECONDARY_ROW
        }
        // Only letters take part in swipes.
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
        KeyCodes.KEYBOARD_PICKER -> "\u2328"
        KeyCodes.VOICE -> "\uD83C\uDF99"
        else -> KeyboardLayout.modifierCap(code) ?: ""
    }
}
