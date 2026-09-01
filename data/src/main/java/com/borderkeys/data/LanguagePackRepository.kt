// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LanguagePackDao
import com.borderkeys.data.entity.LanguagePackEntry
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Importing, verifying and enumerating language packs.
 *
 * The single most dangerous operation in the application. A keyboard with no permissions that
 * maps a file the user was given becomes a parser of untrusted input doing pointer arithmetic
 * inside the process that sees every character typed on the device. The native side treats the
 * bytes as hostile; this side makes sure the bytes cannot change underneath it.
 *
 * The order is not negotiable: **copy first, then validate, then map.** A `content://` URI is
 * never mapped directly, because the app on the other end of it can rewrite the file between the
 * moment it is validated and the moment it is used -- and the gap between what was checked and
 * what was mapped is exactly the class of bug the validation exists to prevent.
 */
class LanguagePackRepository internal constructor(
    private val dao: LanguagePackDao,
    private val packsDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val packs: Flow<List<LanguagePackEntry>> = dao.observeAll()

    suspend fun enabledPacks(): List<LanguagePackEntry> = dao.enabledPacks()

    fun fileFor(entry: LanguagePackEntry): File = File(packsDirectory, entry.fileName)

    /**
     * Result of copying a candidate into private storage. The caller validates it natively --
     * that code lives in :keyboard -- and then either registers it or discards it.
     */
    data class StagedPack(val file: File, val sizeBytes: Long, val sha256: String)

    sealed interface ImportFailure {
        data object TooLarge : ImportFailure
        data object Empty : ImportFailure
        data class Io(val cause: IOException) : ImportFailure
    }

    /**
     * Copies [source] into private storage and hashes it on the way through.
     *
     * The size cap is enforced while copying, not from a length the caller reported: a stream
     * can claim any length it likes, and the only number that means anything is how many bytes
     * actually arrived. The temporary file is deleted on any failure, so a refused import leaves
     * nothing behind.
     */
    fun stage(source: InputStream, fileName: String): Result<StagedPack> {
        if (!packsDirectory.exists() && !packsDirectory.mkdirs()) {
            return Result.failure(IOException("could not create ${packsDirectory.path}"))
        }
        val destination = File(packsDirectory, fileName)
        val temporary = File(packsDirectory, "$fileName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    total += read
                    if (total > MAX_PACK_BYTES) {
                        throw IOException("the pack exceeds the ${MAX_PACK_BYTES} byte limit")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            if (total == 0L) {
                throw IOException("the pack is empty")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("could not move the staged pack into place")
            }
        } catch (error: IOException) {
            temporary.delete()
            destination.delete()
            return Result.failure(error)
        }
        return Result.success(StagedPack(destination, total, digest.digest().toHexString()))
    }

    suspend fun register(entry: LanguagePackEntry): Long = dao.insert(entry)

    /** True when a pack for this language is already installed, whatever it came from. */
    suspend fun hasLanguage(tag: String): Boolean = dao.findByTag(tag) != null

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun setWeight(id: Long, weight: Float) = dao.setWeight(id, weight.coerceIn(0.05f, 4f))

    suspend fun remove(entry: LanguagePackEntry) {
        fileFor(entry).delete()
        dao.delete(entry)
    }

    /**
     * Re-hashes every enabled pack and disables any whose contents changed.
     *
     * Run at start, before anything is mapped. The file is in private storage, but private is a
     * statement about other applications -- not about a restore that substituted it, a
     * filesystem that corrupted it, or a device where the boundary does not hold. A pack that
     * fails switches itself off and records when, so Settings can say what happened rather than
     * the language quietly ceasing to produce suggestions.
     *
     * Returns the packs that failed.
     */
    suspend fun verifyEnabled(): List<LanguagePackEntry> {
        val failed = ArrayList<LanguagePackEntry>()
        for (entry in dao.enabledPacks()) {
            val file = fileFor(entry)
            val actual = runCatching { sha256Of(file) }.getOrNull()
            if (actual == null || actual != entry.sha256 || file.length() != entry.sizeBytes) {
                dao.markIntegrityFailure(entry.id, now())
                failed += entry
            }
        }
        return failed
    }

    companion object {
        /** Matches kMaxPackBytes in bkd_format.hpp. Checked on both sides, on purpose. */
        const val MAX_PACK_BYTES: Long = 64L * 1024L * 1024L

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHexString()
        }

        private fun ByteArray.toHexString(): String {
            val hex = CharArray(size * 2)
            val digits = "0123456789abcdef"
            for (index in indices) {
                val value = this[index].toInt() and 0xFF
                hex[index * 2] = digits[value ushr 4]
                hex[index * 2 + 1] = digits[value and 0x0F]
            }
            return String(hex)
        }
    }
}
