package com.postsaimanager.core.ai.catalog.gguf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.postsaimanager.core.ai.catalog.InstalledModelStore
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of a copy-in, so a multi-gigabyte import is not a frozen screen. */
data class ImportProgress(val bytesCopied: Long, val totalBytes: Long?)

/**
 * Imports a user-supplied GGUF file into app-private storage.
 *
 * This is the escape hatch from the curated catalog: any compatible model the user has,
 * including ones fine-tuned themselves, without waiting for it to appear in a manifest.
 *
 * Two safeguards, because an imported file has **no publisher hash to verify against**:
 *
 * 1. The GGUF header is validated *before* copying, so a wrong file is rejected in
 *    milliseconds rather than after a 2 GB copy.
 * 2. SHA-256 is computed **during** the copy, in the same pass — the file is never read
 *    twice, and the recorded hash is of exactly the bytes that landed on disk.
 */
@Singleton
class ModelImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedStore: InstalledModelStore,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun import(
        uri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): PamResult<InstalledModel> = withContext(ioDispatcher) {
        val displayName = queryDisplayName(uri) ?: "imported-model.gguf"
        val declaredSize = querySize(uri)

        // Header check first — a 2 GB copy that ends in "this was not a model" is a
        // terrible experience, and rejecting costs 24 bytes.
        val validation = runCatching {
            context.contentResolver.openInputStream(uri)?.use(GgufReader::validate)
        }.getOrNull() ?: return@withContext PamResult.Error(
            PamError.FileNotFound("Could not open the selected file."),
        )

        when (validation) {
            is GgufValidation.NotGguf -> return@withContext PamResult.Error(
                PamError.InferenceError(
                    "That file is not a GGUF model. Models usually end in .gguf.",
                ),
            )
            is GgufValidation.UnsupportedVersion -> return@withContext PamResult.Error(
                PamError.InferenceError(
                    "This GGUF file uses format version ${validation.version}, which this " +
                        "app does not support. Try a model exported with current tooling.",
                ),
            )
            is GgufValidation.Truncated -> return@withContext PamResult.Error(
                PamError.InferenceError("The file is incomplete or empty."),
            )
            is GgufValidation.Implausible -> return@withContext PamResult.Error(
                PamError.InferenceError("The file looks corrupt: ${validation.reason}."),
            )
            is GgufValidation.Valid -> Unit
        }

        val modelsDir = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
        val target = File(modelsDir, sanitize(displayName))
        val partial = File(modelsDir, target.name + ".importing")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L

            context.contentResolver.openInputStream(uri)?.use { raw ->
                DigestInputStream(raw.buffered(), digest).use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(ImportProgress(copied, declaredSize))
                        }
                    }
                }
            } ?: return@withContext PamResult.Error(
                PamError.FileNotFound("Could not read the selected file."),
            )

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext PamResult.Error(
                    PamError.InferenceError("Could not move the imported model into place."),
                )
            }

            val model = InstalledModel(
                id = "imported-${target.nameWithoutExtension}",
                descriptorId = null,
                name = target.nameWithoutExtension,
                filePath = target.absolutePath,
                sizeBytes = target.length(),
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                // Unknown until the engine reads the header's metadata; a safe default
                // that every current model supports.
                contextTokens = DEFAULT_CONTEXT_TOKENS,
                source = ModelSource.IMPORTED,
                installedAt = System.currentTimeMillis(),
            )
            installedStore.add(model)
            PamResult.Success(model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            PamResult.Error(PamError.InferenceError("Import failed: ${e.message}", e))
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
        }
    }.getOrNull()

    /** Strips path separators so a hostile display name cannot escape the models directory. */
    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
        return if (cleaned.endsWith(".gguf")) cleaned else "$cleaned.gguf"
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val BUFFER_BYTES = 64 * 1024
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}
