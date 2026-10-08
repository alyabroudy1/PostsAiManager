package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.contacts.SameContact
import com.postsaimanager.core.domain.document.contacts.SameContactProfile
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.document.followup.ScoringFollowUpQuestions
import com.postsaimanager.core.domain.document.people.ConcernedPeople
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.organisation.DetailOwnerProfile
import com.postsaimanager.core.domain.organisation.DetailOwnerQuestion
import com.postsaimanager.core.domain.timeline.SameMatter
import com.postsaimanager.core.domain.timeline.SameMatterProfile
import com.postsaimanager.core.domain.timeline.SameMatterQuestion

private fun noModel(): PamError = PamError.ValidationError("model", "no model")

/** A [SameContact] that cannot answer (no model); what a test that never asks the question passes. */
object NoSameContact : SameContact {
    override suspend fun score(question: SameContactQuestion): PamResult<BaselineScores> = PamResult.Error(noModel())
}

/** A [SameMatter] that cannot answer. */
object NoSameMatter : SameMatter {
    override suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores> = PamResult.Error(noModel())
}

/** A [ConcernedPeople] that cannot answer. */
object NoConcernedPeople : ConcernedPeople {
    override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> = PamResult.Error(noModel())
}

/**
 * The Qwen scorer's [ScoringFollowUpQuestions] over the given scripted scorers, with the ones a test does not care about unable to
 * answer: the way the use cases are built in a test that checks their decision, margins included.
 */
fun scoringFollowUps(
    concerned: ConcernedPeople = NoConcernedPeople,
    sameContact: SameContact = NoSameContact,
    detailOwner: DetailOwnerQuestion = FakeDetailOwnerQuestion(),
    sameMatter: SameMatter = NoSameMatter,
    sameContactProfile: SameContactProfile = SameContactProfile(),
    detailOwnerProfile: DetailOwnerProfile = DetailOwnerProfile(),
    sameMatterProfile: SameMatterProfile = SameMatterProfile(),
): ScoringFollowUpQuestions = ScoringFollowUpQuestions(
    concerned, sameContact, sameContactProfile, detailOwner, detailOwnerProfile, sameMatter, sameMatterProfile,
)
