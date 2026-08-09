package com.postsaimanager.core.data.repository

import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
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
 * 7. Index the text for search — chunk, embed, store
 * 8. Log timeline events
 */
@Singleton
class DocumentProcessingPipeline @Inject constructor(
    private val ocrService: OcrService,
    private val entityExtractor: EntityExtractor,
    private val indexDocument: IndexDocumentUseCase,
    private val mergeExtraction: MergeExtractionUseCase,
    private val fieldRevisionDao: FieldRevisionDao,
    private val documentMapper: DocumentMapper,
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
                                    // Positions kept, so a layout-aware extractor can use
                                    // this page later without re-reading the image.
                                    ocrBlocks = runCatching {
                                        blockJson.encodeToString(
                                            kotlinx.serialization.builtins.ListSerializer(
                                                com.postsaimanager.core.model.OcrBlock.serializer(),
                                            ),
                                            ocrResult.blocks,
                                        )
                                    }.getOrNull(),
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

                // Step 5: Merge the extraction into what is already stored.
                //
                // Merge, not replace. This step used to run
                // `DELETE FROM extracted_data` and re-insert, which destroyed every value
                // a user had corrected or added — silently, with no way to tell a machine
                // guess from a person's decision. MergeExtractionUseCase keeps user values,
                // honours deletions, and flags the cases where extraction now disagrees
                // instead of picking a winner.
                _processingState.value = ProcessingState.Running(
                    documentId, "Saving ${extraction.fields.size} fields...", 0.9f
                )

                val stored = documentDao.getExtractedData(documentId)
                    .map(documentMapper::extractedDataToDomain)
                val now = System.currentTimeMillis()

                val merged = mergeExtraction(
                    existing = stored,
                    extracted = extraction.fields,
                    engineVersion = EXTRACTOR_VERSION,
                    now = now,
                    newId = { UuidGenerator.generate() },
                )

                merged.idsToDelete.forEach { documentDao.deleteExtractedField(it) }
                documentDao.insertExtractedData(
                    merged.toPersist.map(documentMapper::extractedDataToEntity),
                )
                fieldRevisionDao.insertAll(
                    merged.revisions.map(documentMapper::revisionToEntity),
                )

                if (merged.newlyFlagged.isNotEmpty()) {
                    // Worth a timeline entry: the document says something different from
                    // what the user recorded, and they may never open the field itself.
                    timelineRepository.recordEvent(
                        TimelineEvent(
                            id = UuidGenerator.generate(),
                            documentId = documentId,
                            eventType = TimelineEventType.ENTITIES_EXTRACTED,
                            title = "${merged.newlyFlagged.size} field(s) need your review",
                            description = "A new reading differs from your version: " +
                                merged.newlyFlagged.joinToString(", "),
                            createdAt = now,
                        )
                    )
                }

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

                // Step 7: Make it searchable.
                //
                // Deliberately after the document is already marked EXTRACTED and its
                // timeline written. Indexing is an enhancement to a document that is
                // otherwise complete, so a failure here must cost the user nothing —
                // the scan, the OCR text and the extracted fields all stand without it.
                // IndexDocumentUseCase degrades internally too: with no embedding model
                // installed it stores the chunks as text, and keyword search still finds
                // them.
                _processingState.value = ProcessingState.Running(
                    documentId, "Indexing for search...", 0.95f
                )
                when (val indexed = indexDocument(documentId, combinedText)) {
                    is PamResult.Success -> Log.i(
                        TAG,
                        "indexed $documentId chunks=${indexed.data.chunkCount} " +
                            "embedded=${indexed.data.embedded}",
                    )
                    // Swallowed on purpose — see above. Search will be missing this
                    // document until it is re-processed.
                    is PamResult.Error -> Log.w(
                        TAG,
                        "indexing failed for $documentId: ${indexed.error.userMessage}",
                    )
                }

                _processingState.value = ProcessingState.Completed(documentId)
                PamResult.Success(extraction)
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Failed(documentId, e.message ?: "Unknown error")
                PamResult.Error(PamError.ExtractionFailed(detail = e.message ?: "Pipeline failed", cause = e))
            }
        }
}

/** Lenient: a stored layout that cannot be parsed must not fail a document. */
private val blockJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val TAG = "DocProcessing"

/**
 * Identifies the extractor that produced a value.
 *
 * Part of the Understand stage's fingerprint: bump it when extraction logic changes, and
 * every document re-derives its machine values on next run without re-reading a single page.
 */
private const val EXTRACTOR_VERSION = "entity-extractor-1"

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
