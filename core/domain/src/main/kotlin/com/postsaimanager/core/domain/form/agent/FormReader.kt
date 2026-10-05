package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.form.FormCheckpoint
import com.postsaimanager.core.domain.form.FormProgress
import com.postsaimanager.core.domain.form.FormStep
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.form.SubjectSuggestion
import com.postsaimanager.core.domain.form.UnderstandFormRequest
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
import com.postsaimanager.core.domain.form.fill.FormFillTrace
import com.postsaimanager.core.domain.form.fill.FormMessageCodec
import com.postsaimanager.core.domain.form.fill.FormOcrTrace
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormChipLabel
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormFill
import com.postsaimanager.core.model.FormFillStatus
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** What reading a form gave: the stored fill and its fields, and (on a fresh reading) who the form looks like it is for. */
data class FormReading(
    val fill: FormFill,
    val fields: List<FormField>,
    val suggestion: SubjectSuggestion?,
    /** True when the stored reading of this very OCR was reused. */
    val cached: Boolean,
)

/**
 * Reads a document's form for the `read_form` tool: the existing understanding pipeline ([UnderstandFormUseCase], model-scored
 * steps with stored checkpoints), cached per document by the OCR it was read from, with a progress line in the chat. A reading
 * of another OCR is discarded; what the user typed or chose is carried to the same blanks of the new reading.
 */
