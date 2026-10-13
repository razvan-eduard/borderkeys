// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.Context
import com.borderkeys.data.KeyboardSubtypes

/** This keyboard's subtypes the user enabled, leaving out the ones the system enables for its language. */
object ChosenSubtypes {

    /** The globe is drawn, when its setting is on, from this many enabled layouts. */
    const val GLOBE_FROM = 2

    fun count(context: Context): Int = KeyboardSubtypes.enabled(context, implicit = false).size
}
