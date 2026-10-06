package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.document.list.PartyFields
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions
import com.postsaimanager.core.domain.extraction.text.ActionLinks
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
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
import com.postsaimanager.core.model.ValueSource

/**
 * The card at the top of the Extracted tab: the title the reading composed and the summary with a badge saying where it came from.
 * Who it is from and for, and what to do, are the sections below it ([Essentials]). Each part is absent when nothing was read for it.
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
    val summaryText: String? = null,
    val templateArgs: List<String>? = null,
    val summarySource: SummarySource? = null,
    val summaryComing: Boolean = false,
) {
    val hasSummary: Boolean get() = summaryText != null || templateArgs != null

    val isEmpty: Boolean get() = titleArgs == null && !hasSummary
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
 * What the main review button works on: the essential rows only (see [Essentials]); nothing in "All details" is counted.
 *
 * @property uncertain how many open essential rows the extraction was unsure of
 * @property confidentIds the open essential rows that are not uncertain: what "Confirm n confident" confirms
 * @property openIds the essential rows nobody has reviewed yet: what "Confirm all" confirms
 */
data class ReviewSummary(val uncertain: Int, val confidentIds: List<String>, val openIds: List<String>) {
    val confidentOpen: Int get() = confidentIds.size
    val open: Int get() = openIds.size

    /** "Confirm n confident" while uncertain rows remain (it leaves them), "Confirm all" once none do. */
    val mode: ConfirmMode
        get() = when {
            uncertain > 0 -> if (confidentOpen > 0) ConfirmMode.CONFIDENT else ConfirmMode.NONE
            open > 0 -> ConfirmMode.ALL
            else -> ConfirmMode.NONE
        }
}

/** One action line (what the reader must do) with the live fields it quotes, for their inline Confirm and Edit. */
data class ActionLine(val text: String, val rows: List<ExtractedData>)

/**
 * One party of the "From / For / About" block.
 *
 * @property row the party's stored row (the name as read)
 * @property recipient for the addressee: [PagesRecipient.You] on an exact folded match with the Me profile, else the name; null for the others
 * @property addressLines the party's verified address as printed lines (empty when none was read), shown as one compact expandable line
 */
data class PartyEntry(val row: ExtractedData, val recipient: PagesRecipient? = null, val addressLines: List<String> = emptyList())

/** From, For and About; each null when the reading found no such party, [about] also when it is the addressee. */
data class PartiesView(val from: PartyEntry?, val forWhom: PartyEntry?, val about: PartyEntry?) {
    val isEmpty: Boolean get() = from == null && forWhom == null && about == null
}

/**
 * What the Extracted tab shows first, top to bottom: what to do, who it is from and for, and the key information. Everything the AI
 * judged essential; the rest is in "All details". A document read before the key information existed has only the parties and the subject.
 *
 * @property actions the action lines the second stage wrote, each with the fields it quotes; empty when there are none
 * @property parties From / For / About
 * @property subject the subject line, the first of the "Key information"
 * @property keyInfo the facts the AI picked as important for this kind of document (label and value as printed), best first
 */
data class Essentials(
    val actions: List<ActionLine>,
    val parties: PartiesView,
    val subject: ExtractedData?,
    val keyInfo: List<ExtractedData>,
) {
    /** Every row that is essential: what "Check these" and the review button count. */
    val rows: List<ExtractedData>
        get() = (actions.flatMap { it.rows } + listOfNotNull(parties.from?.row, parties.forWhom?.row, parties.about?.row, subject) + keyInfo)
            .distinctBy { it.id }
}

/**
 * The Extracted tab, ready to draw.
 *
 * @property essentials the top of the tab (see [Essentials])
 * @property checkCount the number of uncertain essential rows: the only ones "Check these" counts; an uncertain row of "All details" stays there
 * @property sections "All details": the fields the family's layout lists, other than the essential ones; empty sections are left out
 * @property extras the open metadata that is not key information ("Other details"). Extras the model was unsure of
 *   ([ConfidenceCombiner.HIDDEN_BELOW]) are left out until [showAllExtras].
 * @property hiddenExtras how many extras are hidden behind "Show all".
 * @property ignored rows the person ignored, for the collapsed "Ignored (n)" footer where each can be restored
 */
