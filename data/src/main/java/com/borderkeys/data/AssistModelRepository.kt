// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.assist.KnownAssistModels
import com.borderkeys.data.dao.AssistModelDao
import com.borderkeys.data.entity.AssistModelEntry
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Importing and verifying the text assistant's model.
 *
 * The same shape as [LanguagePackRepository] and for the same reason: copy into private storage
 * first, hash what actually arrived, and only then decide. A `content://` URI is never opened by
 * the runtime, because the app on the other end can change the file between the check and the
 * use.
 *
 * The difference is what happens after hashing. A language pack is validated structurally and
 * accepted on its own terms; a model is accepted only if its hash is one this application knows.
 * A GGUF file is weights and metadata that a runtime maps and executes in a process holding the
 * user's selected text, and "it had the right extension" is not a basis for that.
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
     * Copies a candidate in, hashes it, and accepts it only if the registry recognises it.
     *
     * The size is checked against the registry entry as well as the hash. That is not
     * redundancy for its own sake: a size mismatch means the copy was truncated, and reporting
     * that as "unknown model" would send the user looking for the wrong problem.
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
            // Deleted rather than kept for the user to "approve later". A file this application
            // has decided not to load has no reason to occupy half a gigabyte of their storage.
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

    /**
     * The model to load, if there is one whose bytes still match what was imported.
     *
     * Re-hashed here rather than trusted, every time, before the file is mapped. Hashing a
     * gigabyte costs a couple of seconds, which is a fraction of loading it -- and this is the
     * last point at which a substituted file can be caught before it is executed.
     */
    suspend fun activeVerifiedModel(): AssistModelEntry? {
        val entry = dao.activeModel() ?: return null
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

    suspend fun remove(entry: AssistModelEntry) {
        fileFor(entry).delete()
        dao.delete(entry)
    }

    private companion object {
        /** No published candidate is close to this; it bounds a hostile or mistaken file. */
        const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024

        fun ByteArray.toHexString(): String {
            val digits = "0123456789abcdef"
            val hex = CharArray(size * 2)
            for (index in indices) {
                val value = this[index].toInt() and 0xFF
                hex[index * 2] = digits[value ushr 4]
                hex[index * 2 + 1] = digits[value and 0x0F]
            }
            return String(hex)
        }
    }
}
