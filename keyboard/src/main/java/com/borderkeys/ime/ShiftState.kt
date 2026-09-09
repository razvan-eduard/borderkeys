// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Off, held for one letter, or locked -- shared by [BorderKeysService], which decides it, and
 * [KeyboardCanvasView], which is only ever told it.
 *
 * Its own file rather than a copy in each: the service does not belong on the view's side of
 * that relationship (a leaf view depending on the service that hosts it would be the dependency
 * running backwards), and the view does not belong on the service's, so neither was the right
 * place for the one shared source -- a small file next to both, named for what it is, is.
 */
object ShiftState {
    const val OFF = 0
    const val ON = 1
    const val LOCKED = 2
}
