package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.document.list.PartyFields
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.FamilyPresentation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.PresentationSpec
import com.postsaimanager.core.domain.extraction.v2.SectionKind
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.AddressPart
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SummarySource

/** One line of the summary card: the value, and whether it is worth checking. */
data class CardLine(val value: String, val worthChecking: Boolean)

/**
 * The card at the top of the Extracted tab: the title the reading composed, who it is from, who it is for, what it is, how
 * much and by when, and the summary with a badge saying where it came from. Each part is absent when nothing was read for it.
 *
 * @property titleArgs the positional args of a composed title (`TitleComposer`: family id, sender, subject); null when the
 *   document's title is not a composed one (the top bar shows it)
 * @property summaryText the summary in words: the model's, or the person's own edit; null when there is none or it is a template
 * @property templateArgs the args of a template summary (`SummaryFacts.templateArgs`) the screen renders from string resources
 * @property summarySource where the summary came from, which decides its badge; null when there is no summary
 * @property summaryComing the summary is not there yet but is being written (the reading's second stage): the card says "Summary coming…"
 */
data class SummaryCard(
    val titleArgs: List<String>? = null,
    val from: CardLine? = null,
    val forWhom: CardLine? = null,
    /** The model's document type id (`bill`, ...); rendered from a string resource. */
    val typeId: String? = null,
    val amount: CardLine? = null,
    val due: CardLine? = null,
    val summaryText: String? = null,
    val templateArgs: List<String>? = null,
    val summarySource: SummarySource? = null,
    val summaryComing: Boolean = false,
) {
    val hasSummary: Boolean get() = summaryText != null || templateArgs != null

    val isEmpty: Boolean
        get() = titleArgs == null && from == null && forWhom == null && typeId == null && amount == null && due == null && !hasSummary
}

/** What the chip row at the top says about the reading: the family, the topics and how sure the classifier was. */
data class Header(
    /** The stored type id (a family id, or a legacy id of a document not read again yet); null before a model read it. */
    val typeId: String?,
    val topics: List<String>,
    val confidence: Confidence?,
)

/** How sure the classifier was of the family; a person's own choice has none. */
enum class Confidence { HIGH, MEDIUM, LOW }

/** One printed line of an address block: one part, or two that read as one (street and number, postcode and city). Each part is a stored row. */
data class AddressLine(val parts: List<ExtractedData>)

/** An entry of a section: one field row, or an address block assembled from its rows. */
sealed interface DetailItem {
    /** The rows a block-level action (Confirm, Ignore) applies to. */
    val rows: List<ExtractedData>

    /** The rows that are drawn (an address block does not draw its raw-lines row while it has parts). */
    val shownRows: List<ExtractedData>

    /** The shown rows the extraction was unsure of. */
    val uncertainRows: List<ExtractedData> get() = shownRows.filter { it.isUncertain }

    /** Every shown row is confirmed or edited, so the item collapses to one line. */
    val isSettled: Boolean get() = shownRows.isNotEmpty() && shownRows.all { it.isSettled }
}

data class FieldItem(val row: ExtractedData) : DetailItem {
    override val rows: List<ExtractedData> get() = listOf(row)
    override val shownRows: List<ExtractedData> get() = rows
}

/**
 * The recipient or the sender as one block: the party's name row, then the structured address rows as lines.
 *
 * @property nameRow the party row (`addressee` / `sender`); null when only the structured rows exist
 * @property rawRow the printed lines of the block, drawn only when no part was read
 * @property rows every live row of the block, for the block-level actions
 */
data class AddressBlock(
    val role: PartyRole,
    val nameRow: ExtractedData?,
    val lines: List<AddressLine>,
    val rawRow: ExtractedData?,
    override val rows: List<ExtractedData>,
) : DetailItem {
    override val shownRows: List<ExtractedData>
        get() = listOfNotNull(nameRow) + lines.flatMap { it.parts } + listOfNotNull(rawRow.takeIf { lines.isEmpty() })
}

/** One section of the tab, in the order the family's spec lists them. */
data class PresentedSection(val kind: SectionKind, val items: List<DetailItem>)

/** What the main review button does for the fields that are still open. */
enum class ConfirmMode { NONE, CONFIDENT, ALL }

/**
 * @property uncertain how many open rows the extraction was unsure of (even those the lists do not show)
 * @property confidentOpen how many open rows are not uncertain: what "Confirm n confident" confirms
 * @property open how many rows nobody has reviewed yet
 */
