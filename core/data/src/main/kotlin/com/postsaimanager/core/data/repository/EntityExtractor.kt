package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.FoundValues
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ExtractionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last resort when the reading stage produced nothing at all (no model, and not even values
 * found by code): the values that can be recognised by their shape alone, over the whole text.
 *
 * "Nothing static": no label, no role, no document type, no language and no sender or receiver is
 * guessed here. Every value is a found value: an IBAN that passes its checksum, an e-mail address, a
 * phone number, a date, an amount, an identifier. They carry [ExtractionV2Adapter.FOUND_CONFIDENCE]
 * so they show as worth checking; what they mean is for the user, or a later run with the model.
 *
 * The signature is unchanged so the pipeline call site needs no edit.
 */
@Singleton
class EntityExtractor @Inject constructor() {

    /** @param ocrLanguage kept for the call site; the shape-only reading does not depend on the language. */
    @Suppress("UNUSED_PARAMETER")
    fun extract(documentId: String, ocrText: String, ocrLanguage: String?): ExtractionResult {
        if (ocrText.isBlank()) {
            return ExtractionResult(documentId = documentId, language = ocrLanguage, fields = emptyList())
        }
        val found = FoundValues.of(CandidateExtractor.extractFromText(ocrText))
        val counters = HashMap<String, Int>()
        val fields = found.mapNotNull { c ->
            val (noun, type) = describe(c) ?: return@mapNotNull null
            val n = (counters[noun] ?: 0) + 1
            counters[noun] = n
            ExtractedData(
                id = UuidGenerator.generate(),
                documentId = documentId,
                fieldName = "Found $noun $n",
                fieldValue = c.raw.trim(),
                fieldType = type,
                confidence = ExtractionV2Adapter.FOUND_CONFIDENCE,
                pageNumber = c.page,
            )
        }
        return ExtractionResult(
            documentId = documentId,
            language = ocrLanguage,
            referenceNumbers = fields.filter { it.fieldType == ExtractedFieldType.REFERENCE_NUMBER }.map { it.fieldValue },
            fields = fields,
        )
    }

    /** The shape's noun and the storage type that fits it; a date is not a deadline, an amount is not a total. */
    private fun describe(c: Candidate): Pair<String, ExtractedFieldType>? = when (c.kind) {
        CandidateKind.DATE, CandidateKind.DATETIME -> "date" to ExtractedFieldType.DATE
        CandidateKind.AMOUNT -> "amount" to ExtractedFieldType.OTHER
        CandidateKind.IBAN -> "IBAN" to ExtractedFieldType.IBAN
        CandidateKind.REFERENCE -> "reference" to ExtractedFieldType.REFERENCE_NUMBER
        CandidateKind.PHONE -> "phone" to ExtractedFieldType.PHONE
        CandidateKind.EMAIL -> "e-mail" to ExtractedFieldType.EMAIL
        else -> null
    }
}