data class ExtractedPresentation(
    val summary: SummaryCard,
    val header: Header,
    val essentials: Essentials,
    val checkCount: Int,
    val sections: List<PresentedSection>,
    val extras: List<ExtractedData>,
    val hiddenExtras: Int,
    val showAllExtras: Boolean,
    val ignored: List<ExtractedData>,
    val review: ReviewSummary,
) {
    val extraCount: Int get() = extras.size + hiddenExtras

    /** The size of "All details (n)": every row of the sections and every extra, shown or behind "Show all". */
    val detailCount: Int get() = sections.sumOf { s -> s.items.sumOf { it.shownRows.size } } + extraCount
}

/** A row whose extraction was unsure, or that changed under a person's value, and nobody has ignored. */
val ExtractedData.isUncertain: Boolean get() = reviewState != ReviewState.IGNORED && needsReview

/** A person confirmed or edited it: it is theirs, and a re-read leaves it. */
val ExtractedData.isSettled: Boolean get() = reviewState == ReviewState.CONFIRMED || reviewState == ReviewState.EDITED

/** A person removed it; it is a tombstone a re-read does not bring back. */
val ExtractedData.isIgnored: Boolean get() = reviewState == ReviewState.IGNORED || deletedByUser

/** Builds an [ExtractedPresentation] from a document and its stored fields. Pure; the layout is data in `FamilyPresentation`. */
object ExtractedPresenter {

    private val schema = ExtractionSchema.DEFAULT
    private val allSlots = schema.allSlots.distinctBy { it.json }

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

    /**
     * @param selfName the name on the "Me" profile: an addressee whose folded name equals it reads "You" ([PartyRecipients])
     */
    fun present(
        document: Document,
        fields: List<ExtractedData>,
        showAllExtras: Boolean = false,
        summaryComing: Boolean = false,
        selfName: String? = null,
    ): ExtractedPresentation {
        val spec = FamilyPresentation.of(document.extractionType)
        val family = FamilyPresentation.familyId(document.extractionType)?.let { schema.family(it) }
        val familyOrder = family?.slots?.map { it.json }.orEmpty()

        // An address row stored before verification was recorded may belong to an address that failed its checks (it mixes lines of the
        // letter): it is not shown. The party name rows are unaffected.
        // A row with no value is not a card (an empty "Sender" is nothing to read or check); only one a person added themselves stays,
        // so they can fill it in.
        val shownFields = fields.filter { (!AddressRows.isAddressKey(it.slotKey) || AddressRows.isShown(it.origin)) && hasValueToShow(it) }
        val (ignored, live) = shownFields.partition { it.isIgnored }
        val (extraRows, fixedRows) = live.partition { it.isExtra }

        // The address blocks: each is built with its party's name row (so a repeated name is not drawn twice) and gives the party's
        // compact address line; what stays in "All details" is the block without the name row, which the parties block shows.
        val fullBlocks = buildMap<SectionKind, AddressBlock> {
            if (spec.has(SectionKind.RECIPIENT_BLOCK)) block(PartyRole.ADDRESSEE, fixedRows)?.let { put(SectionKind.RECIPIENT_BLOCK, it) }
            if (spec.has(SectionKind.SENDER_BLOCK)) block(PartyRole.SENDER, fixedRows)?.let { put(SectionKind.SENDER_BLOCK, it) }
        }

        // What is essential: the parties, the subject, the key information the AI picked (the extras, for a document read by a version
        // that picks them by the family's hint) and the fields behind the action lines. A document read earlier has only the parties
        // and the subject: its extras were not picked for what the reader needs.
        val (visibleExtras, hiddenExtras) = extraRows.partition { showAllExtras || !isHidden(it) }
        val actions = ActionLinks.link(document.actionItems, live).map { ActionLine(it.text, it.rows) }
        val actionRowIds = actions.flatMap { a -> a.rows.map { it.id } }.toSet()
        // The slot rows the AI picked as key information (an invoice number, an IBAN ...) come first, best score first, then the extras. A
        // value an action line already states stays in that line's sub-lines and is not drawn twice.
        val readsKeyInfo = ExtractorVersion.readsKeyInfo(document.extractorVersion)
        val keySlotRows = if (readsKeyInfo) fixedRows.filter { it.isKeySlot }.sortedByDescending { it.importance } else emptyList()
        // The section is short whatever is stored: at most MAX_KEY_INFO rows, the rest stay under "All details".
        val keyInfo = if (readsKeyInfo) (keySlotRows + visibleExtras).filter { it.id !in actionRowIds }.take(ScoringDescriptions.MAX_KEY_INFO) else emptyList()
        val essentials = Essentials(
            actions = actions,
            parties = parties(fixedRows, fullBlocks, selfName),
            subject = fixedRows.firstOrNull { it.slotKey == UnderstandingToFields.SLOT_SUBJECT },
            keyInfo = keyInfo,
        )
        val essentialIds = essentials.rows.map { it.id }.toSet()

        val blocks = fullBlocks.mapValues { (_, b) ->
            b.copy(nameRow = null, rows = b.rows.filter { it.id != b.nameRow?.id })
        }.filterValues { it.shownRows.isNotEmpty() }
        val claimed = blocks.values.flatMap { b -> b.rows.map { it.id } }.toSet()

        // Every other fixed row goes into the section the spec names it in, else the one its kind belongs to.
        val bySection = fixedRows.filter { it.id !in claimed && it.id !in essentialIds }
            .groupBy { sectionFor(spec, it) }
            .mapValues { (_, rows) -> rows.sortedBy { rank(spec, it, familyOrder) }.map(::FieldItem) }

        val listed = spec.sections.map { it.kind }.filter { it != SectionKind.EXTRAS }
        val kinds = listed + (bySection.keys + blocks.keys).filter { it !in listed }.sortedBy { it.ordinal }
        val sections = kinds.mapNotNull { kind ->
            val items: List<DetailItem> = listOfNotNull(blocks[kind]) + bySection[kind].orEmpty()
            items.takeIf { it.isNotEmpty() }?.let { PresentedSection(kind, it) }
        }

        return ExtractedPresentation(
            summary = card(document).let { if (summaryComing && !it.hasSummary) it.copy(summaryComing = true) else it },
            header = header(document),
            essentials = essentials,
            checkCount = essentials.rows.count { it.isUncertain },
            sections = sections,
            extras = visibleExtras.filter { it.id !in essentialIds },
            hiddenExtras = hiddenExtras.size,
            showAllExtras = showAllExtras,
            ignored = ignored,
            review = reviewOf(essentials.rows),
        )
    }

