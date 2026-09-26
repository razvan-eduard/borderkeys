// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.borderkeys.data.DataGraph
import com.borderkeys.i18n.LanguageManager

/**
 * The tile in the quick settings: lit while this build is the keyboard in use, with a line
 * under its name saying so or saying what is still to do. A tap opens the settings, which
 * open on the setup screen and offer the system's keyboard picker while BorderKeys is not
 * the keyboard in use, and on the home screen once it is.
 *
 * The system binds this service through BIND_QUICK_SETTINGS_TILE, the way it binds the
 * input method itself; the app asks for no permission of its own.
 */
class KeyboardTile : TileService() {

    override fun onCreate() {
        super.onCreate()
        DataGraph.install(this)
    }

    override fun onStartListening() {
        val tile = qsTile ?: return
        val state = TileState.of(isBorderKeysEnabled(this), isBorderKeysDefault(this))
        val strings = LanguageManager(this).apply {
            loadResolved(DataGraph.themes.currentPreferences().uiLanguage)
        }
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = strings[state.subtitleKey]
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { openSettings() }
        } else {
            openSettings()
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
