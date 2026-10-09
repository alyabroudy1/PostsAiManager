package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Which stored values have a meaning to choose and which meanings they can choose from, the one place that decides it: a date or an
 * amount (by the slot that holds it, by the kind of value code found, or by the type of a value a person added) chooses from the
 * meanings of its kind ([ValueMeanings]); nothing else has one.
 */
object FieldMeanings {

    /** The kind of meaning [field] can have, or null when it is neither a date nor an amount. */
    fun kindOf(field: ExtractedData, schema: ExtractionSchema = ExtractionSchema.DEFAULT, registry: ValueMeanings = ValueMeanings.DEFAULT): MeaningKind? {
        schema.allSlots.firstOrNull { it.json == field.slotKey }?.let { slot -> MeaningKind.of(slot.kind)?.let { return it } }
        ExtractionV2Adapter.parseFoundKey(field.slotKey)?.let { (kind, _) ->
            when (kind) {
                CandidateKind.DATE, CandidateKind.DATETIME -> return MeaningKind.DATE
                CandidateKind.AMOUNT -> return MeaningKind.AMOUNT
                else -> Unit
            }
        }
        ValueMeanings.fromRole(field.role, registry)?.let { return it.kind }
        return if (field.fieldType == ExtractedFieldType.DATE || field.fieldType == ExtractedFieldType.DEADLINE) MeaningKind.DATE else null
    }

    /** The meanings the person can choose for [field], in the registry's order; empty when it has none to choose. */
    fun choices(field: ExtractedData, registry: ValueMeanings = ValueMeanings.DEFAULT): List<ValueMeaning> =
        kindOf(field, registry = registry)?.let(registry::of).orEmpty()
}

/**
 * The user chooses what a date or an amount of the letter means (the letter's date, the due date, the amount to pay ...), or "none of
 * these". The choice is stored with the value as theirs ([DocumentRepository.setFieldMeaning]), so a re-read keeps it and only flags a
 * differing reading of the value; everything that is worded from the meaning (the row's label, the timeline's date, the chat's grounding)
 * follows from the stored role.
 */
class SetFieldMeaningUseCase @Inject constructor(private val documents: DocumentRepository) {

    /** @param meaningId an id of [ValueMeanings], or null for "none of these". */
    suspend operator fun invoke(documentId: String, fieldId: String, meaningId: String?): PamResult<Unit> {
        val field = documents.observeExtractedData(documentId).first().firstOrNull { it.id == fieldId }
            ?: return PamResult.Error(PamError.ValidationError("field", "no such value"))
        val meaning = meaningId?.let { id ->
            FieldMeanings.choices(field).firstOrNull { it.id == id }
                ?: return PamResult.Error(PamError.ValidationError("meaning", "$id is not a meaning of this value"))
        }
        return documents.setFieldMeaning(fieldId, meaning?.let(ValueMeanings::role))
    }
}