data class ReviewSummary(val uncertain: Int, val confidentOpen: Int, val open: Int) {
    /** "Confirm n confident" while uncertain rows remain (it leaves them), "Confirm all" once none do. */
    val mode: ConfirmMode
        get() = when {
            uncertain > 0 -> if (confidentOpen > 0) ConfirmMode.CONFIDENT else ConfirmMode.NONE
            open > 0 -> ConfirmMode.ALL
            else -> ConfirmMode.NONE
        }
}

/**
 * The Extracted tab, ready to draw.
 *
 * @property check the "Check these" group: the items with something the extraction was unsure of, shown expanded and first
 * @property checkCount the number of uncertain rows in [check]
 * @property sections the rest, in the family's layout; empty sections are left out
 * @property extras open metadata the model found ("Other details"), shown collapsed. Extras the model was unsure of
 *   ([ConfidenceCombiner.HIDDEN_BELOW]) are left out until [showAllExtras].
 * @property hiddenExtras how many extras are hidden behind "Show all".
 * @property ignored rows the person ignored, for the collapsed "Ignored (n)" footer where each can be restored
 */
data class ExtractedPresentation(
    val summary: SummaryCard,
    val header: Header,
    val check: List<DetailItem>,
    val checkCount: Int,
    val sections: List<PresentedSection>,
    val extras: List<ExtractedData>,
    val hiddenExtras: Int,
    val showAllExtras: Boolean,
    val ignored: List<ExtractedData>,
    val review: ReviewSummary,
) {
    val extraCount: Int get() = extras.size + hiddenExtras
}

/** A row whose extraction was unsure, or that changed under a person's value, and nobody has ignored. */
val ExtractedData.isUncertain: Boolean get() = reviewState != ReviewState.IGNORED && needsReview

/** A person confirmed or edited it: it is theirs, and a re-read leaves it. */
val ExtractedData.isSettled: Boolean get() = reviewState == ReviewState.CONFIRMED || reviewState == ReviewState.EDITED

/** A person removed it; it is a tombstone a re-read does not bring back. */
val ExtractedData.isIgnored: Boolean get() = reviewState == ReviewState.IGNORED || deletedByUser

/** Builds an [ExtractedPresentation] from a document and its stored fields. Pure; the layout is data in `FamilyPresentation`. */
object ExtractedPresenter {

    private val schemas = listOf(ExtractionSchema.V2, ExtractionSchema.DEFAULT)
    private val allSlots = schemas.flatMap { it.allSlots }.distinctBy { it.json }

    /** The lines of an address block, top to bottom; a line with two parts reads as one ("Hauptstr. 12", "10115 Berlin"). */
    private val addressLines: List<List<AddressPart>> = listOf(
        listOf(AddressPart.RECIPIENT_NAME),
        listOf(AddressPart.ORGANISATION),
        listOf(AddressPart.DEPARTMENT),
        listOf(AddressPart.CARE_OF),
        listOf(AddressPart.STREET, AddressPart.HOUSE_NUMBER),
        listOf(AddressPart.ADDRESS_EXTRA),
        listOf(AddressPart.PO_BOX),
        listOf(AddressPart.PACKSTATION),
        listOf(AddressPart.POSTCODE, AddressPart.CITY),
        listOf(AddressPart.REGION),
        listOf(AddressPart.COUNTRY),
    )

    private val amountKeys = listOf("total", "new_amount", "proof_amount")
    private val dueKeys = listOf("due_date", "objection_deadline")

