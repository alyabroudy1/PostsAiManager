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
import com.postsaimanager.core.data.worker.DocumentEnrichmentWorker
import com.postsaimanager.core.data.worker.DocumentProcessingWorker
import com.postsaimanager.core.data.worker.ReprocessDocumentWorker
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.EnrichmentRetryPolicy
import com.postsaimanager.core.domain.document.EnrichmentTicketRebuilder
import com.postsaimanager.core.domain.document.KeySlotMarker
import com.postsaimanager.core.domain.document.ReprocessOverwritePolicy
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.usecase.AiExtractionUseCase
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FamilySource
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

    /**
     * Readings whose second stage (language, extras, title, summary) is still to be written, by document. Kept here so a second
     * stage that a new scan pushed aside can be scheduled again, and so a screen can say "Summary coming…" meanwhile.
     */
    private val pendingEnrichment = java.util.concurrent.ConcurrentHashMap<String, EnrichmentTicket>()

    /** Documents whose second stage is queued without a ticket (it is rebuilt when the work runs); see [enqueueEnrichment]. */
    private val pendingRebuild: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val _enriching = MutableStateFlow<Set<String>>(emptySet())
    override val enrichingDocuments: Flow<Set<String>> = _enriching.asStateFlow()

    override suspend fun enqueue(documentId: String, force: Boolean, forcedFamily: String?) = withContext(ioDispatcher) {
        // A new scan never waits for a summary: second stages step aside (cancelled, their tickets kept) and come back
        // once the scan's first stage is stored (see [resumeEnrichment]).
        workManager.cancelAllWorkByTag(DocumentEnrichmentWorker.TAG)
        val current = documentDao.getById(documentId)?.status
        if (current != DocumentStatus.PROCESSING.name) {
            // Honest status before the work actually starts — see DocumentStatus's doc
            // comment on why this needs no migration.
            documentDao.updateStatus(documentId, DocumentStatus.QUEUED.name)
        }

        val request = OneTimeWorkRequestBuilder<DocumentProcessingWorker>()
            .setInputData(
                workDataOf(
                    DocumentProcessingWorker.KEY_DOCUMENT_ID to documentId,
                    DocumentProcessingWorker.KEY_FORCED_FAMILY to (forcedFamily ?: ""),
                ),
            )
            .build()
        workManager.enqueueUniqueWork(
            DocumentProcessingWorker.workName(documentId),
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Unit
    }

    override suspend fun enqueueReprocess(documentId: String, urgent: Boolean) = withContext(ioDispatcher) {
        // KEEP: work already pending for this document (from an earlier start) is left alone.
        // Its own unique names, never `process-document-<id>`, so it cannot join, replace or be
        // replaced by a scan; the status is not touched, so the UI never shows it.
        val requests = if (urgent) listOf(ReprocessDocumentWorker.urgentRequest(documentId)) else ReprocessDocumentWorker.requests(documentId)
        requests.forEach { (name, request) ->
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }
        Unit
    }

    override fun cancel(documentId: String) {
        finishEnrichment(documentId)
        workManager.cancelUniqueWork(DocumentEnrichmentWorker.workName(documentId))
        workManager.cancelUniqueWork(DocumentProcessingWorker.workName(documentId))
        workManager.cancelUniqueWork(ReprocessDocumentWorker.chargingWorkName(documentId))
        workManager.cancelUniqueWork(ReprocessDocumentWorker.idleWorkName(documentId))
        workManager.cancelUniqueWork(ReprocessDocumentWorker.urgentWorkName(documentId))
    }

    override suspend fun processDocument(
        documentId: String,
        reprocess: Boolean,
        forcedFamily: String?,
    ): PamResult<ExtractionResult> =
        processingMutex.withLock {
        withContext(ioDispatcher) {
            // A background reprocess is invisible: its progress goes to a state nobody observes, so
            // the UI shows no spinner and no notification for a letter that is already done.
            val state: MutableStateFlow<ProcessingState> =
                if (reprocess) MutableStateFlow(ProcessingState.Idle) else _processingState
            val runStarted = System.nanoTime()
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
                val before = documentDao.getById(documentId)
                // A family a person chose is read as that one, now and on every later re-read ("Read again as ..." names it; a family set
                // earlier by the person is the document's own). An id the schema does not know is not a family.
                val chosenFamily = (
                    forcedFamily ?: before?.extractionType?.takeIf { FamilySource.parse(before.familySource) == FamilySource.USER }
                    )?.takeIf { ExtractionSchema.DEFAULT.family(it) != null }
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
                //
                // A reprocess does not read the images again when every page already has its
                // OCR stored (StoredOcr): faster, and the candidate ids stay the same.
                val ocrSemaphore = Semaphore(OCR_CONCURRENCY)
                val completedPages = AtomicInteger(0)
                val ocrStarted = System.nanoTime()

                val storedOcr = if (reprocess) StoredOcr.reuse(pages, documentMapper) else null
                val ocrByPage: List<Pair<DocumentPageEntity, OcrResult?>> = storedOcr ?: coroutineScope {
                    pages.map { page ->
                        async {
                            ocrSemaphore.withPermit {
                                val pageStarted = System.nanoTime()
                                val ocrResult = ocrService.recognizeText(page.imagePath).getOrNull()
                                Log.i(
                                    TIMING_TAG,
                                    "$documentId ocr page ${page.pageNumber} ms=${msSince(pageStarted)} blocks=${ocrResult?.blocks?.size ?: -1}",
                                )
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
                Log.i(TIMING_TAG, "$documentId ocr all pages=${pages.size} ms=${msSince(ocrStarted)} storedOcr=${storedOcr != null}")

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
                // Nothing to write back when the stored OCR was reused as it is.
                if (storedOcr == null && updatedPages.isNotEmpty()) {
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

                // Read by the model when one is installed: it decides the type, what every
                // value means and who is who, and code verifies what it answered (see
                // AiExtractionUseCase). With no model, or an answer that cannot be read, the
                // result is only the values code found, marked "found" and low confidence,
                // with no guessed roles (ExtractionV2Adapter is their single owner).
                val allBlocks = ocrResults.flatMap { it.blocks }
                val understanding = aiExtraction(
                    allBlocks,
                    // 5.4: lets AiExtractionUseCase turn a character-budget cut into a page
                    // estimate — `ocrResults` is already page-ordered, matching how
                    // `allBlocks` was concatenated above.
                    pageBlockCounts = ocrResults.map { it.blocks.size },
                    // Page 1's image shape, as the benchmark always gave it, for the layout template match.
                    pageAspect = pages.minByOrNull { it.pageNumber }
                        ?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height },
                    traceContent = traceContentFor(documentId),
                    // What a person needs to see first: the type, the parties, the amounts and dates. The language, the extras and the
                    // free text follow as the second stage, in the background, once this is stored.
                    stages = ExtractionV2Pipeline.Stages.FIRST,
                    forcedFamily = chosenFamily,
                )

                val read = (understanding as? PamResult.Success)?.data
                read?.let { logReadingTrace(documentId, it.readingTrace) }
                // The first stage of a staged reading: no extras, no subject, no summary yet (the second stage writes them).
                val staged = read?.enrichment != null
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
                        // The composed title as plain text (the family, the sender, the subject line it quoted).
                        subject = read.title.ifBlank { null }
                            ?: read.facts.firstOrNull { it.kind == FactKind.SUBJECT }?.value,
                        documentType = ExtractionSchema.DEFAULT.legacyType(read.documentType),
                        fields = fields,
                    )
                } else {
                    // Nothing could be read (no layout to read from): no value is invented.
                    if (understanding is PamResult.Error) {
                        Log.i(TAG, "nothing read for $documentId: " + understanding.error.userMessage)
                    }
                    ExtractionResult(documentId = documentId, language = null, fields = emptyList())
                }

                // Part of the Understand stage's fingerprint. Switching between the two
                // re-derives machine values without re-reading a page — and without
                // touching anything the user decided. A run the model did not read (values
                // found by code, or nothing) is stamped as such.
                val engineVersion = if (usedModel) AI_ENGINE_VERSION else FOUND_VALUES_VERSION

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

                val storeStarted = System.nanoTime()
                val stored = documentDao.getExtractedData(documentId)
                    .map(documentMapper::extractedDataToDomain)
                val now = System.currentTimeMillis()

                val merged = mergeExtraction(
                    // A staged reading's first stage produces no extras and no subject: the ones already stored (an earlier
                    // reading's) wait for the second stage to replace them instead of being deleted now.
                    // A stored row of the second stage's kind that shares a name with a fresh row is offered too: the insert replaces on
                    // (document, field name), so an unpaired fresh row would silently delete it, a person's reviewed value included.
                    existing = if (staged) {
                        val freshNames = extraction.fields.map { it.fieldName }.toSet()
                        stored.filter { !UnderstandingToFields.writtenInSecondStage(it) || it.fieldName in freshNames }
                    } else {
                        stored
                    },
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
                val linkStarted = System.nanoTime()
                if (understanding is PamResult.Success && usedModel && !reprocess) {
                    runCatching {
                        // Organisations are linked and created automatically; the pipeline raises no "is this you?" questions.
                        entityProfileLinker.process(documentId, understanding.data)
                    }.onSuccess { outcome ->
                        Log.i(TIMING_TAG, "$documentId profile linking ms=${msSince(linkStarted)}")
                        Log.i(
                            TAG,
                            "profiles for $documentId linked=${outcome.linked} " +
                                "created=${outcome.created} " +
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
                    // The family, the topics, the title and the summary are written through ReprocessOverwritePolicy: a family a person
                    // chose, a title a person set (or real words an older reading wrote) and a summary a person wrote are never replaced.
                    // Only a run in which a model read the document replaces what the model understood: a run with no model must not wipe
                    // an earlier, real reading. (A staged reading's first stage has no summary or questions yet: an earlier reading's stay
                    // until the second stage.)
                    var updated = documentMapper.toDomain(doc)
                    if (usedModel && read != null) {
                        updated = ReprocessOverwritePolicy.applyFamily(updated, read, forcedFamily?.takeIf { ExtractionSchema.DEFAULT.family(it) != null })
                        updated = ReprocessOverwritePolicy.applyTitle(updated, read)
                        if (!staged) updated = ReprocessOverwritePolicy.applySummary(updated, read)
                    }
                    documentDao.update(
                        documentMapper.toEntity(
                            updated.copy(
                                documentType = ExtractionSchema.DEFAULT.legacyType(updated.extractionType) ?: updated.documentType,
                                language = extraction.language ?: updated.language,
                                // Always overwritten with this run's own answer, null included —
                                // a reprocess that happens to read the whole document (a bigger
                                // context window, say) must clear a stale notice from an earlier
                                // truncated run, not leave it lingering.
                                extractionPagesRead = inputTruncation?.pagesRead,
                                extractionTotalPages = inputTruncation?.totalPages,
                                suggestedQuestions = if (usedModel && !staged) read?.suggestedQuestions.orEmpty().take(MAX_SUGGESTED_QUESTIONS) else updated.suggestedQuestions,
                                extractorVersion = engineVersion,
                                // A new reading starts the second stage's attempts again.
                                enrichmentAttempts = 0,
                                // The second stage is owed from the moment the first is stored (recovery keys on it).
                                enrichmentPending = read?.enrichment != null,
                            ),
                        ).copy(syncStatus = doc.syncStatus),
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

                // The result is stored and (for a scan) shown as EXTRACTED: now the second stage, in the background.
                read?.enrichment?.let { scheduleEnrichment(documentId, it) }

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
                Log.i(TIMING_TAG, "$documentId storage (merge, fields, profile linking, title, timeline) ms=${msSince(storeStarted)}")
                val indexStarted = System.nanoTime()
                val pageTexts = ocrByPage.mapNotNull { (page, ocrResult) ->
                    ocrResult?.let { IndexDocumentUseCase.PageText(page.pageNumber, it.fullText) }
                }
                val indexResult = indexDocument(documentId, pageTexts)
                Log.i(TIMING_TAG, "$documentId index ms=${msSince(indexStarted)}")
                when (val indexed = indexResult) {
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

                Log.i(TIMING_TAG, "$documentId TOTAL processDocument (inside the lock, reprocess=$reprocess) ms=${msSince(runStarted)}")
                state.value = ProcessingState.Completed(documentId)
                // Second stages a scan pushed aside come back now, unless another scan is waiting.
                resumeEnrichment()
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

    // ── the reading's second stage ──

    /** Queues [ticket]'s second stage for [documentId] (replacing one queued earlier for the same document). */
    private fun scheduleEnrichment(documentId: String, ticket: EnrichmentTicket) {
        pendingRebuild.remove(documentId)
        pendingEnrichment[documentId] = ticket
        publishEnriching()
        workManager.enqueueUniqueWork(
            DocumentEnrichmentWorker.workName(documentId), ExistingWorkPolicy.REPLACE, DocumentEnrichmentWorker.request(documentId, ticket),
        )
    }

    /**
     * The second stage of a document whose ticket was lost (the process died after its first stage was stored, before the second was
     * queued, or while a scan had pushed it aside): queued without a ticket, which [enrichDocument] rebuilds from what is stored.
     * `KEEP`: a second stage that is genuinely queued is left alone.
     */
    override suspend fun enqueueEnrichment(documentId: String) = withContext(ioDispatcher) {
        if (!pendingEnrichment.containsKey(documentId)) pendingRebuild += documentId
        publishEnriching()
        workManager.enqueueUniqueWork(
            DocumentEnrichmentWorker.workName(documentId), ExistingWorkPolicy.KEEP, DocumentEnrichmentWorker.request(documentId, null),
        )
        Unit
    }

    private fun publishEnriching() {
        _enriching.value = pendingEnrichment.keys.toSet() + pendingRebuild
    }

    /** Puts back every second stage that a new scan pushed aside (work that is still queued is left as it is), unless a scan is waiting. */
    private suspend fun resumeEnrichment() {
        if (pendingEnrichment.isEmpty() && pendingRebuild.isEmpty()) return
        val scansWaiting = documentDao.getByStatus(DocumentStatus.QUEUED.name).isNotEmpty() ||
            documentDao.getByStatus(DocumentStatus.PROCESSING.name).isNotEmpty()
        if (scansWaiting) return
        pendingEnrichment.forEach { (documentId, ticket) ->
            workManager.enqueueUniqueWork(
                DocumentEnrichmentWorker.workName(documentId), ExistingWorkPolicy.KEEP, DocumentEnrichmentWorker.request(documentId, ticket),
            )
        }
        pendingRebuild.forEach { documentId ->
            workManager.enqueueUniqueWork(
                DocumentEnrichmentWorker.workName(documentId), ExistingWorkPolicy.KEEP, DocumentEnrichmentWorker.request(documentId, null),
            )
        }
    }

    /** The second stage of [documentId] is over (written, failed or no longer wanted): nothing is coming any more. */
    private fun finishEnrichment(documentId: String) {
        pendingEnrichment.remove(documentId)
        pendingRebuild.remove(documentId)
        publishEnriching()
    }

    override suspend fun enrichDocument(documentId: String, ticket: EnrichmentTicket?): PamResult<Unit> =
        processingMutex.withLock {
            withContext(ioDispatcher) {
                val started = System.nanoTime()
                try {
                    val doc = documentDao.getById(documentId)
                    if (doc == null || doc.deletedAt != null) {
                        finishEnrichment(documentId)
                        return@withContext PamResult.Error(PamError.FileNotFound(path = documentId))
                    }
                    // A ticket that was lost is rebuilt from the stored family, topics and fields; a document no model has read has nothing to enrich.
                    val storedFields = documentDao.getExtractedData(documentId).map(documentMapper::extractedDataToDomain)
                    val usedTicket = ticket ?: run {
                        if (doc.extractionType == null) {
                            finishEnrichment(documentId)
                            return@withContext PamResult.Error(PamError.ExtractionFailed(detail = "No reading to complete"))
                        }
                        EnrichmentTicketRebuilder.rebuild(documentMapper.toDomain(doc), storedFields)
                    }
                    val pages = documentDao.getPages(documentId)
                    // The same blocks the first stage read, so the candidate ids are its ids; a document without stored text cannot be
                    // read a second time (it was read by an older version: the next re-read starts from the images).
                    val ocrResults = StoredOcr.reuse(pages, documentMapper)?.mapNotNull { it.second }.orEmpty()
                    if (ocrResults.isEmpty()) {
                        settleFailedAttempt(documentId)
                        finishEnrichment(documentId)
                        return@withContext PamResult.Error(PamError.OcrFailed(detail = "No stored text to read again"))
                    }
                    val understanding = aiExtraction(
                        ocrResults.flatMap { it.blocks },
                        pageBlockCounts = ocrResults.map { it.blocks.size },
                        pageAspect = pages.minByOrNull { it.pageNumber }
                            ?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height },
                        stages = ExtractionV2Pipeline.Stages.SECOND,
                        // The slot values as stored now (a person's correction included) are what the stage scores for key information.
                        ticket = usedTicket.copy(slots = EnrichmentTicketRebuilder.slotsOf(storedFields)),
                    )
                    val read = (understanding as? PamResult.Success)?.data
                    read?.let { logReadingTrace(documentId, it.readingTrace) }
                    if (read == null || !read.modelUsed) {
                        settleFailedAttempt(documentId)
                        finishEnrichment(documentId)
                        return@withContext PamResult.Error(PamError.ExtractionFailed(detail = "The model did not write the second stage"))
                    }

                    // What this stage wrote: the extras and the subject line (the parties and slots are the first stage's, untouched).
                    val fields = UnderstandingToFields.invoke(documentId, read.copy(entities = emptyList()), newId = { UuidGenerator.generate() })
                        .filter(UnderstandingToFields::writtenInSecondStage)
                    val now = System.currentTimeMillis()
                    // The merge keeps every value a person wrote or confirmed and every deletion, flagging a differing reading
                    // instead of applying it; only rows this stage owns are offered to it, so nothing of the first stage can be dropped.
                    // A stored row of any kind that shares a name with a fresh row is offered as well: the insert replaces on
                    // (document, field name), so an unpaired fresh row would delete it, a pre-v2 confirmed "Subject" included.
                    val freshNames = fields.map { it.fieldName }.toSet()
                    val merged = mergeExtraction(
                        existing = storedFields.filter { UnderstandingToFields.writtenInSecondStage(it) || it.fieldName in freshNames },
                        extracted = fields, engineVersion = AI_ENGINE_VERSION, now = now, newId = { UuidGenerator.generate() },
                    )
                    merged.idsToDelete.forEach { documentDao.deleteExtractedField(it) }
                    documentDao.insertExtractedData(merged.toPersist.map(documentMapper::extractedDataToEntity))
                    fieldRevisionDao.insertAll(merged.revisions.map(documentMapper::revisionToEntity))
                    // The stored slot rows the stage picked as key information carry the score (the flag lives on the row, so it survives storage).
                    val mergedIds = merged.toPersist.map { it.id }.toSet() + merged.idsToDelete
                    KeySlotMarker.mark(storedFields.filter { it.id !in mergedIds }, read.keySlots).takeIf { it.isNotEmpty() }
                        ?.let { documentDao.insertExtractedData(it.map(documentMapper::extractedDataToEntity)) }

                    // Re-read right before the write: the document may have been trashed or edited while the model was writing.
                    val latest = documentDao.getById(documentId)
                    if (latest == null || latest.deletedAt != null) {
                        finishEnrichment(documentId)
                        return@withContext PamResult.Error(PamError.FileNotFound(path = documentId))
                    }
                    // The composed title, the summary and (for a profile that scores them here) the topics go through ReprocessOverwritePolicy:
                    // never a person's title, family or summary, never real words an older reading wrote.
                    var updated = documentMapper.toDomain(latest)
                    updated = ReprocessOverwritePolicy.applyTitle(updated, read)
                    updated = ReprocessOverwritePolicy.applySummary(updated, read)
                    updated = ReprocessOverwritePolicy.applyActions(updated, read)
                    updated = ReprocessOverwritePolicy.applyLateTopics(updated, read)
                    documentDao.update(
                        documentMapper.toEntity(
                            updated.copy(
                                language = read.language.ifBlank { null } ?: updated.language,
                                suggestedQuestions = read.suggestedQuestions.take(MAX_SUGGESTED_QUESTIONS).ifEmpty { updated.suggestedQuestions },
                                // A summary was settled: nothing is owed. Without one, settleFailedAttempt below counts the attempt.
                                enrichmentPending = updated.enrichmentPending && read.summarySource == null,
                            ),
                        ).copy(syncStatus = latest.syncStatus),
                    )
                    if (read.summarySource == null) {
                        Log.w(TAG, "second stage of $documentId wrote no summary; attempt counted")
                        settleFailedAttempt(documentId)
                    }
                    Log.i(TIMING_TAG, "$documentId TOTAL enrichDocument (inside the lock) ms=${msSince(started)} extras=${fields.size}")
                    finishEnrichment(documentId)
                    PamResult.Success(Unit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Pushed aside by a scan or stopped by the system: the ticket stays, the second stage comes back later.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "second stage failed for $documentId: ${e.message}")
                    runCatching { settleFailedAttempt(documentId) }
                    finishEnrichment(documentId)
                    PamResult.Error(PamError.ExtractionFailed(detail = e.message ?: "Second stage failed", cause = e))
                }
            }
        }

    /**
     * A second stage ended without settling a summary: counts the attempt on the document and, at [EnrichmentRetryPolicy.MAX_ATTEMPTS],
     * stores the template summary of its verified fields, so recovery on app start stops queueing it.
     */
    private suspend fun settleFailedAttempt(documentId: String) {
        val latest = documentDao.getById(documentId)?.takeIf { it.deletedAt == null } ?: return
        val fields = documentDao.getExtractedData(documentId).map(documentMapper::extractedDataToDomain)
        val updated = EnrichmentRetryPolicy.afterFailure(documentMapper.toDomain(latest), fields)
        documentDao.update(documentMapper.toEntity(updated).copy(syncStatus = latest.syncStatus))
    }

    /**
     * The reading's structure in logcat: the chosen model, profile and interpreter always (an unknown model
     * is a warning), the rest (template, zones, candidate counts, ids and scores) only in a debuggable build.
     * Structure only: [DocumentUnderstanding.readingTrace] never holds a word of the letter.
     */
    private fun logReadingTrace(documentId: String, trace: List<String>) {
        val header = trace.firstOrNull() ?: return
        if (header.contains("profile=UNKNOWN")) Log.w(TAG, "reading $documentId: $header (no profile, fallback strategy)") else Log.i(TAG, "reading $documentId: $header")
        val rest = trace.drop(1)
        // Timings (`t ...`: stage, counts, milliseconds) always; the structure and any content only in a debuggable build.
        rest.filter { it.startsWith("t ") }.forEach { Log.i(TIMING_TAG, "$documentId ${it.removePrefix("t ")}") }
        if (isDebuggable()) rest.filter { !it.startsWith("t ") }.forEach { Log.i(TAG, "reading $documentId: $it") }
    }

    private fun msSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000L

    private fun isDebuggable() = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Whether the reading trace of [documentId] may carry the letter's lines: only in a debuggable build, and only for a document whose
     * id the developer listed in the app-private file `debug-trace-docs.txt` (one id per line). Empty by default, so no content is logged.
     */
    private fun traceContentFor(documentId: String): Boolean = isDebuggable() && runCatching {
        java.io.File(appContext.filesDir, "debug-trace-docs.txt").takeIf { it.isFile }?.readLines().orEmpty().any { it.trim() == documentId }
    }.getOrDefault(false)

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

/** Per-stage latency of a reading: one tag, stage + counts + milliseconds, never a word of the letter. */
private const val TIMING_TAG = "ExtractTiming"

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
private const val AI_ENGINE_VERSION = ExtractorVersion.CURRENT
private const val FOUND_VALUES_VERSION = ExtractorVersion.FOUND_VALUES

// The context window comes from ActiveModelProvider, which caps the catalogued value by
// what the device can actually afford. Passing a separate constant here would budget the
// prompt against one number while the KV cache was allocated for another.

// ProcessingState and ProcessingStage moved to :core:model (task 7.15.2) — the data layer
// must not decide what English a user reads for a progress message; see their doc comments.
