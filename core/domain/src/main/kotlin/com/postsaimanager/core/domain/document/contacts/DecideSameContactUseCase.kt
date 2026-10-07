package com.postsaimanager.core.domain.document.contacts

import com.postsaimanager.core.common.result.PamResult
import javax.inject.Inject

/** One asked candidate and its score (log-odds of Yes against No), for the debug log. */
data class CandidateScore(val candidateId: String, val score: Double)

/**
 * The decision: [matchedId] is the existing contact the letter's contact is, or null for a new person. [asked] and [baseline] are what
 * the model scored (empty and null when nothing was asked), [margin] what a candidate had to beat the baseline by.
 */
data class SameContactDecision(
    val matchedId: String?,
    val asked: List<CandidateScore>,
    val baseline: Double?,
    val margin: Double,
) {
    val isNewPerson: Boolean get() = matchedId == null
}

/**
 * Decides whether a contact person read from a letter is the same person as an existing contact of that organisation. The model decides
 * ([SameContact]); the code only verifies: a name pre-filter ([ContactNameFilter]) narrows the candidates and never decides, each one
 * left is scored against a made-up distractor, and a candidate wins only when it beats the distractor by the margin, the best one
 * winning. Nothing left to ask, or nobody beating the margin, is a new person. An error (nothing decided) when no model can answer.
 */
class DecideSameContactUseCase @Inject constructor(
    private val sameContact: SameContact,
    private val profile: SameContactProfile,
) {

    /**
     * @param contact the contact as read from the letter.
     * @param organisation the organisation's name.
     * @param excerpt a short excerpt of the letter around the contact, if there is one.
     * @param candidates the existing contacts of that organisation.
     */
    suspend operator fun invoke(
        contact: ReadContact,
        organisation: String,
        excerpt: String?,
        candidates: List<ContactCandidate>,
    ): PamResult<SameContactDecision> {
        val narrowed = candidates
            .filter { ContactNameFilter.worthAsking(contact.name, it.name) }
            .sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE }
            .take(profile.maxCandidates)
        if (narrowed.isEmpty()) return PamResult.Success(SameContactDecision(null, emptyList(), null, profile.margin))
        val scores = when (val answer = sameContact.score(SameContactQuestion(contact, organisation, excerpt, narrowed))) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val best = scores.beating(profile.margin).maxByOrNull { scores.candidates[it] }
        return PamResult.Success(
            SameContactDecision(
                matchedId = best?.let { narrowed[it].id },
                asked = narrowed.mapIndexed { i, c -> CandidateScore(c.id, scores.candidates[i]) },
                baseline = scores.baseline,
                margin = profile.margin,
            ),
        )
    }
}
