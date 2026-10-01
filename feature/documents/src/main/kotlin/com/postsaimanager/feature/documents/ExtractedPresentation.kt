package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.document.list.PartyFields
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ValueSource

/** One line of the summary card: the value, and whether it is worth checking. */
data class CardLine(val value: String, val worthChecking: Boolean)

/**
 * The card at the top of the Extracted tab: who it is from, who it is for, what it is, how much and
 * by when, and the AI's one- or two-sentence summary. Each part is absent when nothing was read for it.
 */
data class SummaryCard(
    val from: CardLine? = null,
    val forWhom: CardLine? = null,
    /** The model's document type id (`bill`, ...); rendered from a string resource. */
    val typeId: String? = null,
    val amount: CardLine? = null,
    val due: CardLine? = null,
    /** Marked "AI summary" wherever it is shown: the model wrote it, nothing checked it as fact. */
    val aiSummary: String? = null,
    /** The summary is not there yet but is being written (the reading's second stage): the card says "Summary coming…". */
    val summaryComing: Boolean = false,
) {
    val isEmpty: Boolean get() = from == null && forWhom == null && typeId == null && amount == null && due == null && aiSummary == null
}

/** What sort of thing a field is, for grouping under "Details". */
enum class DetailGroup { PARTIES, MONEY, DATES, REFERENCES, TEXT }

data class DetailSection(val group: DetailGroup, val fields: List<ExtractedData>)

/**
 * The Extracted tab, ready to draw.
 *
 * @property details fields grouped by what they are (people, money, dates, references, text), each
 *   group in the document type's slot order, empty groups left out.
 * @property extras open metadata the model found ("Other details"), shown collapsed. Extras the model
 *   was unsure of ([ConfidenceCombiner.HIDDEN_BELOW]) are left out until [showAllExtras].
 * @property hiddenExtras how many extras are hidden behind "Show all".
 */
data class ExtractedPresentation(
    val summary: SummaryCard,
    val details: List<DetailSection>,
    val extras: List<ExtractedData>,
    val hiddenExtras: Int,
    val showAllExtras: Boolean,
) {
    val extraCount: Int get() = extras.size + hiddenExtras
}

/** Builds an [ExtractedPresentation] from a document and its stored fields. Pure. */
object ExtractedPresenter {

    private val schema = ExtractionSchema.DEFAULT

    /**
     * The family of a stored type id: a legacy type the live interpreter still writes, a v2 family id (a migrated
     * document), or the family a legacy id stands for ([LegacyTypes]).
     */
    private fun familyOf(typeId: String?) =
        schema.family(typeId)
            ?: ExtractionSchema.V2.family(typeId)
            ?: LegacyTypes.of(typeId)?.let { ExtractionSchema.V2.family(it.family) }

    private val amountKeys =listOf("total", "new_amount", "proof_amount")
    private val dueKeys = listOf("due_date", "objection_deadline")

    fun present(document: Document, fields: List<ExtractedData>, showAllExtras: Boolean = false, summaryComing: Boolean = false): ExtractedPresentation {
        // A field the user deleted is a tombstone that keeps extraction from bringing it back; it is not shown.
        val live = fields.filter { !it.deletedByUser }
        val order = familyOf(document.extractionType)?.slots?.map { it.json }.orEmpty()

        val (extraRows, fixedRows) = live.partition { it.isExtra }

        val details = fixedRows
            .groupBy(::groupOf)
            .map { (group, rows) -> DetailSection(group, rows.sortedBy { slotRank(it, order) }) }
            .sortedBy { it.group.ordinal }

        val (visible, hidden) = extraRows.partition { showAllExtras || !isHidden(it) }
        return ExtractedPresentation(
            summary = card(document, live).let { if (summaryComing && it.aiSummary == null) it.copy(summaryComing = true) else it },
            details = details,
            extras = visible,
            hiddenExtras = hidden.size,
            showAllExtras = showAllExtras,
        )
    }

    private fun isHidden(extra: ExtractedData): Boolean =
        extra.source == ValueSource.MACHINE && !extra.isConfirmed && extra.confidence < ConfidenceCombiner.HIDDEN_BELOW

    /** People and the subject keep this order ahead of the type's own slots. */
    private val fixedOrder = listOf(
        UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE,
        UnderstandingToFields.SLOT_CONTACT, UnderstandingToFields.SLOT_SUBJECT,
    )

    private fun slotRank(field: ExtractedData, order: List<String>): Int =
        fixedOrder.indexOf(field.slotKey).takeIf { it >= 0 }?.let { it - fixedOrder.size }
            ?: order.indexOf(field.slotKey).takeIf { it >= 0 }
            ?: Int.MAX_VALUE

    fun groupOf(field: ExtractedData): DetailGroup {
        when (field.slotKey) {
            UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE, UnderstandingToFields.SLOT_CONTACT ->
                return DetailGroup.PARTIES
            UnderstandingToFields.SLOT_SUBJECT -> return DetailGroup.TEXT
        }
        schema.allSlots.firstOrNull { it.json == field.slotKey }?.let { slot ->
            return when (slot.kind) {
                SlotKind.AMOUNT, SlotKind.IBAN -> DetailGroup.MONEY
                SlotKind.DATE, SlotKind.DEADLINE -> DetailGroup.DATES
                SlotKind.REFERENCE, SlotKind.REFERENCE_LIST -> DetailGroup.REFERENCES
                SlotKind.NAME -> DetailGroup.PARTIES
                SlotKind.ACTION -> DetailGroup.TEXT
            }
        }
        // No slot: a field an older extractor wrote or a person added; its type is all there is to go by.
        return when (field.fieldType) {
            ExtractedFieldType.PERSON_NAME, ExtractedFieldType.ORGANIZATION, ExtractedFieldType.ADDRESS,
            ExtractedFieldType.PHONE, ExtractedFieldType.EMAIL,
            -> DetailGroup.PARTIES
            ExtractedFieldType.DATE, ExtractedFieldType.DEADLINE -> DetailGroup.DATES
            ExtractedFieldType.IBAN -> DetailGroup.MONEY
            ExtractedFieldType.REFERENCE_NUMBER -> DetailGroup.REFERENCES
            else -> if (field.fieldName == UnderstandingToFields.AMOUNT) DetailGroup.MONEY else DetailGroup.TEXT
        }
    }

    private fun card(document: Document, live: List<ExtractedData>): SummaryCard {
        fun line(row: ExtractedData?) = row?.let { CardLine(it.fieldValue, it.needsReview) }
        fun bySlot(keys: List<String>, legacyName: String): ExtractedData? =
            keys.firstNotNullOfOrNull { key -> live.firstOrNull { it.slotKey == key } }
                ?: live.firstOrNull { it.slotKey == null && it.fieldName == legacyName }

        return SummaryCard(
            from = line(PartyFields.sender(live)),
            forWhom = line(PartyFields.addressee(live)),
            typeId = document.extractionType,
            amount = line(bySlot(amountKeys, UnderstandingToFields.AMOUNT)),
            due = line(bySlot(dueKeys, UnderstandingToFields.DEADLINE)),
            aiSummary = document.summary?.takeIf { it.isNotBlank() },
        )
    }
}
