package com.postsaimanager.core.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts document page images into a single PDF file.
 * Uses Android's built-in PdfDocument API — no external dependencies.
 */
@Singleton
class PdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Generate a PDF from a list of image paths.
     * @return File path of the generated PDF, or null on failure.
     */
    fun generatePdf(imagePaths: List<String>, outputName: String): File? {
        if (imagePaths.isEmpty()) return null

        val pdfDir = File(context.cacheDir, "shared_pdfs").apply { mkdirs() }
        val outputFile = File(pdfDir, "${outputName}.pdf")

        return try {
            val document = PdfDocument()

            imagePaths.forEachIndexed { index, path ->
                val bitmap = loadBitmap(path) ?: return@forEachIndexed

                // A4 at 72 DPI: 595 x 842 points
                val pageWidth = 595
                val pageHeight = 842

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)

                val canvas = page.canvas

                // Scale bitmap to fit page while maintaining aspect ratio
                val scale = minOf(
                    pageWidth.toFloat() / bitmap.width,
                    pageHeight.toFloat() / bitmap.height,
                )
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()
                val left = (pageWidth - scaledWidth) / 2f
                val top = (pageHeight - scaledHeight) / 2f

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                canvas.drawBitmap(scaledBitmap, left, top, null)

                document.finishPage(page)

                // Recycle bitmaps
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                bitmap.recycle()
            }

            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }
            document.close()

            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val uri = Uri.parse(path)
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                "file" -> BitmapFactory.decodeFile(uri.path)
                else -> BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) { null }
    }
}
