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
                            val fullText = visionText.text
                            val confidence = if (visionText.textBlocks.isNotEmpty()) {
                                visionText.textBlocks
                                    .flatMap { it.lines }
                                    .mapNotNull { it.confidence }
                                    .average()
                                    .toFloat()
                            } else 0f

                            val blocks = visionText.textBlocks.map { block ->
                                TextBlock(
                                    text = block.text,
                                    confidence = block.lines
                                        .mapNotNull { it.confidence }
                                        .average()
                                        .toFloat(),
                                    language = block.recognizedLanguage,
                                )
                            }

                            continuation.resume(
                                PamResult.Success(
                                    OcrResult(
                                        fullText = fullText,
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
    val blocks: List<TextBlock>,
    val detectedLanguage: String?,
)

data class TextBlock(
    val text: String,
    val confidence: Float,
    val language: String?,
)
