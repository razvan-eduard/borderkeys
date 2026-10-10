// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * A tile on the keyboard's quick panel, the one a long press on Enter opens. Each is one
 * [Kind]: an action that runs, a switch that reads and writes one setting, or one of the
 * positions, of which the current one is highlighted. The ids are stored; a new tile takes the
 * next free id and an old one keeps its own.
 */
enum class QuickTile(val id: Int, val kind: Kind) {

    /** Puts the resize handles on the keyboard. */
    RESIZE(1, Kind.ACTION),

    /** Docked across the bottom. */
    DOCK(2, Kind.POSITION),

    /** One-handed, left. */
    LEFT(3, Kind.POSITION),

    /** One-handed, right. */
    RIGHT(4, Kind.POSITION),

    /** Floating. */
    FLOAT(5, Kind.POSITION),

    /** The number row above the letters. */
    NUMBER_ROW(6, Kind.SWITCH),

    /** The suggestion strip. */
    SUGGESTION_STRIP(7, Kind.SWITCH),

    /** Swipe typing. */
    SWIPE(8, Kind.SWITCH),

    /** The first suggestion applied on space or punctuation. */
    AUTOCORRECT(9, Kind.SWITCH),

    /** Learning from what is typed. */
    LEARNING(10, Kind.SWITCH),

    /** The clipboard offered on the strip. */
    CLIPBOARD_OFFER(11, Kind.SWITCH),

    /** A click at each key press. */
    KEY_SOUND(12, Kind.SWITCH),

    /** A vibration at each key press. */
    VIBRATION(13, Kind.SWITCH),

    /** The modifier row above the letters. */
    MODIFIER_ROW(14, Kind.SWITCH),

    /** The emoji key beside the space bar. */
    EMOJI_KEY(15, Kind.SWITCH),

    /** The globe key beside the space bar. */
    GLOBE_KEY(16, Kind.SWITCH),

    /** The enlarged key above the finger on a press. */
    KEY_POPUP(17, Kind.SWITCH),

    /** The radial suggestion menu. */
    RADIAL_MENU(18, Kind.SWITCH),

    /** The quick actions bar. */
    QUICK_ACTIONS(19, Kind.SWITCH),

    /** The number pad in numeric fields. */
    NUMERIC_KEYPAD(20, Kind.SWITCH),

    /** Everything on or off by hand, the quick action of the same name. */
    FEATURES(21, Kind.SWITCH),
    ;

    enum class Kind { ACTION, SWITCH, POSITION }

    companion object {
        /** What a new install starts with: the panel as it was before it could be arranged. */
        val DEFAULT: List<QuickTile> = listOf(RESIZE, DOCK, LEFT, RIGHT, FLOAT, NUMBER_ROW)

        /** The most tiles the panel keeps, whatever the screen shows of them. */
        const val MAX_TILES = 16

        fun fromId(id: Int): QuickTile? = idMatching(entries.toTypedArray(), id) { it.id }

        /** The tiles for [ids], in order, each once, dropping ids this build does not know. */
        fun fromIds(ids: List<Int>): List<QuickTile> =
            idsMatching(entries.toTypedArray(), ids) { it.id }.distinct().take(MAX_TILES)
    }
}
