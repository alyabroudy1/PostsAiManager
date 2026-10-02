package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.domain.agent.AgentModel
import com.postsaimanager.core.domain.agent.AgentTrace
import com.postsaimanager.core.domain.agent.EngineAgentModel
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.AiEnginePromptFraming
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
import com.postsaimanager.core.domain.form.agent.FieldValueGuard
import com.postsaimanager.core.domain.form.agent.FormAgentTools
import com.postsaimanager.core.domain.form.agent.FormChatLog
import com.postsaimanager.core.domain.form.agent.FormFillAgent
import com.postsaimanager.core.domain.form.agent.FormReader
import com.postsaimanager.core.domain.form.agent.FormToolEnv
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the form assist: the model ports over the standing engine, the understanding steps that take defaults, the form tools
 * and the one [FormFillAgent] (a singleton: its lock serialises the conversation across screens).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FormAssistModule {

    @Binds
    abstract fun bindFormModel(impl: EngineFormModel): FormModel

    @Binds
    abstract fun bindAgentModel(impl: EngineAgentModel): AgentModel

    companion object {
        @Provides
        fun providePromptFraming(engine: AiEngine): PromptFraming = AiEnginePromptFraming(engine)

        @Provides
        fun provideFormFillProfile(): FormFillProfile = FormFillProfile()

        @Provides
        fun provideFillValues(people: PersonDataSource): FillValues = FillValues(people)

        @Provides
        fun provideUnderstandForm(
            session: PromptSession,
            framing: PromptFraming,
            embedder: EmbeddingService,
            trace: FormFillTrace,
        ): UnderstandFormUseCase = UnderstandFormUseCase(session, framing, embedder, trace = trace)

        @Provides
        fun provideFillRequestDetector(model: FormModel, embedder: EmbeddingService, profile: FormFillProfile): FillRequestDetector =
            FillRequestDetector(model, embedder, profile)

        @Provides
        @Singleton
        fun provideFormChatLog(conversations: ConversationRepository, documents: DocumentRepository): FormChatLog =
            FormChatLog(conversations, documents)

        @Provides
        @Singleton
        @Suppress("LongParameterList")
        fun provideFormAgentTools(
            fills: FormFillRepository,
            documents: DocumentRepository,
            profiles: ProfileRepository,
            people: PersonDataSource,
            remember: RememberDetailUseCase,
            fillValues: FillValues,
            understand: UnderstandFormUseCase,
            log: FormChatLog,
            trace: FormFillTrace,
            ocrTrace: FormOcrTrace,
        ): FormAgentTools = FormAgentTools { documentId ->
            FormToolEnv(
                documentId = documentId, fills = fills, profiles = profiles, people = people, guard = FieldValueGuard(people),
                reader = FormReader(fills, documents, profiles, understand, log, trace = trace, ocrTrace = ocrTrace),
                remember = remember, fillValues = fillValues,
            )
        }

        @Provides
        @Singleton
        @Suppress("LongParameterList")
        fun provideFormFillAgent(
            fills: FormFillRepository,
            documents: DocumentRepository,
            log: FormChatLog,
            tools: FormAgentTools,
            model: AgentModel,
            detector: FillRequestDetector,
            activeModels: ActiveModelProvider,
            trace: FormFillTrace,
            agentTrace: AgentTrace,
        ): FormFillAgent = FormFillAgent(fills, documents, log, tools, model, detector, activeModels, trace = trace, agentTrace = agentTrace)
    }
}
