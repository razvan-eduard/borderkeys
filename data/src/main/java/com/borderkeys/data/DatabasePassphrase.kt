// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Produces the SQLCipher passphrase, generating it on first run and keeping it afterwards: 32
 * random bytes from [SecureRandom], stored base64 in an [EncryptedSharedPreferences] file whose
 * key stays in the Android Keystore.
 */
internal object DatabasePassphrase {

    private const val PREFERENCES_FILE = "borderkeys_keys"
    private const val PASSPHRASE_KEY = "db_passphrase_v1"
    private const val PASSPHRASE_BYTES = 32
    private const val LOCK_FILE_NAME = "db_passphrase.lock"

    /**
     * Returns a freshly allocated copy of the passphrase, which the caller zeroes once SQLCipher
     * has taken it. The read-or-generate runs behind a cross-process [FileLock], since `:app` and
     * `:assist` both open the database.
     */
    fun obtain(context: Context): ByteArray {
        val lockFile = File(context.applicationContext.filesDir, LOCK_FILE_NAME)
        RandomAccessFile(lockFile, "rw").use { raf ->
            // Released by this `use`, before the channel closes.
            raf.channel.lock().use {
                return obtainLocked(context)
            }
        }
    }

    private fun obtainLocked(context: Context): ByteArray {
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
            // A stored value of the wrong length fails rather than being replaced.
            check(decoded.size == PASSPHRASE_BYTES) {
                "stored database passphrase has ${decoded.size} bytes, expected $PASSPHRASE_BYTES"
            }
            return decoded
        }

        val generated = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(generated)
        // commit(): on disk before the database is created.
        val stored = preferences.edit()
            .putString(PASSPHRASE_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
            .commit()
        check(stored) { "could not persist the database passphrase" }
        return generated
    }
}
