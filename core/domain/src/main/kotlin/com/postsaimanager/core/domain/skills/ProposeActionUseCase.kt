package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject

/** An action the model proposed, with the verdict of the grounding checks on each of its values. Shown on a card; fires on Open. */
data class ProposedAction(
    val action: AgentAction,
    /** The letter the chat is about, if any. */
    val documentId: String?,
    val checks: Map<ActionField, FieldCheck>,
)

/** Gathers what an action's values are checked against: the letter, its verified fields, the user's profile and their own words. */
class LoadGroundingSourcesUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val profiles: ProfileRepository,
    private val profileFacts: ProfileFactRepository,
) {
    suspend operator fun invoke(documentId: String?, userMessages: List<String>): GroundingSources {
        val letter = documentId?.let { id ->
            (documents.getDocumentPages(id) as? PamResult.Success)?.data.orEmpty().sortedBy { it.pageNumber }.mapNotNull { it.ocrText }.joinToString("\n")
        }.orEmpty()
        val verified = documentId?.let { id ->
            documents.observeExtractedData(id).first().filterNot { it.deletedByUser }.map { it.fieldValue }
        }.orEmpty()
        val people = profiles.getProfiles().first()
        val profileValues = people.mapNotNull { it.email } +
            people.flatMap { person -> profileFacts.facts(person.id).filterNot { it.sensitive }.map { it.value } }
        return GroundingSources(letterText = letter, verifiedValues = verified, profileValues = profileValues, userMessages = userMessages)
    }
}

/**
 * Step one of acting: checks the values the model chose against the sources (code verifies, the AI decides) and returns the
 * proposal for a card. It runs nothing and changes no value: a value that is not found stays as it is, flagged.
 */
class ProposeActionUseCase @Inject constructor(
    private val loadSources: LoadGroundingSourcesUseCase,
) {
    suspend operator fun invoke(
        action: AgentAction,
        documentId: String?,
        userMessages: List<String>,
        now: LocalDateTime,
    ): ProposedAction {
        val sources = loadSources(documentId, userMessages)
        return ProposedAction(action = action, documentId = documentId, checks = ActionGrounding.check(action, sources, now))
    }
}
