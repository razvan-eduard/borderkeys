// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.i18n.Keys
import org.json.JSONException
import org.json.JSONObject

/**
 * What is wrong with a layout's JSON before the keyboard would draw it: the rules
 * `tools/check_layouts.py` holds the shipped assets to, then the loader's own parse. Pure, so
 * the editor can run it on every keystroke.
 */
object LayoutValidator {

    /** One problem: where it is, as a row and key index or -1, and the catalogue key naming it. */
    data class Problem(val row: Int, val key: Int, val messageKey: String)

    /** The problems in [text], none for a layout the keyboard will draw. */
    fun validate(text: String): List<Problem> {
        val root = try {
            JSONObject(text)
        } catch (error: JSONException) {
            return listOf(Problem(-1, -1, Keys.LAYOUT_ERROR_JSON))
        }
        val problems = ArrayList<Problem>()
        val rows = root.optJSONArray("rows")
        if (rows == null || rows.length() == 0) {
            return listOf(Problem(-1, -1, Keys.LAYOUT_ERROR_NO_ROWS))
        }
        val seen = HashMap<String, Int>()
        var latin = false
        val present = HashSet<Char>()
        var anyKey = false
        for (rowIndex in 0 until rows.length()) {
            val row = rows.optJSONObject(rowIndex) ?: continue
            val keys = row.optJSONArray("keys") ?: continue
            for (keyIndex in 0 until keys.length()) {
                val key = keys.optJSONObject(keyIndex) ?: continue
                anyKey = true
                val character = key.optString("c", "")
                if (character.isNotEmpty()) {
                    if (character.codePointCount(0, character.length) != 1) {
                        problems += Problem(rowIndex, keyIndex, Keys.LAYOUT_ERROR_KEY_NOT_ONE_CHAR)
                    }
                    if (seen.put(character, rowIndex) != null) {
                        problems += Problem(rowIndex, keyIndex, Keys.LAYOUT_ERROR_DUPLICATE_KEY)
                    }
                    val first = character[0]
                    if (first.code < 0x80 && first.isLetter()) {
                        latin = true
                    }
                    if (first in 'a'..'z') {
                        present += first
                    }
                } else {
                    val code = key.optString("code", "")
                    if (code.isEmpty() || KeyCodes.named(code) == KeyCodes.NONE) {
                        problems += Problem(rowIndex, keyIndex, Keys.LAYOUT_ERROR_UNKNOWN_CODE)
                    }
                }
            }
        }
        if (!anyKey) {
            return listOf(Problem(-1, -1, Keys.LAYOUT_ERROR_NO_ROWS))
        }
        // A symbols or number page is not a letter layout: no alphabet, no space row.
        val page = root.optString("id") in PAGES
        if (!page && latin && present.size < ALPHABET.length && root.optString("id") !in LATIN_SUBSETS) {
            problems += Problem(-1, -1, Keys.LAYOUT_ERROR_MISSING_LETTERS)
        }
        val last = rows.optJSONObject(rows.length() - 1)?.optJSONArray("keys")
        val codes = HashSet<String>()
        if (last != null) {
            for (index in 0 until last.length()) {
                codes += last.optJSONObject(index)?.optString("code", "").orEmpty()
            }
        }
        if (!page && ("space" !in codes || "enter" !in codes)) {
            problems += Problem(rows.length() - 1, -1, Keys.LAYOUT_ERROR_NO_SPACE_ENTER)
        }
        if (!modmapIsWellFormed(root)) {
            problems += Problem(-1, -1, Keys.LAYOUT_ERROR_MODMAP)
        }
        if (problems.isEmpty()) {
            try {
                LayoutLoader.parse(text)
            } catch (error: JSONException) {
                problems += Problem(-1, -1, Keys.LAYOUT_ERROR_JSON)
            }
        }
        return problems
    }

    /**
     * Whether [root]'s `"modmap"`, when there is one, is an object of [Modmap.SECTIONS], each
     * mapping one character to one character.
     */
    private fun modmapIsWellFormed(root: JSONObject): Boolean {
        if (!root.has("modmap")) {
            return true
        }
        val modmap = root.optJSONObject("modmap") ?: return false
        for (section in modmap.keys()) {
            if (section !in Modmap.SECTIONS) {
                return false
            }
            val entries = modmap.optJSONObject(section) ?: return false
            for (from in entries.keys()) {
                val to = entries.opt(from) as? String ?: return false
                if (from.codePointCount(0, from.length) != 1 || to.codePointCount(0, to.length) != 1) {
                    return false
                }
            }
        }
        return true
    }

    /** The layout [text] describes, or null when [validate] finds a problem. */
    fun parse(text: String): KeyboardLayout? =
        if (validate(text).isEmpty()) runCatching { LayoutLoader.parse(text) }.getOrNull() else null

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    /** Latin layouts that carry fewer than the twenty-six letters on purpose. */
    private val LATIN_SUBSETS = setOf("toki_pona")

    /** The symbol and number pages, which tools/check_layouts.py holds to no letter rules. */
    private val PAGES = setOf("numpad", "symbols", "symbols_shift", "symbols_numpad_left", "symbols_numpad_right")
}
