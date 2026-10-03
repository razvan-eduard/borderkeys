// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.content.Context
import android.view.inputmethod.InputMethodManager

/** The enabled input methods, this keyboard aside, that offer a voice subtype. */
object VoiceKeyboards {
    fun enabled(context: Context): List<String> {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        return manager.enabledInputMethodList
            .filter { it.packageName != context.packageName }
            .filter { method ->
                manager.getEnabledInputMethodSubtypeList(method, true).any { it.mode == VOICE_MODE }
            }
            .map { it.id }
    }

    const val VOICE_MODE = "voice"
}
