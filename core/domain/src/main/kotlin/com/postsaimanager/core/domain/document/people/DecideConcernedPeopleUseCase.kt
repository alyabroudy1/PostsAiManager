package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.followup.FollowUpQuestions
import com.postsaimanager.core.domain.document.followup.ReadParties
import com.postsaimanager.core.domain.document.list.PartyFields
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Decides, once per document and over the whole letter, which managed people (Me and the family members) it is for or about, and
 * stores the decision on the document (`Document.concernedProfileIds`: null = not asked yet, empty = asked, nobody). The model
 * decides ([FollowUpQuestions]: a follow-up turn in Gemma's own conversation, or the Qwen scorer); the code only verifies: a person is
 * asked about only when the letter mentions a token of their name ([PartyNames.mentions]), and is kept only when the model named them.
 * When no managed profile passes that pre-filter the answer is empty without asking the model at all. Nothing is stored when no model
 * can answer (the decision stays "not asked yet"), and there is no name-matching fallback.
 */
class DecideConcernedPeopleUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val followUps: FollowUpQuestions,
) {

    /**
     * @param letter the letter's text (the pages' OCR text, in reading order).
     * @return the profile ids now concerned, or the error when the model could not answer (the stored decision is then left as it was).
     */
    suspend operator fun invoke(documentId: String, letter: String): PamResult<Set<String>> {
        val managed = profiles.getProfiles().first().filter { it.isManaged }
        val tokens = PartyNames.tokenSet(letter)
        val candidates = managed.filter { PartyNames.mentions(tokens, it.name) }
        val named = if (candidates.isEmpty()) {
            emptySet()
        } else {
            // What the reading itself decided about the parties: the model's own earlier answer, put back in front of it as evidence.
            val fields = runCatching { documents.observeExtractedData(documentId).first() }.getOrDefault(emptyList())
            val read = ReadParties(PartyFields.sender(fields)?.fieldValue, PartyFields.addressee(fields)?.fieldValue)
            when (val answer = followUps.concernedPeople(documentId, letter, candidates.map { SubjectCandidate(it.id, it.name, it.relationship, it.isSelf) }, read)) {
                is PamResult.Error -> return answer
                is PamResult.Success -> answer.data
            }
        }
        val chosen = candidates.filter { it.id in named }.map { it.id }
        documents.setConcernedProfiles(documentId, chosen)
        return PamResult.Success(chosen.toSet())
    }
}
