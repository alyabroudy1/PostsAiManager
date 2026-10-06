package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.ValueSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * The rows of the document list (Home's "Recent documents" and the Documents screen): each document
 * with what a person needs to decide whether to open it, in the order the repository lists them.
 *
 * One owner of the row's meaning. The documents, their fields and their first pages arrive as three
 * batched flows, so the cost does not grow with one query per row. Every value comes from the fields
 * the model chose and the code verified (by slot key); no text is searched for keywords. The screens
 * render the result and decide nothing.
 *
 * Seams for later phases: [PartyNameResolver] (an addressee becomes a profile's name) and [ActionHint]
 * (the badge becomes the real open-task count).
 */
class ObserveDocumentListItemsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val partyNames: PartyNameResolver,
    private val actionHint: ActionHint,
    private val clock: Clock,
    private val profileRepository: ProfileRepository,
    private val matchPeople: MatchDocumentPeopleUseCase,
) {

    /**
     * The rows for every document, or for the ones matching [query] when it is not blank. The profiles
     * and the stored links are two more batched flows, so a renamed or added profile updates every row.
     */
    operator fun invoke(query: String = ""): Flow<List<DocumentListItem>> {
        val documents = if (query.isBlank()) documentRepository.getDocuments() else documentRepository.searchDocuments(query)
        return combine(
            documents,
            documentRepository.observeListFields(),
            documentRepository.observeFirstPagePaths(),
            profileRepository.getProfiles(),
            profileRepository.observeDocumentLinks(),
        ) { docs, fields, pages, profiles, links ->
            val today = LocalDate.now(clock)
            val linksByDocument = links.groupBy { it.documentId }
            docs.map { document ->
                item(
                    document, fields[document.id].orEmpty(), pages[document.id], today,
                    people = { parties -> matchPeople(parties, profiles, linksByDocument[document.id].orEmpty()) },
                )
            }
        }
    }

    private fun item(
        document: Document,
        allFields: List<ExtractedData>,
        firstPage: String?,
        today: LocalDate,
        people: (DocumentParties) -> List<PersonTag>,
    ): DocumentListItem {
        val fields = allFields.filterNot { it.deletedByUser }
        val due = firstReadableDate(fields, DUE_SLOTS, UnderstandingToFields.DEADLINE)
        val letterDate = firstReadableDate(fields, listOf(Slots.LETTER_DATE.json), UnderstandingToFields.DOCUMENT_DATE)
        val hasAmountDue = fields.any {
            it.slotKey == Slots.TOTAL.json && it.role.equals(ROLE_TOTAL_DUE, ignoreCase = true)
        }
        val openActions = actionHint.openActions(
            ActionFacts(document.id, document.extractionType, due, hasAmountDue, today),
        )
        return DocumentListItem(
            document = document,
            firstPagePath = firstPage,
            sender = party(fields, DocumentParty.SENDER),
            addressee = party(fields, DocumentParty.ADDRESSEE),
            status = statusOf(document.status, fields),
            dateChip = dateChip(document, due, letterDate, today),
            openActionCount = openActions.coerceAtLeast(0),
            people = people(
                DocumentParties(
                    addressee = PartyFields.addressee(fields)?.fieldValue,
                    subjectPerson = PartyFields.subjectPerson(fields)?.fieldValue,
                ),
            ),
        )
    }

    private fun statusOf(status: DocumentStatus, fields: List<ExtractedData>): DocumentListStatus = when (status) {
        DocumentStatus.NEW, DocumentStatus.QUEUED -> DocumentListStatus.Waiting
        DocumentStatus.PROCESSING -> DocumentListStatus.Processing
        DocumentStatus.FAILED -> DocumentListStatus.Failed
        DocumentStatus.EXTRACTED -> fields.count(::worthChecking).let { count ->
            if (count > 0) DocumentListStatus.NeedsReview(count) else DocumentListStatus.Ready
        }
        DocumentStatus.REVIEWED, DocumentStatus.ARCHIVED -> DocumentListStatus.Ready
    }

    /**
     * A field the detail screen would flag. A faint machine extra is hidden there (see
     * `ExtractedPresenter`), so it is not counted here either.
     */
    private fun worthChecking(field: ExtractedData): Boolean {
        val hidden = field.isExtra && field.source == ValueSource.MACHINE && !field.isConfirmed &&
            field.confidence < ConfidenceCombiner.HIDDEN_BELOW
        return field.needsReview && !hidden
    }

    private fun dateChip(document: Document, due: LocalDate?, letterDate: LocalDate?, today: LocalDate): DocumentDateChip {
        if (due != null) {
            val days = ChronoUnit.DAYS.between(today, due)
            val urgency = when {
                days < 0 -> DocumentDateChip.Urgency.OVERDUE
                days <= SOON_DAYS -> DocumentDateChip.Urgency.SOON
                else -> DocumentDateChip.Urgency.NORMAL
            }
            return DocumentDateChip(DocumentDateChip.Kind.DUE, due, urgency)
        }
        if (letterDate != null) return DocumentDateChip(DocumentDateChip.Kind.LETTER, letterDate)
        val scanned = Instant.ofEpochMilli(document.createdAt).atZone(clock.zone).toLocalDate()
        return DocumentDateChip(DocumentDateChip.Kind.SCANNED, scanned)
    }

    /** The first of [slots] (then a field an older extractor named [legacyName]) that holds a date that can be read. */
    private fun firstReadableDate(fields: List<ExtractedData>, slots: List<String>, legacyName: String): LocalDate? {
        val candidates = slots.flatMap { slot -> fields.filter { it.slotKey == slot } } +
            fields.filter { it.slotKey == null && it.fieldName == legacyName }
        return candidates.firstNotNullOfOrNull { PrintedDateReader.read(it.fieldValue) }
    }

    private fun party(fields: List<ExtractedData>, party: DocumentParty): String? {
        val row = PartyFields.of(party, fields)
        val printed = row?.fieldValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return partyNames.resolve(party, printed).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** A deadline this many days away or fewer is urgent. */
        const val SOON_DAYS = 3L

        /** The role the model gives the amount that is to be paid (see `Slots.TOTAL`). */
        const val ROLE_TOTAL_DUE = "TOTAL_DUE"

        val DUE_SLOTS = listOf(Slots.DUE_DATE.json, Slots.OBJECTION_DEADLINE.json)
    }
}
