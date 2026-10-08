package com.postsaimanager.core.domain.document.followup

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.document.contacts.SameContactDecision
import com.postsaimanager.core.domain.document.contacts.SameContactQuestion
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.organisation.DetailOwner
import com.postsaimanager.core.domain.organisation.DetailQuestion
import com.postsaimanager.core.domain.timeline.SameMatterDecision
import com.postsaimanager.core.domain.timeline.SameMatterQuestion
import com.postsaimanager.core.model.ModelRuntime
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider

/**
 * The [FollowUpQuestions] the app uses: the reader that reads the letters answers the questions about them. Gemma, when it is the
 * chosen reader ([GemmaReaderTrial], on by default) and the chat model is a LiteRT-LM one: its follow-up turns, and never the Qwen
 * session (it is not even created, so the llama.cpp model is neither loaded nor does it evict Gemma). The Qwen scorer only when the
 * old reader is chosen or no Gemma model is the chat model.
 *
 * A question that could not be answered is reported once, with the tag `AfterReading` ([AfterReadingLog]), and returned as the error
 * it is: the caller leaves the decision pending.
 */
class RoutingFollowUpQuestions @Inject constructor(
    private val trial: GemmaReaderTrial,
    private val activeModel: ActiveModelProvider,
    private val gemma: Provider<GemmaFollowUpQuestions>,
    private val scoring: Provider<ScoringFollowUpQuestions>,
    private val log: AfterReadingLog,
) : FollowUpQuestions {

    override suspend fun concernedPeople(documentId: String, letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> =
        reported(documentId, "concerned people") { it.concernedPeople(documentId, letter, members) }

    override suspend fun sameContact(documentId: String, question: SameContactQuestion): PamResult<SameContactDecision> =
        reported(documentId, "same contact") { it.sameContact(documentId, question) }

    override suspend fun detailOwner(documentId: String, question: DetailQuestion): PamResult<DetailOwner> =
        reported(documentId, "detail owner") { it.detailOwner(documentId, question) }

    override suspend fun sameMatter(documentId: String, question: SameMatterQuestion): PamResult<SameMatterDecision> =
        reported(documentId, "same matter") { it.sameMatter(documentId, question) }

    override suspend fun finish(documentId: String) {
        if (gemmaIsReader()) gemma.get().finish(documentId)
    }

    private suspend fun <T> reported(documentId: String, question: String, ask: suspend (FollowUpQuestions) -> PamResult<T>): PamResult<T> {
        val answer = ask(if (gemmaIsReader()) gemma.get() else scoring.get())
        if (answer is PamResult.Error) log.pending(documentId, question, answer.error.toString())
        return answer
    }

    /** True when Gemma is the reader of the letters and the chat model is one it can run on. */
    private suspend fun gemmaIsReader(): Boolean =
        trial.isEnabled() && activeModel.activeModelPath() != null && activeModel.activeModelConfig().runtime == ModelRuntime.LITERT_LM
}

/** Binds the after-reading questions to the reader that is in use. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FollowUpModule {

    @Binds
    abstract fun bindFollowUpQuestions(impl: RoutingFollowUpQuestions): FollowUpQuestions
}
