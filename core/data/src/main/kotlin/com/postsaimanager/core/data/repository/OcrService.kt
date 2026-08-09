package com.postsaimanager.core.data.repository

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.DocumentLayout
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.OcrBlock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Service for running ML Kit Text Recognition on document pages.
 * Extracts raw OCR text from images.
 */
@Singleton
class OcrService @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on a single image URI and return the extracted text with confidence.
     */
    suspend fun recognizeText(imageUri: String): PamResult<OcrResult> =
        withContext(ioDispatcher) {
            try {
                val uri = Uri.parse(imageUri)
                val image = InputImage.fromFilePath(context, uri)

                suspendCancellableCoroutine<PamResult<OcrResult>> { continuation ->
                    textRecognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            // Replaced below with a reading-order rendering; ML Kit's own
                            // `text` concatenates blocks in an unspecified order, which
                            // interleaves side-by-side columns.
                            @Suppress("UNUSED_VARIABLE")
                            val rawText = visionText.text
                            val confidence = if (visionText.textBlocks.isNotEmpty()) {
                                visionText.textBlocks
                                    .flatMap { it.lines }
                                    .mapNotNull { it.confidence }
                                    .average()
                                    .toFloat()
                            } else 0f

                            // Normalised against the image, so the layout describes the
                            // same letter whether it was photographed at 8 or 12 MP.
                            val pageWidth = image.width.toFloat().takeIf { it > 0f } ?: 1f
                            val pageHeight = image.height.toFloat().takeIf { it > 0f } ?: 1f

                            val blocks = visionText.textBlocks.mapNotNull { block ->
                                // ML Kit may return a block without a box. Dropping it here
                                // would lose text; a full-page box is the honest fallback —
                                // it says "somewhere on this page", which is true.
                                val box = block.boundingBox
                                val bounds = if (box != null) {
                                    TextBounds(
                                        left = (box.left / pageWidth).coerceIn(0f, 1f),
                                        top = (box.top / pageHeight).coerceIn(0f, 1f),
                                        right = (box.right / pageWidth).coerceIn(0f, 1f),
                                        bottom = (box.bottom / pageHeight).coerceIn(0f, 1f),
                                    )
                                } else {
                                    TextBounds(0f, 0f, 1f, 1f)
                                }

                                OcrBlock(
                                    text = block.text,
                                    bounds = bounds,
                                    confidence = block.lines
                                        .mapNotNull { it.confidence }
                                        .average()
                                        .toFloat()
                                        .takeIf { !it.isNaN() } ?: 0f,
                                    language = block.recognizedLanguage,
                                )
                            }

                            continuation.resume(
                                PamResult.Success(
                                    OcrResult(
                                        // Reading order, so the address block and the
                                        // reference block beside it stay whole instead of
                                        // interleaving line by line.
                                        fullText = DocumentLayout.plainText(blocks),
                                        confidence = confidence,
                                        blocks = blocks,
                                        detectedLanguage = visionText.textBlocks
                                            .firstOrNull()?.recognizedLanguage,
                                    )
                                )
                            )
                        }
                        .addOnFailureListener { e ->
                            continuation.resume(
                                PamResult.Error(
                                    PamError.OcrFailed(
                                        detail = e.message ?: "OCR failed",
                                        cause = e,
                                    )
                                )
                            )
                        }
                }
            } catch (e: Exception) {
                PamResult.Error(
                    PamError.OcrFailed(detail = e.message ?: "Failed to process image", cause = e)
                )
            }
        }

    /**
     * Run OCR on multiple pages sequentially.
     */
    suspend fun recognizePages(imageUris: List<String>): List<PamResult<OcrResult>> =
        imageUris.map { recognizeText(it) }
}

data class OcrResult(
    val fullText: String,
    val confidence: Float,
    val blocks: List<OcrBlock>,
    val detectedLanguage: String?,
)


