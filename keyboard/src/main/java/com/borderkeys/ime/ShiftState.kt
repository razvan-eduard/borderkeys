// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/** Off, held for one letter, or locked: set by the service, drawn by [KeyboardCanvasView]. */
object ShiftState {
    const val OFF = 0
    const val ON = 1
    const val LOCKED = 2
}
