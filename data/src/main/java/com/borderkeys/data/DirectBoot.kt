// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager

/**
 * The user's unlock state. Credential-encrypted storage, where the settings, the database and
 * its passphrase live, is readable only after the user's first unlock since boot.
 */
object DirectBoot {

    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /** Whether the lock screen is showing. */
    fun isKeyguardLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    /**
     * Runs [onUnlocked] at once when the user has unlocked, otherwise once on the system's
     * user-unlocked broadcast. Returns what unregisters the receiver, or null when none was
     * needed.
     */
    fun whenUnlocked(context: Context, onUnlocked: () -> Unit): (() -> Unit)? {
        if (isUserUnlocked(context)) {
            onUnlocked()
            return null
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                onUnlocked()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return { runCatching { context.unregisterReceiver(receiver) } }
    }
}
