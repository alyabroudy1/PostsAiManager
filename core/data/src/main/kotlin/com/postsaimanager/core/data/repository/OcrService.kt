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
import com.postsaimanager.core.model.OcrLine
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
    // Lazy, not eager: constructing this calls TextRecognition.getClient(), which requires ML
    // Kit's ContentProvider-based init to have already run — true in the main process, never in
    // `:inference` (see PostsAiManagerApp's class KDoc). OcrService itself is only ever *used*
    // from the main process's document pipeline, but Dagger still builds every @Inject
    // constructor's field initialisers as soon as something reaches this class in the graph —
    // deferring the actual TextRecognition.getClient() call to first real use means a stray
    // graph reference (a Lazy<...> some future change forgets to gate, a test harness touching
    // this module) fails only if OCR is actually attempted, not merely because this object was
    // constructed.
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

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
                                    OcrGeometry.normalise(box.left, box.top, box.right, box.bottom, pageWidth, pageHeight)
                                } else {
                                    TextBounds(0f, 0f, 1f, 1f)
                                }
                                // Lines without a box are skipped (no honest place to draw them); the block still has its text.
                                val lines = block.lines.mapNotNull { line ->
                                    line.boundingBox?.let {
                                        OcrLine(line.text, OcrGeometry.normalise(it.left, it.top, it.right, it.bottom, pageWidth, pageHeight))
                                    }
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
                                    lines = lines,
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


