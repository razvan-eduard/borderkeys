// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LanguagePackDao
import com.borderkeys.data.entity.LanguagePackEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Importing, verifying and enumerating language packs. A pack is copied into private storage,
 * then validated, then mapped, in that order; a `content://` URI is never mapped directly.
 */
class LanguagePackRepository internal constructor(
    private val dao: LanguagePackDao,
    private val packsDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val packs: Flow<List<LanguagePackEntry>> = dao.observeAll()

    suspend fun enabledPacks(): List<LanguagePackEntry> = dao.enabledPacks()

    /** How many packs are switched on -- what the [MAX_ENABLED] limit is checked against. */
    suspend fun enabledCount(): Int = dao.enabledPacks().size

    /** Every installed pack, enabled or not. */
    suspend fun allPacks(): List<LanguagePackEntry> = packs.first()

    fun fileFor(entry: LanguagePackEntry): File = File(packsDirectory, entry.fileName)

    /**
     * The SHA-256 of every pack file hashed in this process, keyed by path, with the size and
     * modification time it was hashed at. [stage] seeds the entry for the file it writes.
     */
    private val hashes = HashMap<String, CachedHash>()

    private class CachedHash(val sizeBytes: Long, val modifiedAt: Long, val sha256: String)

    /** [sha256Of], remembered per file until its size or modification time changes. */
    fun cachedSha256(file: File): String {
        val size = file.length()
        val modified = file.lastModified()
        synchronized(hashes) {
            val cached = hashes[file.path]
            if (cached != null && cached.sizeBytes == size && cached.modifiedAt == modified) {
                return cached.sha256
            }
        }
        val sha256 = sha256Of(file)
        synchronized(hashes) { hashes[file.path] = CachedHash(size, modified, sha256) }
        return sha256
    }

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
     * Copies [source] into private storage, hashing it on the way. The size cap applies to the
     * bytes that arrive; the temporary file is deleted on any failure.
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
        val sha256 = digest.digest().toHexString()
        synchronized(hashes) {
            hashes[destination.path] = CachedHash(total, destination.lastModified(), sha256)
        }
        return Result.success(StagedPack(destination, total, sha256))
    }

    suspend fun register(entry: LanguagePackEntry): Long = dao.insert(entry)

    /**
     * Records [entry], taking the place of a pack already recorded for the same language or
     * under the same file name: the row keeps its id, its switch and its weight, and a file
     * the replaced row named that this one does not is deleted.
     */
    suspend fun registerOrReplace(entry: LanguagePackEntry): Long {
        val existing = dao.findByTag(entry.tag) ?: dao.findByFileName(entry.fileName)
            ?: return dao.insert(entry)
        if (existing.fileName != entry.fileName) {
            fileFor(existing).delete()
        }
        dao.update(entry.copy(id = existing.id, enabled = existing.enabled, weight = existing.weight))
        return existing.id
    }

    /** Updates the record of a pack that was rewritten in place. */
    suspend fun replace(entry: LanguagePackEntry) = dao.update(entry)

    /** True when a pack for this language is already installed, whatever it came from. */
    suspend fun hasLanguage(tag: String): Boolean = dao.findByTag(tag) != null

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun setWeight(id: Long, weight: Float) = dao.setWeight(id, weight.coerceIn(0.05f, 4f))

    suspend fun remove(entry: LanguagePackEntry) {
        fileFor(entry).delete()
        dao.delete(entry)
    }

    /**
     * Re-hashes every enabled pack and disables any whose contents changed, recording when. Run
     * at start, before anything is mapped. Returns the packs that failed.
     */
    suspend fun verifyEnabled(): List<LanguagePackEntry> {
        val failed = ArrayList<LanguagePackEntry>()
        for (entry in dao.enabledPacks()) {
            val file = fileFor(entry)
            val actual = runCatching { cachedSha256(file) }.getOrNull()
            if (actual == null || actual != entry.sha256 || file.length() != entry.sizeBytes) {
                dao.markIntegrityFailure(entry.id, now())
                failed += entry
            }
        }
        return failed
    }

    companion object {
        /** Matches kMaxPackBytes in bkd_format.hpp; checked on both sides. */
        const val MAX_PACK_BYTES: Long = 64L * 1024L * 1024L

        /**
         * How many packs may be switched on at once. Matches `Engine::kMaxPacks` in engine.hpp,
         * checked by LanguagePackLimitTest.
         */
        const val MAX_ENABLED = 4

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
    }
}
