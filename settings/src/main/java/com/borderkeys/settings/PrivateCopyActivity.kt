// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.PrivateCopyRateLimit
import kotlinx.coroutines.runBlocking

/**
 * "Keep privately with BorderKeys" in other apps' text-selection menu: keeps the selection as a
 * private clipboard entry and finishes, drawing nothing and returning nothing. Disabled until
 * the Clipboard screen turns it on; rate-limited per app and in all.
 */
class PrivateCopyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrEmpty() && text.length <= MAX_CHARS) {
                keep(text, referrer?.host)
            }
        }
        finish()
    }

    private fun keep(text: String, source: String?) {
        val limits = getSharedPreferences(RATE_PREFS, MODE_PRIVATE)
        val history = limits.getString(RATE_HISTORY, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val decision = PrivateCopyRateLimit.decide(System.currentTimeMillis(), source, history)
        limits.edit().putString(RATE_HISTORY, decision.history.joinToString("\n")).apply()
        if (!decision.allowed) {
            return
        }
        DataGraph.install(applicationContext)
        runBlocking { DataGraph.clipboard.rememberPrivately(text, source) }
    }

    companion object {
        private const val RATE_PREFS = "private_copy_rate"
        private const val RATE_HISTORY = "history"
        private const val MAX_CHARS = 20_000

        /** Shows or hides the entry in other apps' text-selection menu. */
        fun setOffered(context: Context, offered: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, PrivateCopyActivity::class.java),
                if (offered) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