    fun present(
        document: Document,
        fields: List<ExtractedData>,
        showAllExtras: Boolean = false,
        summaryComing: Boolean = false,
    ): ExtractedPresentation {
        val spec = FamilyPresentation.of(document.extractionType)
        val family = FamilyPresentation.familyId(document.extractionType)?.let { ExtractionSchema.V2.family(it) }
            ?: schemas.firstNotNullOfOrNull { it.family(document.extractionType) }
        val familyOrder = family?.slots?.map { it.json }.orEmpty()

        val (ignored, live) = fields.partition { it.isIgnored }
        val (extraRows, fixedRows) = live.partition { it.isExtra }

        // The address blocks first: they claim the name row and the structured rows of their party.
        val blocks = buildMap<SectionKind, AddressBlock> {
            if (spec.has(SectionKind.RECIPIENT_BLOCK)) block(PartyRole.ADDRESSEE, fixedRows)?.let { put(SectionKind.RECIPIENT_BLOCK, it) }
            if (spec.has(SectionKind.SENDER_BLOCK)) block(PartyRole.SENDER, fixedRows)?.let { put(SectionKind.SENDER_BLOCK, it) }
        }
        val claimed = blocks.values.flatMap { b -> b.rows.map { it.id } }.toSet()

        // Every other fixed row goes into the section the spec names it in, else the one its kind belongs to.
        val bySection = fixedRows.filter { it.id !in claimed }
            .groupBy { sectionFor(spec, it) }
            .mapValues { (_, rows) -> rows.sortedBy { rank(spec, it, familyOrder) }.map(::FieldItem) }

        val listed = spec.sections.map { it.kind }.filter { it != SectionKind.EXTRAS }
        val kinds = listed + (bySection.keys + blocks.keys).filter { it !in listed }.sortedBy { it.ordinal }
        val all = kinds.mapNotNull { kind ->
            val items: List<DetailItem> = listOfNotNull(blocks[kind]) + bySection[kind].orEmpty()
            items.takeIf { it.isNotEmpty() }?.let { PresentedSection(kind, it) }
        }

        // Extras the extraction is unsure of are listed under "Check these" too; the very unsure ones stay behind "Show all".
        val (visibleExtras, hiddenExtras) = extraRows.partition { showAllExtras || !isHidden(it) }

        val check = all.flatMap { s -> s.items.filter { it.uncertainRows.isNotEmpty() } } +
            visibleExtras.filter { it.isUncertain }.map(::FieldItem)
        val sections = all.map { s -> s.copy(items = s.items.filter { it.uncertainRows.isEmpty() }) }.filter { it.items.isNotEmpty() }

        return ExtractedPresentation(
            summary = card(document, live).let { if (summaryComing && !it.hasSummary) it.copy(summaryComing = true) else it },
            header = header(document),
            check = check,
            checkCount = check.sumOf { it.uncertainRows.size },
            sections = sections,
            extras = visibleExtras.filter { !it.isUncertain },
            hiddenExtras = hiddenExtras.size,
            showAllExtras = showAllExtras,
            ignored = ignored,
            review = reviewOf(live, blocks.values),
        )
    }

    private fun header(document: Document): Header {
        val confidence = document.extractionTypeConfidence
            ?.takeIf { document.familySource == FamilySource.MODEL && document.extractionType != null }
            ?.let { c ->
                when {
                    c >= ConfidenceCombiner.REVIEW_BELOW -> Confidence.HIGH
                    c >= ConfidenceCombiner.HIDDEN_BELOW -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }
            }
        return Header(document.extractionType, document.topics, confidence)
    }

    /** Counts what the main button works on. A block's raw row is not drawn but is confirmed with the block, so it is open but never uncertain. */
    private fun reviewOf(live: List<ExtractedData>, blocks: Collection<AddressBlock>): ReviewSummary {
        val hiddenRaw = blocks.filter { it.lines.isNotEmpty() }.mapNotNull { it.rawRow?.id }.toSet()
        val open = live.filter { it.reviewState == ReviewState.UNREVIEWED }
        val uncertain = live.count { it.isUncertain && it.id !in hiddenRaw }
        return ReviewSummary(uncertain = uncertain, confidentOpen = open.count { !it.needsReview || it.id in hiddenRaw }, open = open.size)
    }

    private fun isHidden(extra: ExtractedData): Boolean =
        extra.reviewState == ReviewState.UNREVIEWED && extra.confidence < ConfidenceCombiner.HIDDEN_BELOW

    /** The block of [role]: its name row and the `addressee.*` / `sender.*` rows, or null when none of them exists. */
    private fun block(role: PartyRole, rows: List<ExtractedData>): AddressBlock? {
        val prefix = AddressRows.prefixOf(role) ?: return null
        val nameRow = when (role) {
            PartyRole.SENDER -> PartyFields.sender(rows)
            else -> PartyFields.addressee(rows)
        }
        val addressRows = rows.filter { it.slotKey?.startsWith(prefix) == true }
        if (nameRow == null && addressRows.isEmpty()) return null
        val byPart = addressRows.associateBy { it.slotKey!!.removePrefix(prefix) }

        val lines = addressLines.mapNotNull { parts ->
            val present = parts.mapNotNull { part ->
                byPart[part.key]?.takeUnless { part == AddressPart.RECIPIENT_NAME && sameText(it.fieldValue, nameRow?.fieldValue) }
            }
            present.takeIf { it.isNotEmpty() }?.let(::AddressLine)
        }
        return AddressBlock(
            role = role,
            nameRow = nameRow,
            lines = lines,
            rawRow = byPart[AddressRows.RAW],
            rows = listOfNotNull(nameRow) + addressRows,
        )
    }

