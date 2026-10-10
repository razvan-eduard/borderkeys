// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Cascade screenshots: the screenshots offered one after another, the oldest first, the last one
 * used marking where the series stands.
 */
class ScreenshotSeries {

    /** The last screenshot used, or null while none has been. */
    private var last: ScreenshotFolder.Shot? = null

    /**
     * The first of [shots], which are in [ScreenshotFolder.Shot.ORDER], after the last one used;
     * null when none is left.
     */
    fun next(shots: List<ScreenshotFolder.Shot>): ScreenshotFolder.Shot? {
        val mark = last ?: return shots.firstOrNull()
        return shots.firstOrNull { ScreenshotFolder.Shot.ORDER.compare(it, mark) > 0 }
    }

    /** [shot] was used: the series goes on after it, never back before it. */
    fun used(shot: ScreenshotFolder.Shot) {
        val mark = last
        if (mark == null || ScreenshotFolder.Shot.ORDER.compare(shot, mark) > 0) {
            last = shot
        }
    }

    /** The series ends with [shots]: none of them is offered again. */
    fun end(shots: List<ScreenshotFolder.Shot>) {
        shots.lastOrNull()?.let(::used)
    }
}