class FormReader(
    private val fills: FormFillRepository,
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val understand: UnderstandFormUseCase,
    private val log: FormChatLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val fallbackLocale: () -> Locale = Locale::getDefault,
    private val trace: FormFillTrace = FormFillTrace.NONE,
    private val ocrTrace: FormOcrTrace = FormOcrTrace.NONE,
) {

    suspend fun read(documentId: String): PamResult<FormReading> {
        val conversation = log.ensureConversation(documentId)
        val fill = fills.fillForDocument(documentId)
            ?: FormFill(
                id = FormChatLog.fillId(documentId), documentId = documentId, status = FormFillStatus.UNDERSTANDING,
                conversationId = conversation, createdAt = clock(), updatedAt = clock(),
            ).also { fills.saveFill(it) }
        val pages = pagesOf(documentId)
        val key = readingKey(pages)
        val stored = fills.fields(fill.id)
        if (stored.isNotEmpty() && fill.readingKey == key) {
            trace.event("read", "fill=${fill.id} cached=true fields=${stored.size}")
            return PamResult.Success(FormReading(fill, stored, null, cached = true))
        }
        if (pages.isEmpty() || pages.all { it.isEmpty() }) return PamResult.Error(PamError.OcrFailed("no text on the pages"))

        // An interrupted reading of this very OCR continues after its last finished step (its progress line says how far it got);
        // a stored reading of another OCR is out of date: it is discarded and what the user answered is carried to the same blanks.
        val progress = lastProgress(documentId)
        val earlier = progress?.let(FormMessageCodec::parse)?.args.orEmpty()
        val resumable = progress != null && earlier.getOrNull(2) == key
        val carried = if (!resumable && stored.isNotEmpty()) answersOf(stored) else emptyList()
        if (!resumable && stored.isNotEmpty()) fills.deleteFields(fill.id)
        val working = fill.copy(status = FormFillStatus.UNDERSTANDING, readingKey = null, updatedAt = clock())
        fills.saveFill(working)
        val doneBefore = if (resumable) earlier.firstOrNull()?.toIntOrNull() ?: 0 else 0
        val progressId = progress?.takeIf { resumable }?.id?.also { log.adopt(it, progress.createdAt) } ?: UuidGenerator.generate()

        suspend fun status(done: Int, total: Int) =
            log.upsert(progressId, documentId, FormMessage(FormMessageKind.STATUS, FormText.UNDERSTANDING, listOf(done.toString(), total.toString(), key)))
        ocrTrace.lines(documentId, pages)
        status(doneBefore, STEPS)

        val language = documentLanguage(documentId)
        val storedForResume = if (doneBefore >= 2 && carried.isEmpty()) fills.fields(fill.id) else emptyList()
        val resumeAt = FormStep.entries.getOrNull(doneBefore - 1)?.takeIf { storedForResume.isNotEmpty() && it.ordinal >= FormStep.CONFIRM.ordinal }
        val request = UnderstandFormRequest(
            documentId = documentId, formFillId = fill.id, pages = pages, subjects = subjectCandidates(),
            today = today(), nowMs = clock(), fallbackLocale = language ?: fallbackLocale(),
            resume = resumeAt?.let { FormCheckpoint(it, storedForResume) },
            onCheckpoint = { checkpoint ->
                fills.saveFields(fill.id, checkpoint.fields)
                trace.event("checkpoint", "fill=${fill.id} step=${checkpoint.step} fields=${checkpoint.fields.size}")
            },
        )
        val result = coroutineScope {
            val updates = Channel<FormProgress>(Channel.CONFLATED)
            val reporter = launch { for (p in updates) status(p.done, p.total) }
            try {
                understand(request) { updates.trySend(it) }
            } finally {
                updates.close()
                reporter.join()
            }
        }
        val understanding = when (result) {
            is PamResult.Error -> return result
            is PamResult.Success -> result.data
        }
        status(STEPS, STEPS)
        fills.saveFields(fill.id, understanding.fields)
        restoreAnswers(fill.id, carried)
        val done = working.copy(
            status = FormFillStatus.ASKING, localeTag = (language ?: understanding.locale).toLanguageTag(), conversationId = conversation,
            readingKey = key, roleProfiles = if (carried.isEmpty()) working.roleProfiles else emptyMap(), updatedAt = clock(),
        )
        fills.saveFill(done)
        trace.event("understood", "fill=${fill.id} found=${understanding.fields.size} pages=${pages.size}")
        if (understanding.searchModelMissing) {
            log.post(
                documentId,
                FormMessage(FormMessageKind.STATUS, FormText.SEARCH_MODEL_MISSING, chips = listOf(FormChip(FormChipAction.OPEN_MODELS, labelCode = FormChipLabel.DOWNLOAD))),
            )
        }
        val best = understanding.subjectRanking.firstOrNull()?.takeIf { it.plausible && it.reasonLine != null }
        return PamResult.Success(FormReading(done, fills.fields(fill.id), best, cached = false))
    }

    private fun answersOf(fields: List<FormField>): List<FormField> =
        fields.filter { it.reviewState != ReviewState.UNREVIEWED && it.valueSource == FormValueSource.USER && it.value != null }

    private suspend fun restoreAnswers(fillId: String, answers: List<FormField>) {
        if (answers.isEmpty()) return
        val fields = fills.fields(fillId)
        answers.forEach { old ->
            val same = fields.firstOrNull { it.value == null && sameBlank(it, old) } ?: return@forEach
            fills.setValue(same.id, old.value, FormValueSource.USER, ReviewState.EDITED, old.profileId, clock())
        }
    }

    private fun sameBlank(a: FormField, b: FormField): Boolean =
        a.page == b.page && norm(a.labelText) == norm(b.labelText) && norm(a.section) == norm(b.section)

    private fun norm(text: String?): String = text.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    private suspend fun lastProgress(documentId: String): AiMessage? = log.messages(documentId)
        .lastOrNull { FormMessageCodec.parse(it)?.let { m -> m.kind == FormMessageKind.STATUS && m.text == FormText.UNDERSTANDING } == true }

    /** The stored OCR text of every page of the document, one block per line (what the form's own vocabulary is read from). */
    suspend fun ocrText(documentId: String): String = pagesOf(documentId).flatten().joinToString("\n") { it.text }

    private suspend fun pagesOf(documentId: String): List<List<OcrBlock>> =
        (documents.getDocumentPages(documentId) as? PamResult.Success)?.data?.sortedBy { it.pageNumber }?.map { it.ocrBlocks }.orEmpty()

    private suspend fun subjectCandidates(): List<SubjectCandidate> = FormRefs.orderedPeople(allProfiles()).map {
        SubjectCandidate(it.id, it.name, it.relationship, it.isSelf, it.birthDate?.let(::parseDate))
    }

    private suspend fun allProfiles() = profiles.getProfiles().first()

    private fun parseDate(iso: String): LocalDate? = try {
        LocalDate.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

    /** The document's stored language (a language tag the extraction's second stage wrote), or null when none is stored or it is no tag. */
    suspend fun documentLanguage(documentId: String): Locale? {
        val tag = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.language?.trim().orEmpty().replace('_', '-')
        return tag.takeIf { LANGUAGE_TAG.matches(it) && !it.equals("und", ignoreCase = true) }?.let(Locale::forLanguageTag)
    }

    companion object {
        /** The way of reading forms: bumped when a better reading should replace the stored ones (it is part of [readingKey]). */
        private const val READING_VERSION = "v4"

        /** The understanding has this many steps (see [FormStep]). */
        private const val STEPS = 5

        private val LANGUAGE_TAG = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")

        /** Identifies the reading of one OCR: the way of reading plus a hash of every block's text and place. */
        fun readingKey(pages: List<List<OcrBlock>>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            pages.forEach { page ->
                page.forEach { digest.update("${it.text}|${it.bounds}".toByteArray()) }
                digest.update(0)
            }
            return READING_VERSION + "-" + digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
