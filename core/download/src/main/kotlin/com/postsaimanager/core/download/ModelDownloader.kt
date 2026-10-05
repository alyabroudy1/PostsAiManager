package com.postsaimanager.core.download

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Progress of an in-flight model download. */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toFloat() / it }
}

class ModelDownloader(
    private val httpClient: HttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Downloads [url] to [destination], resuming an existing partial file where possible,
     * and verifies the result against [expectedSha256].
     *
     * Survives process death: progress lives in the partial file on disk, not in memory, so
     * a killed download resumes from wherever the bytes stopped. Cancellation leaves the
     * partial file intact for the same reason.
     *
     * The file is only moved into place after the hash matches — a corrupt or truncated
     * download can never be mistaken for an installed model.
     */
    suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSize: Long?,
        onProgress: (DownloadProgress) -> Unit = {},
    ): PamResult<File> = withContext(ioDispatcher) {
        val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
        partial.parentFile?.mkdirs()

        try {
            var decision = ResumePolicy.decide(
                existingBytes = if (partial.exists()) partial.length() else 0L,
                expectedTotal = expectedSize,
            )

            if (decision !is ResumePolicy.Decision.AlreadyComplete) {
                val outcome = fetch(url, partial, decision, expectedSize, onProgress)

                // A stale partial yields 416; recover silently with a fresh download
                // rather than surfacing an error the user cannot act on.
                if (outcome == FetchOutcome.RESTART_REQUIRED) {
                    partial.delete()
                    decision = ResumePolicy.Decision.StartFresh
                    fetch(url, partial, decision, expectedSize, onProgress)
                }
            }

            val actualSha = sha256Of(partial)
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                // Never leave bytes that failed verification where a retry could resume
                // onto them.
                partial.delete()
                return@withContext PamResult.Error(
                    PamError.InferenceError(
                        "Downloaded model failed integrity check. Expected $expectedSha256, got $actualSha.",
                    ),
                )
            }

            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                return@withContext PamResult.Error(
                    PamError.InferenceError("Could not move the verified model into place."),
                )
            }

            PamResult.Success(destination)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // keep the partial file; cancellation is not a failure
        } catch (e: Exception) {
            PamResult.Error(PamError.InferenceError("Model download failed: ${e.message}", e))
        }
    }

    private enum class FetchOutcome { COMPLETE, RESTART_REQUIRED }

    private suspend fun fetch(
        url: String,
        partial: File,
        decision: ResumePolicy.Decision,
        expectedSize: Long?,
        onProgress: (DownloadProgress) -> Unit,
    ): FetchOutcome {
        val offset = (decision as? ResumePolicy.Decision.Resume)?.fromByte ?: 0L

        return httpClient.prepareGet(url) {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.execute { response ->
            val status = response.status.value

            if (ResumePolicy.shouldRestartAfter(status)) return@execute FetchOutcome.RESTART_REQUIRED
            if (!ResumePolicy.isSuccess(status)) error("HTTP $status")

            val writeMode = ResumePolicy.writeModeFor(status, offset)
            val startAt = if (writeMode == ResumePolicy.WriteMode.APPEND) offset else 0L

            // Content-Length is the *remaining* bytes on a 206, so add the offset back.
            val total = expectedSize
                ?: response.headers["Content-Length"]?.toLongOrNull()?.plus(startAt)

            RandomAccessFile(partial, "rw").use { file ->
                if (writeMode == ResumePolicy.WriteMode.TRUNCATE) file.setLength(0)
                file.seek(startAt)

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                var written = startAt

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    file.write(buffer, 0, read)
                    written += read
                    onProgress(DownloadProgress(written, total))
                }
            }
            FetchOutcome.COMPLETE
        }
    }

    /** Streamed so a 2 GB model is never held in memory. */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val DEFAULT_BUFFER_BYTES = 64 * 1024
    }
}
