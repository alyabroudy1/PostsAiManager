package com.postsaimanager.core.data.repository

import android.util.Log
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.document.EnrichmentTicketRebuilder
import com.postsaimanager.core.domain.document.ReprocessOverwritePolicy
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsOutcome
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextsRequest
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.domain.extraction.gemma.PaidState
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ExtraValue
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FieldProvenance
import com.postsaimanager.core.model.RecognisedFact

/** How the text step of a stored Gemma reading ended. */
internal sealed interface GemmaTextStageResult {

    /** The reading is not Gemma's (the old reader is chosen, no ticket says otherwise): the usual second stage writes the texts. */
    data object NotGemma : GemmaTextStageResult

    /** The summary was settled (the model's sentences or the template) and the key facts stored. */
    data object Stored : GemmaTextStageResult

    /** The document has no stored reading or text to write from, or was trashed meanwhile: nothing is owed any more. */
    data object Gone : GemmaTextStageResult

    /** The step could not run now (the model is busy, missing or too slow): the attempt is counted and asked again later. */
    class Failed(val reason: String) : GemmaTextStageResult
}

/**
 * The second step of a Gemma reading, the one that runs after the reading is stored: the summary and the key facts, written by
 * [GemmaTrialReading.writeTexts] from the letter's stored text and the facts the reading stored (a value a person corrected counts as
 * it is), and stored the way the staged second stage stores its extras (the same merge: a value a person wrote or confirmed, and every
 * deletion, is kept). The reading itself, its title, its actions and its timeline event are not touched: they were decided in the one call.
 */
internal class GemmaTextStage(
    private val gemma: GemmaTrialReading,
    private val documentDao: DocumentDao,
    private val fieldRevisionDao: FieldRevisionDao,
    private val documentMapper: DocumentMapper,
    private val mergeExtraction: MergeExtractionUseCase,
    /** The key facts' labels are written in the app's language; null keeps the document's own language. */
    private val appLanguage: com.postsaimanager.core.domain.settings.AppLanguageProvider? = null,
) {

    suspend fun run(documentId: String, ticket: EnrichmentTicket?): GemmaTextStageResult {
        val doc = documentDao.getById(documentId)?.takeIf { it.deletedAt == null } ?: return GemmaTextStageResult.Gone
        val oneGo = ticket?.oneGo == true
        // A ticket of the staged second stage is not ours; a lost one (null) is ours only while Gemma is the chosen reader.
        if (ticket != null && !oneGo) return GemmaTextStageResult.NotGemma
        if (doc.extractionType == null) return if (oneGo) GemmaTextStageResult.Gone else GemmaTextStageResult.NotGemma

        val storedFields = documentDao.getExtractedData(documentId).map(documentMapper::extractedDataToDomain)
        val domainDoc = documentMapper.toDomain(doc)
        val ocrText = documentDao.getPages(documentId).sortedBy { it.pageNumber }
            .mapNotNull { it.ocrText?.takeIf { text -> text.isNotBlank() } }.joinToString("\n")
        if (ocrText.isEmpty()) return if (oneGo) GemmaTextStageResult.Gone else GemmaTextStageResult.NotGemma

        val request = GemmaTextsRequest(
            documentId = documentId,
            text = GemmaTextRequest(
                ocrText = ocrText,
                facts = EnrichmentTicketRebuilder.factsOf(domainDoc, storedFields),
                knownValues = EnrichmentTicketRebuilder.rebuild(domainDoc, storedFields).takenValues,
                languageCode = appLanguage?.aiLanguageCode() ?: doc.language,
                paid = PaidState.of(ticket?.paid),
                // Only the key facts are asked, always: the summary is the reading's first turn (trimmed to the one length limit and checked),
                // and a summary field in this answer's schema ran to the token cap on the device (pass 28); the template is the fallback.
                writeSummary = false,
            ),
            oneGo = oneGo,
        )
        val written = when (val outcome = gemma.writeTexts(request)) {
            GemmaTextsOutcome.NotGemma -> return GemmaTextStageResult.NotGemma
            is GemmaTextsOutcome.Done -> when (val o = outcome.outcome) {
                is GemmaTextOutcome.Unavailable -> return GemmaTextStageResult.Failed(o.reason)
                is GemmaTextOutcome.Written -> o
            }
        }
        written.notes.forEach { Log.i(TAG, "gemma texts of $documentId: $it") }

        // The key facts are open values (extras), stored under the label the model gave them; a label a stored field already has is
        // stored under its identity instead, which is unique by construction (the screen words it).
        val takenNames = storedFields.filterNot(UnderstandingToFields::writtenInSecondStage).map { it.fieldName.lowercase() }.toSet()
        val facts = written.keyInfo.map { k ->
            val identity = ExtraValue.identityOf(k.label)
            RecognisedFact(
                label = if (k.label.lowercase() in takenNames) identity else k.label,
                value = k.value,
                kind = FactKind.OTHER,
                confidence = ConfidenceCombiner.MEDIUM,
                provenance = FieldProvenance(slotKey = identity, origin = SlotOrigin.MODEL_QUOTED.name, aiConfidence = ConfidenceCombiner.MEDIUM),
            )
        }
        val fields = UnderstandingToFields.invoke(documentId, DocumentUnderstanding(facts = facts), newId = { UuidGenerator.generate() })
            .filter(UnderstandingToFields::writtenInSecondStage)
        val freshNames = fields.map { it.fieldName }.toSet()
        val merged = mergeExtraction(
            existing = storedFields.filter { UnderstandingToFields.writtenInSecondStage(it) || it.fieldName in freshNames },
            extracted = fields, engineVersion = ExtractorVersion.CURRENT, now = System.currentTimeMillis(), newId = { UuidGenerator.generate() },
        )
        merged.idsToDelete.forEach { documentDao.deleteExtractedField(it) }
        documentDao.insertExtractedData(merged.toPersist.map(documentMapper::extractedDataToEntity))
        fieldRevisionDao.insertAll(merged.revisions.map(documentMapper::revisionToEntity))

        // Re-read right before the write: the document may have been trashed or edited while the model was writing. A summary a person
        // wrote is never replaced (the policy).
        val latest = documentDao.getById(documentId)?.takeIf { it.deletedAt == null } ?: return GemmaTextStageResult.Gone
        val summary = if (written.summaryAsked) {
            DocumentUnderstanding(
                summary = written.summary.text.orEmpty(), summarySource = written.summary.origin,
                summaryCode = written.summary.code, summaryArgs = written.summary.args,
            )
        } else {
            DocumentUnderstanding()
        }
        val updated = ReprocessOverwritePolicy.applySummary(documentMapper.toDomain(latest), summary).copy(enrichmentPending = false)
        documentDao.update(documentMapper.toEntity(updated).copy(syncStatus = latest.syncStatus))
        Log.i(TAG, "gemma texts stored for $documentId: summary=${written.summary.origin} keyInfo=${written.keyInfo.size}")
        return GemmaTextStageResult.Stored
    }

    private companion object {
        const val TAG = "DocProcessing"
    }
}
