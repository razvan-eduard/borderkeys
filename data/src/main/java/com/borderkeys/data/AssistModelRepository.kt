// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.assist.KnownAssistModels
import com.borderkeys.data.dao.AssistModelDao
import com.borderkeys.data.entity.AssistModelEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Importing and verifying the text assistant's model: copied into private storage, hashed, and
 * accepted only if [KnownAssistModels] knows the hash. A `content://` URI is never opened by the
 * runtime.
 */
class AssistModelRepository internal constructor(
    private val dao: AssistModelDao,
    private val modelsDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val models: Flow<List<AssistModelEntry>> = dao.observeAll()

    fun fileFor(entry: AssistModelEntry): File = File(modelsDirectory, entry.fileName)

    sealed interface ImportResult {
        data class Accepted(val entry: AssistModelEntry) : ImportResult
        /** The bytes are fine; nothing in the registry has that hash. */
        data class UnknownModel(val sha256: String, val sizeBytes: Long) : ImportResult
        data class Failed(val cause: IOException) : ImportResult
    }

    /**
     * Copies a candidate in, hashes it, and accepts it only if the registry recognises it; a size
     * mismatch is reported apart from an unknown hash.
     */
    suspend fun import(source: InputStream, suggestedName: String): ImportResult {
        if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
            return ImportResult.Failed(IOException("could not create ${modelsDirectory.path}"))
        }
        val temporary = File(modelsDirectory, "$suggestedName.part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_MODEL_BYTES) {
                        throw IOException("the model exceeds the $MAX_MODEL_BYTES byte limit")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (error: IOException) {
            temporary.delete()
            return ImportResult.Failed(error)
        }

        val hash = digest.digest().toHexString()
        val known = KnownAssistModels.bySha256(hash)
        if (known == null || known.sizeBytes != total) {
            temporary.delete()
            return ImportResult.UnknownModel(hash, total)
        }

        val destination = File(modelsDirectory, known.fileName)
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            return ImportResult.Failed(IOException("could not move the model into place"))
        }

        val entry = AssistModelEntry(
            displayName = known.displayName,
            fileName = known.fileName,
            sha256 = known.sha256,
            sizeBytes = known.sizeBytes,
            license = known.license,
            source = known.source,
            contextTokens = known.contextTokens,
            importedAt = now(),
            active = true,
        )
        val id = dao.insert(entry)
        dao.setActive(id)
        return ImportResult.Accepted(entry.copy(id = id))
    }

    /** The model to load, re-hashed before every load, if its bytes still match the import. */
    suspend fun activeVerifiedModel(): AssistModelEntry? = verified(dao.activeModel())

    /**
     * The imported model with this file name, re-hashed the same way [activeVerifiedModel] does,
     * or null if there is no such model or its bytes no longer match. For a per-category model
     * override -- see [com.borderkeys.data.theme.KeyboardPreferences.assistTranslateModel].
     */
    suspend fun verifiedModelByFileName(fileName: String): AssistModelEntry? {
        if (fileName.isBlank()) {
            return null
        }
        return verified(dao.observeAll().first().firstOrNull { it.fileName == fileName })
    }

    private suspend fun verified(entry: AssistModelEntry?): AssistModelEntry? {
        if (entry == null) {
            return null
        }
        val file = fileFor(entry)
        val actual = runCatching { LanguagePackRepository.sha256Of(file) }.getOrNull()
        if (actual == null || !actual.equals(entry.sha256, ignoreCase = true) ||
            file.length() != entry.sizeBytes
        ) {
            dao.markIntegrityFailure(entry.id, now())
            return null
        }
        return entry
    }

    /** Makes [entry] the one active model, deactivating the previous one. */
    suspend fun activate(entry: AssistModelEntry) {
        dao.setActive(entry.id)
    }

    suspend fun remove(entry: AssistModelEntry) {
        fileFor(entry).delete()
        dao.delete(entry)
        // Removing the active model activates the most recently imported one left.
        if (entry.active) {
            dao.observeAll().first().firstOrNull()?.let { dao.setActive(it.id) }
        }
    }

    private companion object {
        /** No published candidate is close to this; it bounds a hostile or mistaken file. */
        const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024
    }
}
