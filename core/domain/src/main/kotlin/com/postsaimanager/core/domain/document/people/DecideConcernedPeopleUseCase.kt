package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Decides, once per document and over the whole letter, which managed people (Me and the family members) it is for or about, and
 * stores the decision (a CONCERNS link per person; see [ProfileRepository.replaceConcernedLinks]). The model decides
 * ([ConcernedPeople]); the code only verifies: a person is asked about only when the letter mentions a token of their name
 * ([PartyNames.mentions]), and is kept only when the model named them. Nobody is asked about when no name token is in the letter, and
 * nothing is stored (and no chip appears) when no model can answer: there is no name-matching fallback.
 */
class DecideConcernedPeopleUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val concerned: ConcernedPeople,
) {

    /**
     * @param letter the letter's text (the pages' OCR text, in reading order).
     * @return the profile ids now concerned, or the error when the model could not answer (the stored decision is then left as it was).
     */
    suspend operator fun invoke(documentId: String, letter: String): PamResult<Set<String>> {
        val managed = profiles.getProfiles().first().filter { it.isManaged }
        if (managed.isEmpty()) return PamResult.Success(emptySet())
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
        val chosen = candidates.filter { it.id in named }.map { it.id }.toSet()
        profiles.replaceConcernedLinks(documentId, managed.map { it.id }.toSet(), chosen)
        return PamResult.Success(chosen)
    }
}
