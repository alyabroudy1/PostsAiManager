package com.postsaimanager.core.data.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.importing.ImportLimits
import com.postsaimanager.core.domain.importing.ImportProblem
import com.postsaimanager.core.domain.importing.ImportedKind
import com.postsaimanager.core.domain.importing.PageImageSource
import com.postsaimanager.core.domain.importing.RenderResult
import com.postsaimanager.core.domain.importing.StageResult
import com.postsaimanager.core.domain.importing.StagedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PageImageSource] over the platform: `PdfRenderer` for PDFs, `ImageDecoder` for images (`BitmapFactory` and `ExifInterface` on the
 * two Android versions that predate it), no library.
 *
 * Layout under `filesDir/import/<batchId>/`: `staged/` the private copies, `pages/` the rendered page JPEGs, `thumbs/` the sheet's
 * thumbnails. Everything is removed by [discard]. Nothing here logs a file name or any content.
 */
@Singleton
class AndroidPageImageSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val io: CoroutineDispatcher,
) : PageImageSource {

    override val supportsPasswordPdfs: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override suspend fun stage(batchId: String, uri: String): StageResult = withContext(io) {
        val parsed = Uri.parse(uri)
        val name = displayName(parsed)
        val target = File(dir(batchId, STAGED), UuidGenerator.generate()).also { it.parentFile?.mkdirs() }
        try {
            val copy = context.contentResolver.openInputStream(parsed)?.use { input ->
                FileOutputStream(target).use { output -> BoundedCopy.copy(input, output, ImportLimits.MAX_FILE_BYTES) }
            } ?: return@withContext reject(target, ImportProblem.Unreadable(name))
            if (copy !is BoundedCopy.Result.Copied) return@withContext reject(target, ImportProblem.TooLarge(name))

            val kind = FileTypeSniffer.sniff(readHeader(target)) ?: return@withContext reject(target, ImportProblem.NotSupported(name))
            val file = StagedFile(
                id = target.name, path = target.absolutePath, kind = kind, displayName = name,
                sizeBytes = copy.bytes, sha256 = copy.sha256, pageCount = 1,
            )
            val checked = if (kind == ImportedKind.PDF) checkPdf(file, password = null) else checkImage(file)
            if (checked is StageResult.Rejected) target.delete()
            checked
        } catch (e: IOException) {
            Log.w(TAG, "could not copy a file into the batch: ${e.javaClass.simpleName}")
            reject(target, ImportProblem.Unreadable(name))
        } catch (e: SecurityException) {
            reject(target, ImportProblem.Unreadable(name))
        }
    }

    override suspend fun unlock(file: StagedFile, password: String): StageResult = withContext(io) { checkPdf(file, password) }

    override suspend fun thumbnail(batchId: String, file: StagedFile, password: String?): String? = withContext(io) {
        val out = File(dir(batchId, THUMBS), "${file.id}.jpg").also { it.parentFile?.mkdirs() }
        try {
            val bitmap = when (file.kind) {
                ImportedKind.PDF -> when (val page = renderPdfPage(file.path, password, index = 0, longestSide = ImportLimits.THUMBNAIL_LONGEST_SIDE_PX)) {
                    is PdfPage.Rendered -> page.bitmap
                    else -> return@withContext null
                }
                ImportedKind.IMAGE -> decodeImage(file.path, ImportLimits.THUMBNAIL_LONGEST_SIDE_PX)
            }
            writeJpeg(bitmap, out, THUMBNAIL_QUALITY)
            Uri.fromFile(out).toString()
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    override suspend fun renderPages(batchId: String, file: StagedFile, password: String?): RenderResult = withContext(io) {
        val pagesDir = dir(batchId, PAGES).also { it.mkdirs() }
        val written = mutableListOf<File>()
        try {
            when (file.kind) {
                ImportedKind.IMAGE -> {
                    val out = File(pagesDir, "${file.id}-001.jpg")
                    written += out
                    writeJpeg(decodeImage(file.path, ImportLimits.PAGE_LONGEST_SIDE_PX), out, PAGE_QUALITY)
                }
                ImportedKind.PDF -> {
                    val opened = openPdf(file.path, password)
                    if (opened !is PdfOpen.Ok) return@withContext RenderResult.Failed(problemFor(opened, file))
                    opened.use { renderer ->
                        ImportLimits.pageCountProblem(file.displayName, renderer.pageCount)?.let {
                            return@withContext RenderResult.Failed(it)
                        }
                        for (index in 0 until renderer.pageCount) {
                            currentCoroutineContext().ensureActive()
                            val out = File(pagesDir, "${file.id}-%03d.jpg".format(index + 1))
                            written += out
                            writeJpeg(renderPage(renderer, index, ImportLimits.PAGE_LONGEST_SIDE_PX), out, PAGE_QUALITY)
                        }
                    }
                }
            }
            RenderResult.Rendered(written.map { Uri.fromFile(it).toString() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            written.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            // All or nothing: no page of this file stays behind.
            Log.w(TAG, "rendering failed: ${e.javaClass.simpleName}")
            written.forEach { it.delete() }
            RenderResult.Failed(ImportProblem.Broken(file.displayName))
        } catch (e: OutOfMemoryError) {
            written.forEach { it.delete() }
            RenderResult.Failed(ImportProblem.Broken(file.displayName))
        }
    }

    override suspend fun deletePages(pagePaths: List<String>) {
        withContext(io) { pagePaths.forEach { path -> Uri.parse(path).path?.let { File(it).delete() } } }
    }

    override suspend fun discard(batchId: String) {
        withContext(io) { runCatching { batchDir(batchId).deleteRecursively() } }
    }

    // ── PDF ──

    private sealed interface PdfOpen {
        class Ok(val renderer: PdfRenderer, private val descriptor: ParcelFileDescriptor) : PdfOpen {
            inline fun <T> use(block: (PdfRenderer) -> T): T = try {
                block(renderer)
            } finally {
                close()
            }

            fun close() {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }

        data object NeedsPassword : PdfOpen
        data object WrongPassword : PdfOpen
        data object Broken : PdfOpen
    }

    private fun openPdf(path: String, password: String?): PdfOpen {
        val descriptor = try {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            return PdfOpen.Broken
        }
        return try {
            val renderer = if (password != null && supportsPasswordPdfs) {
                PdfRenderer(descriptor, android.graphics.pdf.LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(descriptor)
            }
            PdfOpen.Ok(renderer, descriptor)
        } catch (e: SecurityException) {
            runCatching { descriptor.close() }
            if (password != null) PdfOpen.WrongPassword else PdfOpen.NeedsPassword
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            PdfOpen.Broken
        }
    }

    private fun problemFor(opened: PdfOpen, file: StagedFile): ImportProblem = when (opened) {
        PdfOpen.NeedsPassword -> if (supportsPasswordPdfs) ImportProblem.PasswordRequired(file.displayName) else ImportProblem.PasswordUnsupported(file.displayName)
        PdfOpen.WrongPassword -> ImportProblem.WrongPassword(file.displayName)
        else -> ImportProblem.Broken(file.displayName)
    }

    /** Opens the PDF to count its pages and apply the caps; the result is the file as the sheet and the worker will use it. */
    private fun checkPdf(file: StagedFile, password: String?): StageResult {
        val opened = openPdf(file.path, password)
        if (opened !is PdfOpen.Ok) {
            // A locked PDF stays staged (the sheet asks for its password) where the platform can read it; below Android 15 it can
            // never be read, so that is said now instead of offering a password box.
            return if (opened === PdfOpen.NeedsPassword && supportsPasswordPdfs) {
                StageResult.Staged(file.copy(pageCount = 0, passwordRequired = true))
            } else {
                StageResult.Rejected(problemFor(opened, file))
            }
        }
        return opened.use { renderer ->
            val pages = renderer.pageCount
            ImportLimits.pageCountProblem(file.displayName, pages)?.let { StageResult.Rejected(it) }
                ?: StageResult.Staged(file.copy(pageCount = pages, passwordRequired = false))
        }
    }

    private sealed interface PdfPage {
        class Rendered(val bitmap: Bitmap) : PdfPage
        data object Failed : PdfPage
    }

    private fun renderPdfPage(path: String, password: String?, index: Int, longestSide: Int): PdfPage {
        val opened = openPdf(path, password)
        if (opened !is PdfOpen.Ok) return PdfPage.Failed
        return opened.use { renderer ->
            if (index >= renderer.pageCount) PdfPage.Failed else PdfPage.Rendered(renderPage(renderer, index, longestSide))
        }
    }

    /** One page on a white background: a PDF page is transparent where nothing is drawn, and the pipeline expects paper. */
    private fun renderPage(renderer: PdfRenderer, index: Int, longestSide: Int): Bitmap {
        return renderer.openPage(index).use { page ->
            val (width, height) = PageScaling.fit(page.width, page.height, longestSide)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    // ── Images ──

    /** Valid when the platform can read its size; the type was already checked from the header. */
    private fun checkImage(file: StagedFile): StageResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            StageResult.Staged(file)
        } else {
            StageResult.Rejected(
                // On Android 8 a format only the newer decoder reads (HEIC) is simply not supported; otherwise the file is damaged.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) ImportProblem.NotSupported(file.displayName) else ImportProblem.Broken(file.displayName),
            )
        }
    }

    /** The image with its EXIF rotation applied, no larger than [longestSide], on white (a transparent PNG gets paper behind it). */
    private fun decodeImage(path: String, longestSide: Int): Bitmap {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                val (width, height) = PageScaling.fitDown(info.size.width, info.size.height, longestSide)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeWithBitmapFactory(path, longestSide)
        }
        return onWhite(decoded)
    }

    /** Android 8 only: ImageDecoder starts at Android 9. Samples down while decoding, then applies the EXIF rotation by hand. */
    private fun decodeWithBitmapFactory(path: String, longestSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longestSide) sample *= 2
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("undecodable image")
        val degrees = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val rotated = if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
        val (width, height) = PageScaling.fitDown(rotated.width, rotated.height, longestSide)
        return if (width == rotated.width) rotated else Bitmap.createScaledBitmap(rotated, width, height, true)
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        return flat
    }

    /** A JPEG carries no EXIF unless it is written, so compressing a decoded bitmap also drops the location and camera data. */
    private fun writeJpeg(bitmap: Bitmap, out: File, quality: Int) {
        try {
            FileOutputStream(out).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw IOException("could not write the page")
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ── Files ──

    private fun readHeader(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(FileTypeSniffer.HEADER_BYTES)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        } else {
            null
        }
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: DEFAULT_NAME
    }

    private fun reject(target: File, problem: ImportProblem): StageResult {
        target.delete()
        return StageResult.Rejected(problem)
    }

    private fun batchDir(batchId: String): File {
        require(batchId.isNotBlank() && '/' !in batchId && '\\' !in batchId && ".." !in batchId) { "not a batch id" }
        return File(File(context.filesDir, ROOT), batchId)
    }

    private fun dir(batchId: String, name: String): File = File(batchDir(batchId), name)

    companion object {
        private const val TAG = "PageImageSource"
        const val ROOT = "import"
        private const val STAGED = "staged"
        private const val PAGES = "pages"
        private const val THUMBS = "thumbs"
        private const val DEFAULT_NAME = "file"
        private const val PAGE_QUALITY = 90
        private const val THUMBNAIL_QUALITY = 80

        /** The folder holding an import batch's private files; the queue keeps the batch's request there too. */
        fun batchDirectory(context: Context, batchId: String): File = File(File(context.filesDir, ROOT), batchId)
    }
}
