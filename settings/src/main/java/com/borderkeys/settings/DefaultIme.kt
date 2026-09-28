// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * Whether this build is enabled in the system's list of input methods; [isBorderKeysDefault] says
 * whether it is the one in use.
 */
fun isBorderKeysEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
}

/** Whether this build is the keyboard in use, compared by exact package name, never by prefix. */
fun isBorderKeysDefault(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return current.substringBefore('/') == context.packageName
}

/** Opens the system's keyboard picker. */
fun openKeyboardPicker(context: Context) {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.showInputMethodPicker()
}

/**
 * How many times the current lifecycle owner has resumed since this was first composed: a key to
 * `remember` against for an answer that lives in system settings.
 */
@Composable
fun rememberResumedCount(): State<Int> {
    val resumed = remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed.intValue += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * Whether BorderKeys is the selected keyboard, kept live, offering the picker whenever it is
 * enabled but not selected.
 *
 * [openKeyboardPicker] is called only once the window has input focus; before that the call does
 * nothing. The answer is re-read on every focus return and polled for
 * [BORDERKEYS_DEFAULT_POLL_WINDOW_MILLIS] after each resume.
 */
@Composable
fun rememberBorderKeysDefaultState(): State<Boolean> {
    val context = LocalContext.current
    val isDefault = remember { mutableStateOf(isBorderKeysDefault(context)) }

    // The picker is offered once per resume, not on every focus change.
    val resumed by rememberResumedCount()
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(resumed) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        isDefault.value = isBorderKeysDefault(context)
        if (isBorderKeysEnabled(context) && !isDefault.value) {
            openKeyboardPicker(context)
        }
    }

    // Re-read on every focus return, and polled for a bounded window after each resume.
    LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && !isDefault.value) {
                isDefault.value = isBorderKeysDefault(context)
            }
        }
    }
    LaunchedEffect(resumed) {
        val deadline = System.currentTimeMillis() + BORDERKEYS_DEFAULT_POLL_WINDOW_MILLIS
        while (isActive && !isDefault.value && System.currentTimeMillis() < deadline) {
            delay(BORDERKEYS_DEFAULT_POLL_MILLIS)
            if (isBorderKeysDefault(context)) {
                isDefault.value = true
            }
        }
    }

    return isDefault
}

/** How often [rememberBorderKeysDefaultState] polls while BorderKeys is not yet the default. */
private const val BORDERKEYS_DEFAULT_POLL_MILLIS = 500L

/** How long after each resume the poll keeps going before the focus watcher alone is trusted. */
private const val BORDERKEYS_DEFAULT_POLL_WINDOW_MILLIS = 60_000L
