package com.postsaimanager.core.data.repository

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.data.worker.DocumentProcessingWorker
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
    private val aiExtraction: AiExtractionUseCase,
    private val entityProfileLinker: EntityProfileLinker,
    private val fieldRevisionDao: FieldRevisionDao,
    private val documentMapper: DocumentMapper,
    private val documentDao: DocumentDao,
    private val timelineRepository: TimelineRepository,
    @ApplicationContext private val appContext: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DocumentProcessor {
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    override val processingState: Flow<ProcessingState> = _processingState.asStateFlow()

    private val workManager get() = WorkManager.getInstance(appContext)

    /**
     * The on-device model is single-resident (documentation/07-document-pipeline.md §7): two
     * documents must never run the pipeline at once, or one engine load stomps the other's
     * session. WorkManager can start more than one worker concurrently, so the serialisation
     * has to live here rather than in unique-work naming, which only prevents *the same*
     * document from running twice.
     */
    private val processingMutex = Mutex()

    override suspend fun enqueue(documentId: String, force: Boolean) = withContext(ioDispatcher) {
        val current = documentDao.getById(documentId)?.status
        if (current != DocumentStatus.PROCESSING.name) {
            // Honest status before the work actually starts — see DocumentStatus's doc
            // comment on why this needs no migration.
            documentDao.updateStatus(documentId, DocumentStatus.QUEUED.name)
        }

        val request = OneTimeWorkRequestBuilder<DocumentProcessingWorker>()
            .setInputData(workDataOf(DocumentProcessingWorker.KEY_DOCUMENT_ID to documentId))
            .build()
        workManager.enqueueUniqueWork(
            DocumentProcessingWorker.workName(documentId),
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Unit
    }

    override fun cancel(documentId: String) {
        workManager.cancelUniqueWork(DocumentProcessingWorker.workName(documentId))
    }

    override suspend fun processDocument(documentId: String): PamResult<ExtractionResult> =
        processingMutex.withLock {
        withContext(ioDispatcher) {
            try {
                // Step 1: Mark as processing
                _processingState.value = ProcessingState.Running(documentId, ProcessingStage.READ, 0f)
                documentDao.updateStatus(documentId, DocumentStatus.PROCESSING.name)

                // Step 2: Get pages
                val pages = documentDao.getPages(documentId)
                if (pages.isEmpty()) {
                    return@withContext PamResult.Error(
                        PamError.OcrFailed(detail = "No pages found for document")
                    )
                }

                // Step 3: OCR pages concurrently, bounded to OCR_CONCURRENCY in flight at
                // once — enough to overlap ML Kit's per-page latency without decoding every
                // page's bitmap into memory at the same time. Page order in the result list
                // is preserved regardless of completion order: `combinedText` below reads
                // page 1 before page 2 no matter which one finished OCR first. Progress is
                // reported by completion count, which — with bounded, not ordered,
                // concurrency — does not always match page number.
                val ocrSemaphore = Semaphore(OCR_CONCURRENCY)
                val completedPages = AtomicInteger(0)

                val ocrByPage: List<Pair<DocumentPageEntity, OcrResult?>> = coroutineScope {
                    pages.map { page ->
                        async {
                            ocrSemaphore.withPermit {
                                val ocrResult = ocrService.recognizeText(page.imagePath).getOrNull()
                                val completed = completedPages.incrementAndGet()
                                _processingState.value = ProcessingState.Running(
                                    documentId = documentId,
                                    stage = ProcessingStage.READ,
                                    progress = completed.toFloat() / pages.size * 0.6f,
                                    currentPage = completed,
                                    totalPages = pages.size,
                                )
                                page to ocrResult
                            }
                        }
                    }.awaitAll()
                }

                val ocrResults = ocrByPage.mapNotNull { it.second }

                // One write for every page that produced text, instead of one DAO call per
                // page — turns an N-statement sequence into a single batched insert.
                val updatedPages = ocrByPage.mapNotNull { (page, ocrResult) ->
                    ocrResult?.let {
                        page.copy(
                            ocrText = it.fullText,
                            ocrConfidence = it.confidence,
                            // Positions kept, so a layout-aware extractor can use
                            // this page later without re-reading the image.
                            ocrBlocks = runCatching {
                                blockJson.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(
                                        com.postsaimanager.core.model.OcrBlock.serializer(),
                                    ),
                                    it.blocks,
                                )
                            }.getOrNull(),
                        )
                    }
                }
                if (updatedPages.isNotEmpty()) {
                    documentDao.insertPages(updatedPages)
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
                    documentId = documentId, stage = ProcessingStage.UNDERSTAND, progress = 0.7f,
                )

                val combinedText = ocrResults.joinToString("\n\n") { it.fullText }

                // Read by the model when one is installed, by patterns when not.
                //
                // The model is given the page layout — each block labelled with where it
                // sits — rather than flat text, which is what lets one prompt work across
                // sender formats. Patterns encode a single layout in a single language, and
                // on a clean German letter read the salutation "Frau" as the recipient's
                // name.
                //
                // Falling back rather than failing: a device with no model, too little
                // memory, or a model that returned something unusable still gets a document
                // with fields. Worse fields, not none.
                val allBlocks = ocrResults.flatMap { it.blocks }
                val understanding = aiExtraction(
                    allBlocks,
                    // 5.4: lets AiExtractionUseCase turn a character-budget cut into a page
                    // estimate — `ocrResults` is already page-ordered, matching how
                    // `allBlocks` was concatenated above.
                    pageBlockCounts = ocrResults.map { it.blocks.size },
                )

                val usedModel = understanding is PamResult.Success &&
                    understanding.data.entities.isNotEmpty()

                val extraction = if (understanding is PamResult.Success && usedModel) {
                    val fields = UnderstandingToFields.invoke(
                        documentId = documentId,
                        understanding = understanding.data,
                        newId = { UuidGenerator.generate() },
                    )
                    Log.i(
                        TAG,
                        "understood $documentId entities=${understanding.data.entities.size} " +
                            "facts=${understanding.data.facts.size} fields=${fields.size}",
                    )
                    ExtractionResult(
                        documentId = documentId,
                        language = understanding.data.language.ifBlank { null },
                        subject = understanding.data.subject.ifBlank { null },
                        documentType = null,
                        fields = fields,
                    )
                } else {
                    if (understanding is PamResult.Error) {
                        Log.i(TAG, "model unavailable, using patterns: " +
                            understanding.error.userMessage)
                    }
                    entityExtractor.extract(documentId, combinedText, null)
                }

                // Part of the Understand stage's fingerprint. Switching between the two
                // re-derives machine values without re-reading a page — and without
                // touching anything the user decided.
                val engineVersion = if (usedModel) AI_ENGINE_VERSION else EXTRACTOR_VERSION

                // Step 5: Merge the extraction into what is already stored.
                //
                // Merge, not replace. This step used to run
                // `DELETE FROM extracted_data` and re-insert, which destroyed every value
                // a user had corrected or added — silently, with no way to tell a machine
                // guess from a person's decision. MergeExtractionUseCase keeps user values,
                // honours deletions, and flags the cases where extraction now disagrees
                // instead of picking a winner.
                _processingState.value = ProcessingState.Running(
                    documentId = documentId,
                    stage = ProcessingStage.UNDERSTAND,
                    progress = 0.9f,
                    fieldCount = extraction.fields.size,
                )

                val stored = documentDao.getExtractedData(documentId)
                    .map(documentMapper::extractedDataToDomain)
                val now = System.currentTimeMillis()

                val merged = mergeExtraction(
                    existing = stored,
                    extracted = extraction.fields,
                    engineVersion = engineVersion,
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

                // Step 5b: Turn what the model recognised into profiles and links.
                //
                // Only possible when the model ran — the pattern extractor never produces
                // RecognisedEntity values, so understanding.data.entities would be empty
                // anyway. Wrapped the same way indexing is: a document the user scanned is
                // complete without this, so a failure here is logged and the document
                // proceeds exactly as if no entities had been found.
                if (understanding is PamResult.Success && usedModel) {
                    runCatching {
                        entityProfileLinker.process(documentId, understanding.data)
                    }.onSuccess { outcome ->
                        Log.i(
                            TAG,
                            "profiles for $documentId linked=${outcome.linked} " +
                                "created=${outcome.created} proposals=${outcome.proposals.size} " +
                                "dismissed=${outcome.ignoredAsDismissed}",
                        )
                    }.onFailure { e ->
                        // A cancellation (REPLACE, delete-cancel, system stop) must propagate
                        // to the outer scope like any other cancellation — swallowing it here
                        // via runCatching would let the pipeline carry on as if entity linking
                        // had merely failed, instead of the whole run being torn down. See the
                        // outer catch block's doc on the same rule.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "entity linking failed for $documentId: ${e.message}")
                    }
                }

                // 5.4: whether this run had to cut the document's layout to fit the
                // extraction budget — only meaningful when the model actually ran; the
                // pattern fallback never truncates an input, it just reads flat text.
                val inputTruncation = (understanding as? PamResult.Success)
                    ?.takeIf { usedModel }
                    ?.data
                    ?.inputTruncation

                // Update document with detected type, language, and subject
                val doc = documentDao.getById(documentId)
                if (doc != null) {
                    documentDao.update(
                        doc.copy(
                            documentType = extraction.documentType?.name ?: doc.documentType,
                            language = extraction.language ?: doc.language,
                            title = if (extraction.subject != null && doc.title.startsWith("Scan"))
                                extraction.subject!! else doc.title,
                            // Always overwritten with this run's own answer, null included —
                            // a reprocess that happens to read the whole document (a bigger
                            // context window, say) must clear a stale notice from an earlier
                            // truncated run, not leave it lingering.
                            extractionPagesRead = inputTruncation?.pagesRead,
                            extractionTotalPages = inputTruncation?.totalPages,
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
                    documentId = documentId, stage = ProcessingStage.INDEX, progress = 0.95f,
                )
                // Per page, not the joined `combinedText` — so a chunk never straddles a
                // page break and can always be cited as `[p.N]` (4.0). `ocrByPage` already
                // preserves page order regardless of OCR completion order (see its own
                // comment above).
                val pageTexts = ocrByPage.mapNotNull { (page, ocrResult) ->
                    ocrResult?.let { IndexDocumentUseCase.PageText(page.pageNumber, it.fullText) }
                }
                when (val indexed = indexDocument(documentId, pageTexts)) {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancellation is not a failure — WorkManager REPLACE (Reprocess),
                // deleting the document mid-run, or the system stopping the worker all
                // cancel this coroutine. `CancellationException` is a subclass of
                // `Exception`, so it used to be caught by the `catch (e: Exception)` below
                // and the document was marked FAILED — wrongly, since nothing actually went
                // wrong, and a REPLACE'd run racing its own successor to write FAILED last
                // could stomp a status the new run had already moved past. Rethrow and leave
                // the document's status exactly as it was: the next enqueue (recovery on app
                // start, or whoever cancelled it) decides what happens next, not this run.
                throw e
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Failed(documentId, e.message ?: "Unknown error")
                // Persisted, not just transient: processingState is one in-memory flow that
                // nothing restores after the app is killed, but a failed document must still
                // read as failed — with a retry — the next time anyone opens it.
                runCatching { documentDao.updateStatus(documentId, DocumentStatus.FAILED.name) }
                PamResult.Error(PamError.ExtractionFailed(detail = e.message ?: "Pipeline failed", cause = e))
            }
        }
    }
}

/** Lenient: a stored layout that cannot be parsed must not fail a document. */
private val blockJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val TAG = "DocProcessing"

/** Pages OCR'd at once. Bounded so a ten-page scan doesn't decode ten bitmaps together. */
private const val OCR_CONCURRENCY = 2

/**
 * Identifies the extractor that produced a value.
 *
 * Part of the Understand stage's fingerprint: bump it when extraction logic changes, and
 * every document re-derives its machine values on next run without re-reading a single page.
 */
private const val EXTRACTOR_VERSION = "entity-extractor-1"

/** Bump when the prompt, the grammar or the field mapping changes. */
private const val AI_ENGINE_VERSION = "ai-understanding-1"

// The context window comes from ActiveModelProvider, which caps the catalogued value by
// what the device can actually afford. Passing a separate constant here would budget the
// prompt against one number while the KV cache was allocated for another.

// ProcessingState and ProcessingStage moved to :core:model (task 7.15.2) — the data layer
// must not decide what English a user reads for a progress message; see their doc comments.
