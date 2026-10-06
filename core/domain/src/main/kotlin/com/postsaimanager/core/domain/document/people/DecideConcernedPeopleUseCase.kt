package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Decides, once per document and over the whole letter, which managed people (Me and the family members) it is for or about, and
 * stores the decision on the document (`Document.concernedProfileIds`: null = not asked yet, empty = asked, nobody). The model
 * decides ([ConcernedPeople]); the code only verifies: a person is asked about only when the letter mentions a token of their name
 * ([PartyNames.mentions]), and is kept only when the model named them. When no managed profile passes that pre-filter the answer is
 * empty without asking the model at all. Nothing is stored when no model can answer, and there is no name-matching fallback.
 *
 * Follow-up: the question runs in its own prompt session, so it pays one more read of the letter (about 10 to 18 s in the
 * background on the phone). Asked inside the reading's own body session (the letter already decoded) it would cost about 1 to 3 s.
 */
class DecideConcernedPeopleUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val concerned: ConcernedPeople,
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
            when (val answer = concerned.decide(letter, candidates.map { SubjectCandidate(it.id, it.name, it.relationship, it.isSelf) })) {
                is PamResult.Error -> return answer
                is PamResult.Success -> answer.data
            }
        }
        val chosen = candidates.filter { it.id in named }.map { it.id }
        documents.setConcernedProfiles(documentId, chosen)
        return PamResult.Success(chosen.toSet())
    }
}
