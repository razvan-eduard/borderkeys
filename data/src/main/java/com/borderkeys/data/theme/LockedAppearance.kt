// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * What the keyboard draws with before the user's first unlock, kept in device-protected storage:
 * the two themes and the appearance and layout preferences, as [KeyboardPreferences.forLockedStart]
 * keeps them. Mirrored from the settings after every write, once they are readable.
 */
@Serializable
data class LockedAppearance(
    val theme: KeyboardTheme = KeyboardTheme(),
    val lightTheme: KeyboardTheme = KeyboardTheme(),
    val preferences: KeyboardPreferences = KeyboardPreferences(),
) {
    /** This as a [KeyboardAppearance], with the default effects. */
    fun asAppearance(): KeyboardAppearance =
        KeyboardAppearance(theme, lightTheme, preferences, ParticleEffectsSettings())

    companion object {
        fun of(theme: KeyboardTheme, lightTheme: KeyboardTheme, preferences: KeyboardPreferences) =
            LockedAppearance(theme, lightTheme, preferences.forLockedStart())
    }
}

/** The device-protected copy as flows and suspending updates; the [DataStore] is not exposed. */
class LockedAppearanceRepository internal constructor(private val store: DataStore<LockedAppearance>) {
    val data: Flow<LockedAppearance> = store.data

    /** [data], read on the calling thread. */
    fun current(): LockedAppearance = runBlocking { data.first() }

    suspend fun update(transform: (LockedAppearance) -> LockedAppearance) {
        store.updateData(transform)
    }
}

object LockedAppearanceSerializer : Serializer<LockedAppearance> {

    private val json = PERSISTED_JSON

    override val defaultValue: LockedAppearance = LockedAppearance()

    override suspend fun readFrom(input: InputStream): LockedAppearance {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            val read = json.decodeFromString(LockedAppearance.serializer(), bytes.decodeToString())
            LockedAppearance(
                read.theme.sanitised(),
                read.lightTheme.sanitised(),
                read.preferences.sanitised().forLockedStart(),
            )
        } catch (error: SerializationException) {
            throw CorruptionException("the locked appearance file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the locked appearance file is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: LockedAppearance, output: OutputStream) {
        output.write(json.encodeToString(LockedAppearance.serializer(), t).encodeToByteArray())
    }
}
