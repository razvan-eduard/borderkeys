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
 * Whether this build is enabled in the system's list of input methods.
 *
 * Enabled and *in use* are different questions -- see [isBorderKeysDefault] -- and every screen
 * that asks either one asks it through here, so the answer is derived the same way everywhere.
 */
fun isBorderKeysEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
}

/**
 * Whether *this* build is the keyboard currently in use.
 *
 * Compared by exact package, never as a prefix. The assistant build's package name is this one's
 * with a suffix, so `"com.borderkeys"` is a *prefix* of `"com.borderkeys.plus"` -- a prefix (or
 * `startsWith`) test lets the core build believe it is the current keyboard whenever the other
 * one actually is, which is the one thing a check like this exists to get right.
 */
fun isBorderKeysDefault(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return current.substringBefore('/') == context.packageName
}

/**
 * Opens the system's own keyboard switcher.
 *
 * The one dialog an application cannot fake or skip: choosing the active input method is a
 * system decision by design, because an app that could make itself the keyboard unasked would be
 * a keylogger. All this does is bring up the picker the user would otherwise reach through the
 * notification shade or Settings themselves.
 */
fun openKeyboardPicker(context: Context) {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.showInputMethodPicker()
}

/**
 * Whether BorderKeys is the selected keyboard, kept live -- offering the picker itself, the
 * moment it can, whenever it is not.
 *
 * Every screen with something that only makes sense once BorderKeys is actually the one typing
 * -- Setup's own step 2, the draft box's own fallback when it was reached from a selection made
 * through some other keyboard -- needs the same two things, previously written out twice with
 * the two write-ups already drifting slightly: [openKeyboardPicker] waited on until the window
 * has real input focus (called any earlier and it silently does nothing -- a cold launch's first
 * frame is visible before that finishes), and the answer polled afterwards, because there is no
 * callback for "the default input method changed." Picking an entry from the picker's own dialog
 * neither pauses nor resumes the caller's activity, so an `ON_RESUME` observer alone never learns
 * that a choice was actually made -- only asking again, repeatedly, does.
 *
 * One implementation, used from both places that needed it, rather than a second copy of either
 * workaround the next time a third place needs the same answer.
 */
@Composable
fun rememberBorderKeysDefaultState(): State<Boolean> {
    val context = LocalContext.current
    val isDefault = remember { mutableStateOf(isBorderKeysDefault(context)) }

    // Re-offers the picker once per genuine return to this screen (a resumed tick), not on
    // every raw window-focus flicker -- the same dialog reopening every time focus so much as
    // blinks would be worse than the tap it is trying to save.
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumed += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(resumed) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        isDefault.value = isBorderKeysDefault(context)
        if (isBorderKeysEnabled(context) && !isDefault.value) {
            openKeyboardPicker(context)
        }
    }

    // Polled rather than left to the next resumed tick to catch it: a choice made in the
    // picker's own dialog is the whole point of having offered it, and the caller waiting on
    // this value should not need the person to leave and return to this screen a second time
    // just to have that choice noticed.
    LaunchedEffect(Unit) {
        while (isActive) {
            if (isDefault.value) {
                break
            }
            val current = isBorderKeysDefault(context)
            if (current) {
                isDefault.value = true
                break
            }
            delay(BORDERKEYS_DEFAULT_POLL_MILLIS)
        }
    }

    return isDefault
}

/** How often [rememberBorderKeysDefaultState] polls while BorderKeys is not yet the default. */
private const val BORDERKEYS_DEFAULT_POLL_MILLIS = 500L
