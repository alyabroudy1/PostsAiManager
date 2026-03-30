package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full document processing pipeline:
 * 1. Update document status to PROCESSING
 * 2. Run OCR on each page
 * 3. Save OCR text to page entities
 * 4. Run entity extraction on combined text
 * 5. Save extracted data
 * 6. Update document status to EXTRACTED
 * 7. Log timeline events
 */
@Singleton
class DocumentProcessingPipeline @Inject constructor(
    private val ocrService: OcrService,
    private val entityExtractor: EntityExtractor,
    private val documentDao: DocumentDao,
    private val timelineRepository: TimelineRepository,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: Flow<ProcessingState> = _processingState.asStateFlow()

    suspend fun processDocument(documentId: String): PamResult<ExtractionResult> =
        withContext(ioDispatcher) {
            try {
                // Step 1: Mark as processing
                _processingState.value = ProcessingState.Running(documentId, "Starting OCR...", 0f)
                documentDao.updateStatus(documentId, DocumentStatus.PROCESSING.name)

                // Step 2: Get pages
                val pages = documentDao.getPages(documentId)
                if (pages.isEmpty()) {
                    return@withContext PamResult.Error(
                        PamError.OcrFailed(detail = "No pages found for document")
                    )
                }

                // Step 3: OCR each page
                val ocrResults = mutableListOf<OcrResult>()
                pages.forEachIndexed { index, page ->
                    val progress = (index + 1).toFloat() / pages.size * 0.6f
                    _processingState.value = ProcessingState.Running(
                        documentId,
                        "OCR: Page ${index + 1}/${pages.size}",
                        progress,
                    )

                    val result = ocrService.recognizeText(page.imagePath)
                    val ocrResult = result.getOrNull()
                    if (ocrResult != null) {
                        ocrResults.add(ocrResult)
                        // Update page with OCR text
                        documentDao.insertPages(
                            listOf(
                                page.copy(
                                    ocrText = ocrResult.fullText,
                                    ocrConfidence = ocrResult.confidence,
                                )
                            )
                        )
                    }
                }

                // Log OCR event
                timelineRepository.recordEvent(
                    TimelineEvent(
                        id = UuidGenerator.generate(),
                        documentId = documentId,
                        eventType = TimelineEventType.TEXT_EXTRACTED,
                        title = "Text extracted from ${pages.size} page(s)",
                        description = "Average confidence: ${
                            ocrResults.map { it.confidence }.average().let { "%.0f%%".format(it * 100) }
                        }",
                        createdAt = System.currentTimeMillis(),
                    )
                )

                // Step 4: Entity extraction (with built-in language detection)
                _processingState.value = ProcessingState.Running(
                    documentId, "Analyzing document structure...", 0.7f
                )

                val combinedText = ocrResults.joinToString("\n\n") { it.fullText }

                val extraction = entityExtractor.extract(documentId, combinedText, null)

                // Step 5: Save extracted data (clear old data first for retry support)
                _processingState.value = ProcessingState.Running(
                    documentId, "Saving ${extraction.fields.size} fields...", 0.9f
                )

                // Clear previous extraction on retry
                documentDao.deleteExtractedData(documentId)

                documentDao.insertExtractedData(
                    extraction.fields.map { field ->
                        ExtractedDataEntity(
                            id = field.id,
                            documentId = field.documentId,
                            fieldName = field.fieldName,
                            fieldValue = field.fieldValue,
                            fieldType = field.fieldType.name,
                            confidence = field.confidence,
                            pageNumber = field.pageNumber,
                            isConfirmed = false,
                        )
                    }
                )

                // Update document with detected type, language, and subject
                val doc = documentDao.getById(documentId)
                if (doc != null) {
                    documentDao.update(
                        doc.copy(
                            documentType = extraction.documentType?.name ?: doc.documentType,
                            language = extraction.language ?: doc.language,
                            title = if (extraction.subject != null && doc.title.startsWith("Scan"))
                                extraction.subject!! else doc.title,
                        )
                    )
                }

                // Step 6: Mark as extracted
                documentDao.updateStatus(documentId, DocumentStatus.EXTRACTED.name)

                // Log extraction event
                timelineRepository.recordEvent(
                    TimelineEvent(
                        id = UuidGenerator.generate(),
                        documentId = documentId,
                        eventType = TimelineEventType.ENTITIES_EXTRACTED,
                        title = "Extracted ${extraction.fields.size} field(s)",
                        description = extraction.fields.joinToString(", ") { it.fieldName },
                        createdAt = System.currentTimeMillis(),
                    )
                )

                _processingState.value = ProcessingState.Completed(documentId)
                PamResult.Success(extraction)
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Failed(documentId, e.message ?: "Unknown error")
                PamResult.Error(PamError.ExtractionFailed(detail = e.message ?: "Pipeline failed", cause = e))
            }
        }
}

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Running(
        val documentId: String,
        val message: String,
        val progress: Float,
    ) : ProcessingState
    data class Completed(val documentId: String) : ProcessingState
    data class Failed(val documentId: String, val error: String) : ProcessingState
}
