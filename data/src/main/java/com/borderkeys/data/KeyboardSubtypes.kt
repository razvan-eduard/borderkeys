// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.borderkeys.language.LanguageTags
import java.util.Locale

/** This keyboard's input-method subtypes as the system holds them, read from anywhere in the app. */
object KeyboardSubtypes {

    /**
     * The subtypes in use: the current one while this keyboard is the default input method, else
     * every enabled one, those the system enables for its language included; none while the
     * keyboard is not enabled.
     */
    fun inUse(context: Context): List<InputMethodSubtype> {
        val manager = manager(context) ?: return emptyList()
        enabledMethod(context) ?: return emptyList()
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val inUse = default?.let(ComponentName::unflattenFromString)?.packageName == context.packageName
        val current = manager.currentInputMethodSubtype
        return if (inUse && current != null) listOf(current) else enabled(context)
    }

    /** This keyboard among the enabled input methods, or null while it is not enabled. */
    fun enabledMethod(context: Context): InputMethodInfo? =
        manager(context)?.enabledInputMethodList?.firstOrNull { it.packageName == context.packageName }

    /**
     * This keyboard's enabled subtypes, with those the system enables for its language when
     * [implicit]; none while the keyboard is not enabled.
     */
    fun enabled(context: Context, implicit: Boolean = true): List<InputMethodSubtype> {
        val manager = manager(context) ?: return emptyList()
        val method = enabledMethod(context) ?: return emptyList()
        return manager.getEnabledInputMethodSubtypeList(method, implicit)
    }

    /** The language tags of this keyboard's enabled subtypes, those naming none left out, each once. */
    fun enabledTags(context: Context): List<String> =
        enabled(context).map { it.languageTag }.filter { it.isNotEmpty() }.distinct()

    private fun manager(context: Context): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    /** The language of a link nothing else names one for: [languageOf] the subtypes in use and the device. */
    fun language(context: Context): String =
        languageOf(inUse(context).map { it.languageTag }, Locale.getDefault().toLanguageTag())

    /** The primary language of the first of [subtypeTags] that names one, else of [deviceTag]. */
    fun languageOf(subtypeTags: List<String?>, deviceTag: String): String =
        subtypeTags.map(LanguageTags::primary).firstOrNull { it != LanguageTags.UNKNOWN }
            ?: LanguageTags.primary(deviceTag)
}
