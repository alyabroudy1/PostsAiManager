package com.postsaimanager.core.domain.document.followup

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.contacts.SameContactDecision
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.organisation.DetailOwner
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.timeline.SameMatterDecision
import com.postsaimanager.core.domain.timeline.SameMatterQuestion

/**
 * The questions asked of the model after a letter is read: the one place the four of them reach it. A port so the decisions
 * ([com.postsaimanager.core.domain.document.people.DecideConcernedPeopleUseCase], [com.postsaimanager.core.domain.document.contacts.DecideSameContactUseCase],
 * [com.postsaimanager.core.domain.organisation.DecideDetailOwnerUseCase], [com.postsaimanager.core.domain.timeline.DecideSameMatterUseCase])
 * are testable without a model, and so the reader that read the letter is the one that answers.
 *
 * Two implementations: [GemmaFollowUpQuestions] asks each as a constrained follow-up turn in the Gemma reader's own conversation (the
 * letter is already read), and [ScoringFollowUpQuestions] scores yes/no statements on the Qwen prompt session (the old reader). The
 * use cases narrow the candidates and apply what the answer means; the model decides, code only verifies.
 *
 * Every method is an error (nothing decided) when no model can answer: the caller leaves the decision pending and never reads it as
 * "no match". [documentId] names the letter the question is about (it is the key of the reader's open conversation).
 */
interface FollowUpQuestions {

    /**
     * Which of [members] the letter is for or about, as profile ids; empty when it is about none of them. [members] are the people
     * whose name the letter mentions, never everybody; the answer is always a subset of them.
     */
    suspend fun concernedPeople(documentId: String, letter: String, members: List<SubjectCandidate>): PamResult<Set<String>>

    /** Whether the contact in [question] is one of its candidates (the matched id), or a new person. */
    suspend fun sameContact(documentId: String, question: SameContactQuestion): PamResult<SameContactDecision>

    /** Whose the value of [question] is. */
    suspend fun detailOwner(documentId: String, question: DetailQuestion): PamResult<DetailOwner>

    /** Whether the new letter belongs to one of the candidate matters (the matched id), or starts a new one. */
    suspend fun sameMatter(documentId: String, question: SameMatterQuestion): PamResult<SameMatterDecision>

    /** The questions about [documentId] are done: the reader's conversation, kept open for them, is closed. A no-op when there is none. */
    suspend fun finish(documentId: String) {}

    companion object {
        /** Answers nothing (every question an error): for a caller that only ever calls [finish], such as a test that reads no letters. */
        val NONE: FollowUpQuestions = object : FollowUpQuestions {
            private fun <T> none(): PamResult<T> = PamResult.Error(PamError.ExtractionFailed(detail = "No follow-up questions"))

            override suspend fun concernedPeople(documentId: String, letter: String, members: List<SubjectCandidate>) = none<Set<String>>()

            override suspend fun sameContact(documentId: String, question: SameContactQuestion) = none<SameContactDecision>()

            override suspend fun detailOwner(documentId: String, question: DetailQuestion) = none<DetailOwner>()

            override suspend fun sameMatter(documentId: String, question: SameMatterQuestion) = none<SameMatterDecision>()
        }
    }
}

/**
 * Where a question that could not be answered is reported (tag `AfterReading`): the decision stays pending and is asked again on the
 * next reading, never turned into "no contact" or "no matter". Ids and reasons only, never a word of the letter.
 */
interface AfterReadingLog {

    fun pending(documentId: String, question: String, reason: String)
}
