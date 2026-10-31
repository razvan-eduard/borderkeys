// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.backup.BackupRepository
import io.mockk.mockk

/**
 * The backups of [tables], through their repository, its database's other tables holding nothing:
 * a restore is gathered from the tables and written back into them, as the backup screen restores
 * a file.
 */
class MemoryBackups(tables: MemoryDictionary, fallbackLanguage: String, now: () -> Long) {

    private val backups =
        BackupRepository(mockk(relaxed = true), mockk(relaxed = true), tables.repository, { fallbackLanguage }, now)

    /** Gathers the personal dictionary's part of a backup and restores it into the same tables. */
    suspend fun restoreWhatIsHeld() {
        backups.apply(backups.gather(DICTIONARY), DICTIONARY)
    }

    private companion object {
        /** The personal dictionary's part of a backup alone. */
        val DICTIONARY = BackupRepository.Parts(dictionary = true)
    }
}