    private fun hasValueToShow(row: ExtractedData): Boolean = row.fieldValue.isNotBlank() || row.source == ValueSource.USER

    /** From (the sender), For (the addressee, "You" for the Me profile) and About (the subject person, only when it is someone else). */
    private fun parties(rows: List<ExtractedData>, blocks: Map<SectionKind, AddressBlock>, selfName: String?): PartiesView {
        fun addressOf(kind: SectionKind) = blocks[kind]?.lines?.map { line -> line.parts.joinToString(" ") { it.fieldValue.lines().joinToString(" ") } }.orEmpty()
        val sender = PartyFields.sender(rows)
        val addressee = PartyFields.addressee(rows)
        val about = rows.firstOrNull { it.slotKey == UnderstandingToFields.SLOT_SUBJECT_PERSON }
            ?.takeIf { addressee == null || !PartyRecipients.sameName(it.fieldValue, addressee.fieldValue) }
        return PartiesView(
            from = sender?.let { PartyEntry(it, addressLines = addressOf(SectionKind.SENDER_BLOCK)) },
            forWhom = addressee?.let {
                val recipient = PartyRecipients.of(it.fieldValue, selfName)
                PartyEntry(it, recipient, addressLines = if (recipient is PagesRecipient.You) emptyList() else addressOf(SectionKind.RECIPIENT_BLOCK))
            },
            about = about?.let { PartyEntry(it) },
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

    /** Counts what the main button works on: the essential rows only. An uncertain row of "All details" is never counted, and never confirmed here. */
    private fun reviewOf(essential: List<ExtractedData>): ReviewSummary {
        val open = essential.filter { it.reviewState == ReviewState.UNREVIEWED }
        return ReviewSummary(
            uncertain = essential.count { it.isUncertain },
            confidentIds = open.filter { !it.needsReview }.map { it.id },
            openIds = open.map { it.id },
        )
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
            UnderstandingToFields.SLOT_SENDER, UnderstandingToFields.SLOT_ADDRESSEE, UnderstandingToFields.SLOT_CONTACT,
            UnderstandingToFields.SLOT_SUBJECT_PERSON,
            -> return SectionKind.PARTIES
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

    private fun card(document: Document): SummaryCard {
        val text = document.summary?.takeIf { it.isNotBlank() }
        val template = text == null && document.summaryCode == SummaryWriter.TEMPLATE_CODE && document.summaryArgs.isNotEmpty()
        return SummaryCard(
            titleArgs = document.titleArgs.takeIf { TitleComposer.isComposed(document.titleCode) && it.isNotEmpty() },
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
