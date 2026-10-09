package com.postsaimanager.core.data.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.DocumentPage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk copy of a document's scanned page images.
 *
 * `ScannerViewModel` hands the repository whatever URI ML Kit's document scanner returned —
 * a `content://` URI into ML Kit's own scan cache. That cache is not ours: it can be cleared
 * by the OS or by ML Kit itself at any time, which would silently turn every page image in the
 * app into a broken link. This store copies each page into app-private storage
 * (`filesDir/documents/<documentId>/page-<n>.jpg`) the moment a document is created, so the
 * app owns the bytes it shows for the rest of the document's life.
 *
 * It also fills in the page's true width/height — the scanner result carries neither — by
 * decoding just the image bounds, not the full bitmap, so a multi-page scan costs a handful of
 * header reads rather than a handful of full decodes.
 */
@Singleton
class PageImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val documentsRoot: File
        get() = File(context.filesDir, "documents")

    /**
     * Copies every page's source image into `filesDir/documents/<documentId>/`, returning the
     * pages with [DocumentPage.imagePath] rewritten to the new `file://` location and
     * [DocumentPage.width]/[DocumentPage.height] set to the image's real dimensions.
     *
     * All-or-nothing: if any page fails to copy, the partial directory is removed and an error
     * is returned, rather than persisting a document with some pages pointing at files that
     * were never written.
     */
    suspend fun storePages(documentId: String, pages: List<DocumentPage>): PamResult<List<DocumentPage>> =
        withContext(ioDispatcher) {
            val docDir = File(documentsRoot, documentId)
            try {
                docDir.mkdirs()
                val stored = pages.map { page -> storePage(docDir, page) }
                PamResult.Success(stored)
            } catch (e: Exception) {
                Log.w(TAG, "failed to store pages for $documentId: ${e.message}")
                docDir.deleteRecursively()
                PamResult.Error(PamError.FileWriteError(cause = e))
            }
        }

    /**
     * Copies an imported PDF (a `file://` URI of the import's temporary copy) to `filesDir/documents/<documentId>/original.pdf` and
     * returns its `file://` URI. It sits in the document's own folder, so [deleteDocumentImages] removes it with the pages. Throws
     * when the copy fails, which `createDocument` treats like a failed page copy.
     */
    fun storeOriginal(documentId: String, sourceUri: String): String {
        val docDir = File(documentsRoot, documentId).also { it.mkdirs() }
        val target = File(docDir, ORIGINAL_NAME)
        val source = Uri.parse(sourceUri)
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open the original file")
        return Uri.fromFile(target).toString()
    }

    /** Removes a document's whole image directory. Safe to call on a document with none. */
    suspend fun deleteDocumentImages(documentId: String) {
        withContext(ioDispatcher) {
            runCatching { File(documentsRoot, documentId).deleteRecursively() }
                .onFailure { e -> Log.w(TAG, "failed to delete images for $documentId: ${e.message}") }
        }
    }

    /** Removes the image files at [paths] (`file://` URIs of pages that were deleted). A file that is not there is no error. */
    suspend fun deleteImages(paths: List<String>) {
        withContext(ioDispatcher) {
            paths.forEach { path ->
                runCatching { Uri.parse(path).path?.let { File(it).delete() } }
                    .onFailure { e -> Log.w(TAG, "failed to delete $path: ${e.message}") }
            }
        }
    }

    private fun storePage(docDir: File, page: DocumentPage): DocumentPage {
        val outFile = File(docDir, "page-${page.pageNumber}.jpg")
        val sourceUri = Uri.parse(page.imagePath)

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open source image for page ${page.pageNumber}")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outFile.absolutePath, bounds)

        return page.copy(
            imagePath = Uri.fromFile(outFile).toString(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
        )
    }

    private companion object {
        const val TAG = "PageImageStore"
        const val ORIGINAL_NAME = "original.pdf"
    }
}
