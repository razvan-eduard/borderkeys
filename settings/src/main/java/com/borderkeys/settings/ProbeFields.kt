// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.ui.graphics.Color
import com.borderkeys.i18n.Keys

/**
 * The "Try it here" field's modes: a plain field, a password field, a field that takes
 * several lines. Resting inside the empty field the label names the mode; once it has floated
 * onto the outline it says that a tap moves to the next mode, and the typing hint inside the
 * empty field names the mode instead. Both spellings of the label lead with a bullet in the
 * mode's colour. The field carries its mode and then its exact text in its content
 * description, so the smoke suite can read a password field's text where the accessibility
 * tree shows it masked.
 */
enum class ProbeMode(val description: String, val labelKey: String, val colour: Color) {
    PLAIN("probe-plain:", Keys.SWIPE_TRY_IT_HERE, Color(0xFF1E88E5)),
    PASSWORD("probe-password:", Keys.SWIPE_TRY_A_PASSWORD_HERE, Color(0xFFE53935)),
    LINES("probe-lines:", Keys.SWIPE_TRY_SEVERAL_LINES_HERE, Color(0xFF43A047)),
    ;

    fun next(): ProbeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Leads the label; the suite finds the label by it. */
        const val BULLET = "● "
    }
}
