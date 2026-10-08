package com.postsaimanager.core.domain.document.followup

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.contacts.CandidateScore
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactDecision
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.document.people.ConcernedPeople
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.organisation.DetailOwner
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.DetailOwnerQuestion
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.timeline.SameMatter
import com.postsaimanager.core.domain.timeline.SameMatterDecision
import com.postsaimanager.core.domain.timeline.SameMatterProfile
import com.postsaimanager.core.domain.timeline.SameMatterQuestion
import javax.inject.Inject

/**
 * [FollowUpQuestions] for the old reader (the Qwen scorer): each question is a set of yes/no statements scored on the llama.cpp prompt
 * session ([SameContact], [DetailOwnerQuestion], [SameMatter], [ConcernedPeople]), and a candidate counts only when its score beats a
 * made-up distractor's by the margin of its profile (a small model leans Yes on everything, so only the distance to the baseline says
 * anything). Used only when Gemma is not the reader: under Gemma these scorers are never touched, so no Qwen session is opened.
 */
class ScoringFollowUpQuestions @Inject constructor(
    private val concerned: ConcernedPeople,
    private val sameContactScores: SameContact,
    private val sameContactProfile: SameContactProfile,
    private val detailOwnerScores: DetailOwnerQuestion,
    private val detailOwnerProfile: DetailOwnerProfile,
    private val sameMatterScores: SameMatter,
    private val sameMatterProfile: SameMatterProfile,
) : FollowUpQuestions {

    override suspend fun concernedPeople(documentId: String, letter: String, members: List<SubjectCandidate>, read: ReadParties): PamResult<Set<String>> =
        concerned.decide(letter, members)

    override suspend fun sameContact(documentId: String, question: SameContactQuestion): PamResult<SameContactDecision> {
        val scores = when (val answer = sameContactScores.score(question)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val best = scores.beating(sameContactProfile.margin).maxByOrNull { scores.candidates[it] }
        return PamResult.Success(
            SameContactDecision(
                matchedId = best?.let { question.candidates[it].id },
                asked = question.candidates.mapIndexed { i, c -> CandidateScore(c.id, scores.candidates[i]) },
                baseline = scores.baseline,
                margin = sameContactProfile.margin,
            ),
        )
    }

    override suspend fun detailOwner(documentId: String, question: DetailQuestion): PamResult<DetailOwner> {
        val organisationBeats = when (val answer = detailOwnerScores.scoreOrganisation(question)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data.beating(detailOwnerProfile.margin).isNotEmpty()
        }
        val contactBeats = question.contactCanOwn && when (val answer = detailOwnerScores.scoreContact(question)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data.beating(detailOwnerProfile.margin).isNotEmpty()
        }
        return PamResult.Success(
            when {
                contactBeats -> DetailOwner.CONTACT
                organisationBeats -> DetailOwner.ORGANISATION
                else -> DetailOwner.NEITHER
            },
        )
    }

    override suspend fun sameMatter(documentId: String, question: SameMatterQuestion): PamResult<SameMatterDecision> {
        val scores = when (val answer = sameMatterScores.score(question)) {
            is PamResult.Error -> return answer
            is PamResult.Success -> answer.data
        }
        val best = scores.beating(sameMatterProfile.margin).maxByOrNull { scores.candidates[it] }
        return PamResult.Success(
            SameMatterDecision(
                matchedId = best?.let { question.candidates[it].id },
                asked = question.candidates.mapIndexed { i, c -> c.id to scores.candidates[i] },
                baseline = scores.baseline,
                margin = sameMatterProfile.margin,
            ),
        )
    }
}
