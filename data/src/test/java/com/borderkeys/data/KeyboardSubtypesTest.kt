// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** This keyboard found among the enabled input methods, and nothing read while it is not one of them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [PhraseTablesSqlTest.SDK])
class KeyboardSubtypesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun enable(vararg packages: String) {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        shadowOf(manager).setEnabledInputMethodInfoList(
            packages.map { InputMethodInfo(it, KEYBOARD_CLASS, it, null) },
        )
    }

    @Test
    fun `this keyboard is found among the enabled input methods, another listed first`() {
        enable(OTHER_PACKAGE, context.packageName)
        assertEquals(context.packageName, KeyboardSubtypes.enabledMethod(context)?.packageName)
    }

    @Test
    fun `while only another keyboard is enabled, nothing of this one is read`() {
        enable(OTHER_PACKAGE)
        assertNull(KeyboardSubtypes.enabledMethod(context))
        assertEquals(emptyList<Any>(), KeyboardSubtypes.enabled(context))
        assertEquals(emptyList<String>(), KeyboardSubtypes.enabledTags(context))
        assertEquals(emptyList<Any>(), KeyboardSubtypes.inUse(context))
    }

    private companion object {
        const val OTHER_PACKAGE = "com.example.otherkeyboard"
        const val KEYBOARD_CLASS = "Keyboard"
    }
}
