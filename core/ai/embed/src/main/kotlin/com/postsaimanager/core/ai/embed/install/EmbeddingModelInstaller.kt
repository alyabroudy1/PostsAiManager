package com.postsaimanager.core.ai.embed.install

import android.util.Log
import com.postsaimanager.core.ai.embed.EmbeddingModelFiles
import com.postsaimanager.core.ai.embed.LazyEmbeddingService
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.download.DownloadProgress
import com.postsaimanager.core.download.ModelDownloader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the embedding model described by [EmbeddingModelRelease] into app storage.
 *
 * Reuses [ModelDownloader], so it inherits resume-across-process-death, hash verification
 * before the file is moved into place, and recovery from a stale partial file. What it adds
 * is that this is a **two-file** install: the weights are useless without the exact
 * vocabulary they were trained against, so the model is only "installed" when both are
 * present and verified.
 */
@Singleton
class EmbeddingModelInstaller @Inject constructor(
    private val files: EmbeddingModelFiles,
    private val downloader: ModelDownloader,
    private val embeddingService: LazyEmbeddingService,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** Fraction of the whole install, across both files. */
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) {
        val fraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }

    fun isInstalled(): Boolean = files.arePresent()

    /**
     * Downloads whatever is missing and makes the model live.
     *
     * Safe to call when already installed — it verifies presence and returns without
     * touching the network. Safe to call again after a failure: a partial file is resumed
     * rather than restarted.
     */
    suspend fun install(onProgress: (Progress) -> Unit = {}): PamResult<Unit> =
        withContext(ioDispatcher) {
            if (files.arePresent()) return@withContext PamResult.Success(Unit)

            files.directory.mkdirs()

            val targets = listOf(
                EmbeddingModelRelease.model to files.modelFile,
                EmbeddingModelRelease.vocabulary to files.vocabFile,
            )

            // Bytes already banked by completed files, so the reported fraction covers the
            // whole install rather than restarting at zero for the second file.
            var completedBytes = 0L

            for ((asset, destination) in targets) {
                if (destination.isFile && destination.length() == asset.sizeBytes) {
                    // Already downloaded and verified on a previous attempt; the downloader
                    // only ever moves a file into place after its hash matches.
                    completedBytes += asset.sizeBytes
                    onProgress(Progress(completedBytes, EmbeddingModelRelease.totalBytes))
                    continue
                }

                val banked = completedBytes
                val result = downloader.download(
                    url = asset.url,
                    destination = destination,
                    expectedSha256 = asset.sha256,
                    expectedSize = asset.sizeBytes,
                    onProgress = { progress: DownloadProgress ->
                        onProgress(
                            Progress(
                                banked + progress.bytesDownloaded,
                                EmbeddingModelRelease.totalBytes,
                            ),
                        )
                    },
                )

                if (result is PamResult.Error) {
                    Log.w(TAG, "embedding asset failed: ${asset.url} — ${result.error.userMessage}")
                    // Leave whatever succeeded in place. The next attempt skips it, and the
                    // partial file for this one resumes.
                    return@withContext result
                }
                completedBytes += asset.sizeBytes
            }

            // Clears the "load already failed" latch, so a model installed after a failed
            // attempt is tried again instead of staying dormant until the next launch.
            embeddingService.reset()

            Log.i(TAG, "embedding model installed at ${files.directory}")
            PamResult.Success(Unit)
        }

    /**
     * Removes the model.
     *
     * Search degrades to keyword-only rather than breaking, so this is a legitimate thing to
     * offer a user who wants ~257 MB back.
     */
    suspend fun uninstall() = withContext(ioDispatcher) {
        files.directory.deleteRecursively()
        embeddingService.reset()
    }

    /** Files left behind by an interrupted download, for reporting real disk usage. */
    fun partialBytes(): Long =
        files.directory.listFiles()
            ?.filter { it.name.endsWith(PARTIAL_SUFFIX) }
            ?.sumOf(File::length)
            ?: 0L

    private companion object {
        const val TAG = "EmbeddingInstall"
        const val PARTIAL_SUFFIX = ".part"
    }
}
