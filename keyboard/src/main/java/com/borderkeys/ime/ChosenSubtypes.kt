// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager

/** This keyboard's subtypes the user enabled, leaving out the ones the system enables for its language. */
object ChosenSubtypes {

    /** The globe is drawn, when its setting is on, from this many enabled layouts. */
    const val GLOBE_FROM = 2

    fun count(context: Context): Int {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return 0
        val ours = manager.enabledInputMethodList.firstOrNull { it.packageName == context.packageName } ?: return 0
        return manager.getEnabledInputMethodSubtypeList(ours, false).size
    }
}