    private fun sameText(a: String, b: String?): Boolean = b != null && a.trim().equals(b.trim(), ignoreCase = true)

    /** The section a fixed row is drawn in: the spec's own placement, else its slot's kind, else its field type. */
    private fun sectionFor(spec: PresentationSpec, field: ExtractedData): SectionKind {
        field.slotKey?.let { key -> spec.sectionOf(key)?.let { return it } }
        val money = listOf(SectionKind.ACTION, SectionKind.PAYMENT).firstOrNull { spec.has(it) } ?: SectionKind.ACTION
        when (field.slotKey) {
            UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE, UnderstandingToFields.SLOT_CONTACT ->
                return SectionKind.PARTIES
            UnderstandingToFields.SLOT_SUBJECT -> return SectionKind.TEXT
        }
        allSlots.firstOrNull { it.json == field.slotKey }?.let { slot ->
            return when (slot.kind) {
                SlotKind.AMOUNT, SlotKind.IBAN, SlotKind.DEADLINE -> money
                SlotKind.DATE -> SectionKind.DATES
                SlotKind.REFERENCE, SlotKind.REFERENCE_LIST -> SectionKind.REFERENCES
                SlotKind.NAME -> SectionKind.PARTIES
                SlotKind.ACTION -> SectionKind.TEXT
            }
        }
        // No slot: a field an older extractor wrote or a person added; its type is all there is to go by.
        return when (field.fieldType) {
            ExtractedFieldType.PERSON_NAME, ExtractedFieldType.ORGANIZATION, ExtractedFieldType.ADDRESS,
            ExtractedFieldType.PHONE, ExtractedFieldType.EMAIL,
            -> SectionKind.PARTIES
            ExtractedFieldType.DATE -> SectionKind.DATES
            ExtractedFieldType.DEADLINE, ExtractedFieldType.IBAN -> money
            ExtractedFieldType.REFERENCE_NUMBER -> SectionKind.REFERENCES
            else -> if (field.fieldName == UnderstandingToFields.AMOUNT) money else SectionKind.TEXT
        }
    }

    /** People and the subject keep this order ahead of the section's own slot list. */
    private val fixedOrder = listOf(
        UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE,
        UnderstandingToFields.SLOT_CONTACT, UnderstandingToFields.SLOT_SUBJECT,
    )

    private fun rank(spec: PresentationSpec, field: ExtractedData, familyOrder: List<String>): Int {
        fixedOrder.indexOf(field.slotKey).takeIf { it >= 0 }?.let { return it - fixedOrder.size }
        val listed = spec.sections.flatMap { it.slots }.indexOf(field.slotKey)
        if (listed >= 0) return listed
        return familyOrder.indexOf(field.slotKey).takeIf { it >= 0 }?.let { it + LISTED_AFTER } ?: Int.MAX_VALUE
    }

    /** Slots a spec does not name come after the ones it does, in the family's own order. */
    private const val LISTED_AFTER = 1_000

    private fun card(document: Document, live: List<ExtractedData>): SummaryCard {
        fun line(row: ExtractedData?) = row?.let { CardLine(it.fieldValue, it.needsReview) }
        fun bySlot(keys: List<String>, legacyName: String): ExtractedData? =
            keys.firstNotNullOfOrNull { key -> live.firstOrNull { it.slotKey == key } }
                ?: live.firstOrNull { it.slotKey == null && it.fieldName == legacyName }

        val text = document.summary?.takeIf { it.isNotBlank() }
        val template = text == null && document.summaryCode == SummaryWriter.TEMPLATE_CODE && document.summaryArgs.isNotEmpty()
        return SummaryCard(
            titleArgs = document.titleArgs.takeIf { TitleComposer.isComposed(document.titleCode) && it.isNotEmpty() },
            from = line(PartyFields.sender(live)),
            forWhom = line(PartyFields.addressee(live)),
            typeId = document.extractionType,
            amount = line(bySlot(amountKeys, UnderstandingToFields.AMOUNT)),
            due = line(bySlot(dueKeys, UnderstandingToFields.DEADLINE)),
            summaryText = text,
            templateArgs = document.summaryArgs.takeIf { template },
            summarySource = when {
                text != null -> document.summarySource ?: SummarySource.MODEL
                template -> SummarySource.TEMPLATE
                else -> null
            },
        )
    }
}
