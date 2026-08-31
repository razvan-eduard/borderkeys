// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Produces the SQLCipher passphrase, generating it on first run and keeping it afterwards.
 *
 * The chain is: 32 random bytes from [SecureRandom], stored base64 in an
 * [EncryptedSharedPreferences] file, which is itself encrypted with a key that never leaves the
 * Android Keystore. What that buys is that the database file is useless on its own -- pulled off
 * a backup, off a rooted filesystem, out of an ADB extraction -- because the key it needs is
 * held by hardware on one device and cannot be exported from it.
 *
 * What it does not buy, and is worth being clear about: an attacker who is running code as this
 * app, on this unlocked device, can ask the Keystore to decrypt for them. Keystore protects the
 * key against exfiltration, not against use. Everything in this database is protected against
 * the file being copied elsewhere, which is the realistic threat for a keyboard.
 */
internal object DatabasePassphrase {

    private const val PREFERENCES_FILE = "borderkeys_keys"
    private const val PASSPHRASE_KEY = "db_passphrase_v1"
    private const val PASSPHRASE_BYTES = 32

    /**
     * Returns a freshly allocated copy of the passphrase. The caller owns it and should zero it
     * once SQLCipher has taken it; SQLCipher keeps its own copy.
     */
    fun obtain(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val preferences = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFERENCES_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        val existing = preferences.getString(PASSPHRASE_KEY, null)
        if (existing != null) {
            val decoded = Base64.decode(existing, Base64.NO_WRAP)
            // A stored value of the wrong length means the file was tampered with or a previous
            // write was truncated. Falling through to generate a new one would silently orphan
            // the whole database, so this fails loudly instead.
            check(decoded.size == PASSPHRASE_BYTES) {
                "stored database passphrase has ${decoded.size} bytes, expected $PASSPHRASE_BYTES"
            }
            return decoded
        }

        val generated = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(generated)
        // commit(), not apply(). The passphrase must be on disk before the database it protects
        // is created; an asynchronous write that loses a race with a process death would leave a
        // database nothing can ever open again.
        val stored = preferences.edit()
            .putString(PASSPHRASE_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
            .commit()
        check(stored) { "could not persist the database passphrase" }
        return generated
    }
}
