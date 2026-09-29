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
import com.postsaimanager.core.data.mapper.JsonColumns
import com.postsaimanager.core.data.worker.DocumentProcessingWorker
import com.postsaimanager.core.data.worker.ReprocessDocumentWorker
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.DocumentTitlePolicy
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.TimelineCodes
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

    override suspend fun enqueueReprocess(documentId: String) = withContext(ioDispatcher) {
        // KEEP: work already pending for this document (from an earlier start) is left alone.
        // Its own unique names, never `process-document-<id>`, so it cannot join, replace or be
        // replaced by a scan; the status is not touched, so the UI never shows it.
        ReprocessDocumentWorker.requests(documentId).forEach { (name, request) ->
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }
        Unit
    }

    override fun cancel(documentId: String) {
        workManager.cancelUniqueWork(DocumentProcessingWorker.workName(documentId))
        workManager.cancelUniqueWork(ReprocessDocumentWorker.chargingWorkName(documentId))
        workManager.cancelUniqueWork(ReprocessDocumentWorker.idleWorkName(documentId))
    }

    override suspend fun processDocument(
        documentId: String,
        reprocess: Boolean,
    ): PamResult<ExtractionResult> =
        processingMutex.withLock {
        withContext(ioDispatcher) {
            // A background reprocess is invisible: its progress goes to a state nobody observes, so
            // the UI shows no spinner and no notification for a letter that is already done.
            val state: MutableStateFlow<ProcessingState> =
                if (reprocess) MutableStateFlow(ProcessingState.Idle) else _processingState
            try {
                // A document trashed after this run was enqueued but before it started: stop
                // before anything is written. Not a failure — same treatment as a
                // cancellation below — because nothing went wrong, the document is just gone
                // from the user's point of view.
                if (documentDao.getById(documentId)?.deletedAt != null) {
                    return@withContext PamResult.Error(PamError.FileNotFound(path = documentId))
                }

                // Step 1: Mark as processing. Not for a reprocess: the document stays EXTRACTED
                // throughout, so a failure or a kill leaves it exactly as it was.
                state.value = ProcessingState.Running(documentId, ProcessingStage.READ, 0f)
                if (!reprocess) documentDao.updateStatus(documentId, DocumentStatus.PROCESSING.name)

                // Step 2: Get pages
                val pages = documentDao.getPages(documentId)
                if (pages.isEmpty()) {
                    val detail = "No pages found for document"
                    failDocument(documentId, REASON_NO_PAGES, detail, reprocess)
                    return@withContext PamResult.Error(PamError.OcrFailed(detail = detail))
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
                                state.value = ProcessingState.Running(
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

                // Log OCR event: a code and its numbers, rendered by the UI in the user's language.
                // The English title and description stay as the fallback for a reader that cannot
                // resolve the code (and are what older rows have).
                val ocrPercent = ocrResults.map { it.confidence }.takeIf { it.isNotEmpty() }
                    ?.average()?.let { Math.round(it * 100).toInt() }
                // A reprocess adds one event of its own at the end instead of repeating the scan's.
                if (!reprocess) {
                    timelineRepository.recordEvent(
                        TimelineEvent(
                            id = UuidGenerator.generate(),
                            documentId = documentId,
                            eventType = TimelineEventType.TEXT_EXTRACTED,
                            title = "Text extracted from ${pages.size} page(s)",
                            description = ocrPercent?.let { "Average confidence: $it%" },
                            createdAt = System.currentTimeMillis(),
                            code = TimelineCodes.OCR_DONE,
                            args = listOfNotNull(pages.size.toString(), ocrPercent?.toString()),
                        )
                    )
                }

                // Step 4: Entity extraction (with built-in language detection)
                state.value = ProcessingState.Running(
                    documentId = documentId, stage = ProcessingStage.UNDERSTAND, progress = 0.7f,
                )

                val combinedText = ocrResults.joinToString("\n\n") { it.fullText }

                // Read by the model when one is installed: it decides the type, what every
                // value means and who is who, and code verifies what it answered (see
                // AiExtractionUseCase). With no model, or an answer that cannot be read, the
                // result is only the values code found, marked "found" and low confidence,
                // with no guessed roles. The pattern extractor is the last resort, for a
                // document from which nothing at all could be read.
                val allBlocks = ocrResults.flatMap { it.blocks }
                val understanding = aiExtraction(
                    allBlocks,
                    // 5.4: lets AiExtractionUseCase turn a character-budget cut into a page
                    // estimate — `ocrResults` is already page-ordered, matching how
                    // `allBlocks` was concatenated above.
                    pageBlockCounts = ocrResults.map { it.blocks.size },
                )

                val read = (understanding as? PamResult.Success)?.data
                // Something was read: by the model, or (no model) only found by code.
                val usedV2 = read != null &&
                    (read.entities.isNotEmpty() || read.facts.isNotEmpty() || read.documentType.isNotBlank())
                // The model itself ran and its answer was used.
                val usedModel = usedV2 && read?.modelUsed == true

                // A background reprocess exists to read a letter better. Without the model it
                // could only replace an earlier reading with values merely found by code, so it
                // stops here with the old data untouched (recorded, retried once later).
                if (reprocess && !usedModel) {
                    failDocument(documentId, REASON_NO_MODEL, "The model did not read the document", reprocess)
                    return@withContext PamResult.Error(
                        PamError.ExtractionFailed(detail = "The model did not read the document"),
                    )
                }

                val extraction = if (usedV2 && read != null) {
                    val fields = UnderstandingToFields.invoke(
                        documentId = documentId,
                        understanding = read,
                        newId = { UuidGenerator.generate() },
                    )
                    Log.i(
                        TAG,
                        "understood $documentId model=$usedModel type=${read.documentType} " +
                            "entities=${read.entities.size} facts=${read.facts.size} fields=${fields.size}",
                    )
                    ExtractionResult(
                        documentId = documentId,
                        language = read.language.ifBlank { null },
                        // The model's own title (sender and purpose, in the letter's language),
                        // else the subject line it quoted.
                        subject = read.title.ifBlank { null }
                            ?: read.facts.firstOrNull { it.kind == FactKind.SUBJECT }?.value,
                        documentType = ExtractionSchema.DEFAULT.legacyType(read.documentType),
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
                val engineVersion = when {
                    usedModel -> AI_ENGINE_VERSION
                    usedV2 -> FOUND_VALUES_VERSION
                    else -> EXTRACTOR_VERSION
                }

                // Step 5: Merge the extraction into what is already stored.
                //
                // Merge, not replace. This step used to run
                // `DELETE FROM extracted_data` and re-insert, which destroyed every value
                // a user had corrected or added — silently, with no way to tell a machine
                // guess from a person's decision. MergeExtractionUseCase keeps user values,
                // honours deletions, and flags the cases where extraction now disagrees
                // instead of picking a winner.
                state.value = ProcessingState.Running(
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
                            code = TimelineCodes.REVIEW_FLAGGED,
                            args = listOf(merged.newlyFlagged.size.toString()) + merged.newlyFlaggedKeys,
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
                // Not on a background reprocess: profiles and their proposals ("is this the same
                // person?") are questions for the user, and an update nobody asked for must not
                // raise new ones. What the merge flags is all a reprocess surfaces.
                if (understanding is PamResult.Success && usedModel && !reprocess) {
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

                // Re-checked right before the first write of this run's results: trashing a
                // document cancels its work (DocumentRepositoryImpl.moveToTrash), but a run
                // already past that cancellation point would otherwise keep going and write
                // OCR text, fields and a status onto a document the user just deleted. This
                // is the race DocumentRepositoryImpl's moveToTrash KDoc points back to.
                val doc = documentDao.getById(documentId)
                if (doc?.deletedAt != null) {
                    state.value = ProcessingState.Completed(documentId)
                    return@withContext PamResult.Error(PamError.FileNotFound(path = documentId))
                }
                if (doc != null) {
                    // The model's title replaces a default title, or the title at the first model
                    // reading; never a person's, and never on a later reprocess (DocumentTitlePolicy).
                    // A real title clears the default's code.
                    val newTitle = extraction.subject?.takeIf {
                        usedV2 && DocumentTitlePolicy.modelTitleMayReplace(
                            isUserTitle = doc.isUserTitle,
                            titleCode = doc.titleCode,
                            modelHasRead = doc.extractionType != null,
                        )
                    }
                    documentDao.update(
                        doc.copy(
                            documentType = extraction.documentType?.name ?: doc.documentType,
                            language = extraction.language ?: doc.language,
                            title = newTitle ?: doc.title,
                            titleCode = if (newTitle != null) null else doc.titleCode,
                            titleArgs = if (newTitle != null) null else doc.titleArgs,
                            // Always overwritten with this run's own answer, null included —
                            // a reprocess that happens to read the whole document (a bigger
                            // context window, say) must clear a stale notice from an earlier
                            // truncated run, not leave it lingering.
                            extractionPagesRead = inputTruncation?.pagesRead,
                            extractionTotalPages = inputTruncation?.totalPages,
                            // What the model understood about the document as a whole. Only a run
                            // in which a model read the document replaces these: a run with no
                            // model must not wipe an earlier, real reading.
                            extractionType = if (usedModel) read?.documentType?.ifBlank { null } else doc.extractionType,
                            extractionTypeConfidence =
                                if (usedModel) read?.documentTypeConfidence else doc.extractionTypeConfidence,
                            summary = if (usedModel) read?.summary?.ifBlank { null } else doc.summary,
                            suggestedQuestions = if (usedModel) {
                                JsonColumns.encodeStrings(read?.suggestedQuestions.orEmpty().take(MAX_SUGGESTED_QUESTIONS))
                            } else {
                                doc.suggestedQuestions
                            },
                            extractorVersion = engineVersion,
                        )
                    )
                }

                // Step 6: Mark as extracted (a reprocess never left it, so nothing to write).
                if (!reprocess) documentDao.updateStatus(documentId, DocumentStatus.EXTRACTED.name)

                // Log extraction event. A reprocess records one quiet event of its own — a code and
                // the two versions, not English text — in place of the scan's "extracted N fields".
                timelineRepository.recordEvent(
                    if (reprocess) {
                        TimelineEvent(
                            id = UuidGenerator.generate(),
                            documentId = documentId,
                            eventType = TimelineEventType.ENTITIES_EXTRACTED,
                            title = "Read again with a newer version",
                            createdAt = System.currentTimeMillis(),
                            code = TimelineCodes.REPROCESSED,
                            args = listOf(doc?.extractorVersion.orEmpty(), engineVersion),
                        )
                    } else {
                        TimelineEvent(
                            id = UuidGenerator.generate(),
                            documentId = documentId,
                            eventType = TimelineEventType.ENTITIES_EXTRACTED,
                            title = "Extracted ${extraction.fields.size} field(s)",
                            description = extraction.fields.joinToString(", ") { it.fieldName },
                            createdAt = System.currentTimeMillis(),
                            code = TimelineCodes.FIELDS_EXTRACTED,
                            args = listOf(extraction.fields.size.toString()) +
                                extraction.fields.map { it.labelKey },
                        )
                    }
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
                state.value = ProcessingState.Running(
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

                state.value = ProcessingState.Completed(documentId)
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
                val detail = e.message ?: "Pipeline failed"
                failDocument(documentId, REASON_ERROR, detail, reprocess)
                PamResult.Error(PamError.ExtractionFailed(detail = detail, cause = e))
            }
        }
    }

    /**
     * Every non-cancellation exit of [processDocument] that isn't a success routes through
     * here: it is the one place that marks a document `FAILED`, so no early return can leave
     * one stuck at `PROCESSING` forever (the bug this method exists to close — a document with
     * no pages used to return an error without ever changing the row's status, and startup
     * recovery kept re-running it on every launch).
     *
     * Three things happen, and none of them can skip the other two just because it failed:
     * - [Log.w], so `adb logcat` shows *why* — the worker used to log only "Worker result
     *   FAILURE" with no reason attached.
     * - the status is [persisted][DocumentStatus.FAILED], since [_processingState] is one
     *   in-memory flow nothing restores after the app is killed.
     * - the reason is recorded on the timeline (`reasonCode` machine-readable for the detail
     *   screen to branch on, `detail` human-readable for its description), so the FAILED
     *   banner can say something more useful than "something went wrong".
     */
    private suspend fun failDocument(
        documentId: String,
        reasonCode: String,
        detail: String,
        reprocess: Boolean = false,
    ) {
        if (reprocess) {
            // A background re-read of a finished letter never marks it FAILED: it keeps its old
            // data and status. Only a quiet timeline row is left, and counted so a later start
            // retries once and then stops.
            Log.w(TAG, "reprocess failed for $documentId ($reasonCode): $detail")
            runCatching {
                timelineRepository.recordEvent(
                    TimelineEvent(
                        id = UuidGenerator.generate(),
                        documentId = documentId,
                        eventType = TimelineEventType.ENTITIES_EXTRACTED,
                        title = "Update skipped",
                        description = detail,
                        data = reasonCode,
                        createdAt = System.currentTimeMillis(),
                        code = TimelineCodes.REPROCESS_FAILED,
                        args = listOf(reasonCode),
                    )
                )
            }
            return
        }
        Log.w(TAG, "processing failed for $documentId ($reasonCode): $detail")
        _processingState.value = ProcessingState.Failed(documentId, detail)
        runCatching { documentDao.updateStatus(documentId, DocumentStatus.FAILED.name) }
        runCatching {
            timelineRepository.recordEvent(
                TimelineEvent(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    eventType = TimelineEventType.PROCESSING_FAILED,
                    title = "Processing failed",
                    description = detail,
                    data = reasonCode,
                    createdAt = System.currentTimeMillis(),
                    code = TimelineCodes.PROCESSING_FAILED,
                )
            )
        }
    }
}

/** Lenient: a stored layout that cannot be parsed must not fail a document. */
private val blockJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val TAG = "DocProcessing"

/**
 * [TimelineEvent.data] reason codes `failDocument` records — the detail screen's FAILED
 * banner branches on these, e.g. to offer Delete instead of Try again for [REASON_NO_PAGES].
 */
private const val REASON_NO_PAGES = "no_pages"
private const val REASON_ERROR = "error"
private const val REASON_NO_MODEL = "no_model"

/** The chat offers at most this many of the model's suggested questions. */
private const val MAX_SUGGESTED_QUESTIONS = 3

/** Pages OCR'd at once. Bounded so a ten-page scan doesn't decode ten bitmaps together. */
private const val OCR_CONCURRENCY = 2

// The versions a run stamps come from ExtractorVersion (core:domain) — the one place they are bumped,
// and the one the background reprocess compares against. Part of the Understand stage's fingerprint.
private const val EXTRACTOR_VERSION = ExtractorVersion.PATTERNS
private const val AI_ENGINE_VERSION = ExtractorVersion.CURRENT
private const val FOUND_VALUES_VERSION = ExtractorVersion.FOUND_VALUES

// The context window comes from ActiveModelProvider, which caps the catalogued value by
// what the device can actually afford. Passing a separate constant here would budget the
// prompt against one number while the KV cache was allocated for another.

// ProcessingState and ProcessingStage moved to :core:model (task 7.15.2) — the data layer
// must not decide what English a user reads for a progress message; see their doc comments.
