package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.AiEnginePromptFraming
import com.postsaimanager.core.domain.form.FillValues
import com.postsaimanager.core.domain.form.GuardiansOfUseCase
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.PromptFraming
import com.postsaimanager.core.domain.form.RememberDetailUseCase
import com.postsaimanager.core.domain.form.UnderstandFormUseCase
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
 * Wires the form assist's domain classes: the model port over the standing engine, the steps that take defaults, and the one
 * [FormFillConversation] (a singleton: its lock serialises the conversation across screens).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FormAssistModule {

    @Binds
    abstract fun bindFormModel(impl: EngineFormModel): FormModel

    companion object {
        @Provides
        fun providePromptFraming(engine: AiEngine): PromptFraming = AiEnginePromptFraming(engine)

        @Provides
        fun provideFormFillProfile(): FormFillProfile = FormFillProfile()

        @Provides
        fun provideFillValues(people: PersonDataSource): FillValues = FillValues(people)

        @Provides
        fun provideUnderstandForm(session: PromptSession, framing: PromptFraming, embedder: EmbeddingService): UnderstandFormUseCase =
            UnderstandFormUseCase(session, framing, embedder)

        @Provides
        fun provideAnswerChips(people: PersonDataSource, profile: FormFillProfile): AnswerChips = AnswerChips(people, profile)

        @Provides
        fun provideAnswerInterpreter(model: FormModel, profile: FormFillProfile): AnswerInterpreter = AnswerInterpreter(model, profile)

        @Provides
        fun provideQuestionWriter(model: FormModel, profile: FormFillProfile): FormQuestionWriter =
            FormQuestionWriter(model, profile = profile)

        @Provides
        fun provideIntentClassifier(model: FormModel, profile: FormFillProfile): FormIntentClassifier = FormIntentClassifier(model, profile)

        @Provides
        fun provideFillRequestDetector(model: FormModel, embedder: EmbeddingService, profile: FormFillProfile): FillRequestDetector =
            FillRequestDetector(model, embedder, profile)

        @Provides
        @Singleton
        @Suppress("LongParameterList")
        fun provideConversation(
            fills: FormFillRepository,
            conversations: ConversationRepository,
            documents: DocumentRepository,
            profiles: ProfileRepository,
            people: PersonDataSource,
            guardiansOf: GuardiansOfUseCase,
            remember: RememberDetailUseCase,
            understand: UnderstandFormUseCase,
            model: FormModel,
            classifier: FormIntentClassifier,
            detector: FillRequestDetector,
            interpreter: AnswerInterpreter,
            writer: FormQuestionWriter,
            chips: AnswerChips,
            fillValues: FillValues,
            profile: FormFillProfile,
        ): FormFillConversation = FormFillConversation(
            fills, conversations, documents, profiles, people, guardiansOf, remember, understand, model, classifier, detector,
            interpreter, writer, chips, fillValues, profile,
        )
    }
}
