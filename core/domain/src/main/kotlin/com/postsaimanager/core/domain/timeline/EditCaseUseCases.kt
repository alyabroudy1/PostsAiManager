package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.CaseTitleSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

/** Where the user puts a letter: into a matter that exists, into a new one, or into none. */
sealed interface CaseTarget {
    data class Existing(val caseId: String) : CaseTarget

    /** A new matter of the letter's sender, named [title] (blank: the letter's own event title). */
    data class New(val title: String? = null) : CaseTarget

    data object None : CaseTarget
}

/** The matters a letter can be moved to: those of its sender organisation, and the one it is in now. */
data class CaseChoices(val currentCaseId: String?, val cases: List<Case>)

/** The choices of "Move to another matter" for a letter: empty while the letter has no event, so no sender, to belong to. */
class ObserveCaseChoicesUseCase @Inject constructor(private val events: EventRepository) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(documentId: String): Flow<CaseChoices?> =
        events.observeEventsForDocument(documentId).flatMapLatest { own ->
            val organisationId = own.firstNotNullOfOrNull { it.organisationProfileId } ?: return@flatMapLatest flowOf(null)
            val current = own.firstNotNullOfOrNull { it.caseId }
            events.observeCasesForOrganisation(organisationId).map { CaseChoices(current, it) }
        }
}

/**
 * The user moves a letter to another matter, to a new one, or to none. Every event of the letter follows, the choice is stored as theirs
 * (so reading the letter again does not regroup it, see `RecordDocumentEventsUseCase`), and a matter left with no event is deleted while
 * the statuses of the matters touched are brought in line with their events (a status the user set stays, [CaseStatusPolicy]).
 */
class MoveDocumentToCaseUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val events: EventRepository,
    private val refresh: RefreshCaseStatusUseCase,
    private val clock: Clock,
) {

    suspend operator fun invoke(documentId: String, target: CaseTarget): PamResult<Unit> {
        val own = events.eventsOfDocument(documentId)
        val first = own.firstOrNull() ?: return PamResult.Error(PamError.ValidationError("case", "the letter has no event to put in a matter"))
        val before = own.mapNotNull { it.caseId }.distinct()
        val caseId: String? = when (target) {
            is CaseTarget.Existing -> (events.getCase(target.caseId) ?: return PamResult.Error(PamError.ValidationError("case", "no such matter"))).id
            CaseTarget.None -> null
            is CaseTarget.New -> {
                val organisationId = own.firstNotNullOfOrNull { it.organisationProfileId }
                    ?: return PamResult.Error(PamError.ValidationError("case", "a matter needs the letter's sender"))
                val typed = target.title?.trim()?.takeIf { it.isNotEmpty() }
                val keys = ReferenceKeys.of(documents.observeExtractedData(documentId).first())
                val created = Case(
                    id = UuidGenerator.generate(), organisationProfileId = organisationId, title = typed ?: first.title, referenceKeys = keys,
                    createdAt = clock.millis(), titleSource = if (typed != null) CaseTitleSource.USER else CaseTitleSource.AUTO,
                )
                events.saveCase(created)
                created.id
            }
        }
        events.setDocumentCase(documentId, caseId)
        documents.markCaseChosenByUser(documentId)
        (before + listOfNotNull(caseId)).distinct().forEach { refresh(it) }
        return PamResult.Success(Unit)
    }
}

/**
 * The user sets a matter's status by hand (open, approved, rejected, closed), or hands it back to the events (null: automatic). A status
 * they set is stored with a USER source and no derived status replaces it until they reset it.
 */
class SetCaseStatusUseCase @Inject constructor(private val events: EventRepository) {

    suspend operator fun invoke(caseId: String, status: CaseStatus?) {
        if (status != null) {
            events.setCaseStatusByUser(caseId, status)
        } else {
            events.setCaseStatusAutomatic(caseId, CaseStatusDeriver.derive(events.eventsOfCase(caseId)))
        }
    }
}
