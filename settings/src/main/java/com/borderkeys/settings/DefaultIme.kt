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
 * How many times the current lifecycle owner has resumed since this was first composed -- a
 * key to `remember` against for an answer that lives in system settings and changes only while
 * this screen is away, such as the keyboard being enabled or chosen there. One implementation
 * for every screen that sends the person out to change such a thing and has to notice on the
 * way back; Home, Setup and [rememberBorderKeysDefaultState] all read it.
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
    val resumed by rememberResumedCount()
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(resumed) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        isDefault.value = isBorderKeysDefault(context)
        if (isBorderKeysEnabled(context) && !isDefault.value) {
            openKeyboardPicker(context)
        }
    }

    // A choice made in the picker's own dialog neither pauses nor resumes this activity, so it
    // is noticed two ways. The window regaining focus when the dialog closes is the first: the
    // answer is re-read on every focus return. A poll behind that is the second, for a picker
    // that never took the window's focus at all -- bounded per resume, so a screen left open on
    // a phone that never switches is not a wakeup every half second for as long as it stays
    // open, which it used to be.
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
