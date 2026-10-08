package com.postsaimanager.core.data.di

import com.postsaimanager.core.data.gemma.GemmaTrialReader
import com.postsaimanager.core.data.gemma.MlKitEntityAnnotator
import com.postsaimanager.core.data.gemma.SharedPreferencesGemmaReaderTrial
import com.postsaimanager.core.domain.extraction.gemma.ChatEngineGemmaReader
import com.postsaimanager.core.domain.extraction.gemma.EntityAnnotator
import com.postsaimanager.core.domain.extraction.gemma.GemmaDocumentReader
import com.postsaimanager.core.domain.extraction.gemma.ChatEngineGemmaTextGenerator
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import com.postsaimanager.core.domain.extraction.gemma.GemmaTextGenerator
import com.postsaimanager.core.domain.extraction.gemma.GemmaTrialReading
import com.postsaimanager.core.data.repository.ContactRepositoryImpl
import com.postsaimanager.core.data.repository.EventRepositoryImpl
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.data.repository.ConversationRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentChunkRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentProcessingPipeline
import com.postsaimanager.core.data.repository.DocumentRepositoryImpl
import com.postsaimanager.core.data.repository.FormFillRepositoryImpl
import com.postsaimanager.core.data.repository.PersonDataSourceImpl
import com.postsaimanager.core.domain.repository.FormFillRepository
import com.postsaimanager.core.data.repository.DocumentNoteRepositoryImpl
import com.postsaimanager.core.data.repository.ProfileFactRepositoryImpl
import com.postsaimanager.core.data.repository.ProfileRepositoryImpl
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.data.repository.TimelineRepositoryImpl
import com.postsaimanager.core.data.repository.UserPreferencesRepositoryImpl
import com.postsaimanager.core.data.skills.WorkManagerReminderScheduler
import com.postsaimanager.core.data.util.FileChatImageStore
import com.postsaimanager.core.data.util.PdfGenerator
import com.postsaimanager.core.domain.ai.ChatImageStore
import com.postsaimanager.core.domain.skills.ReminderScheduler
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds
    @Singleton
    abstract fun bindProfileFactRepository(impl: ProfileFactRepositoryImpl): ProfileFactRepository

    @Binds
    @Singleton
    abstract fun bindDocumentNoteRepository(impl: DocumentNoteRepositoryImpl): DocumentNoteRepository

    @Binds
    @Singleton
    abstract fun bindPersonDataSource(impl: PersonDataSourceImpl): PersonDataSource

    @Binds
    @Singleton
    abstract fun bindFormFillRepository(impl: FormFillRepositoryImpl): FormFillRepository

    @Binds
    @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    @Binds
    @Singleton
    abstract fun bindTimelineRepository(impl: TimelineRepositoryImpl): TimelineRepository

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(impl: UserPreferencesRepositoryImpl): UserPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindDocumentChunkRepository(
        impl: DocumentChunkRepositoryImpl,
    ): DocumentChunkRepository

    // ── Ports for feature/documents (task 7.15.2) — features may see only :core:domain, so
    // each mechanism below is bound to the interface declared there. ──

    @Binds
    @Singleton
    abstract fun bindDocumentProcessor(impl: DocumentProcessingPipeline): DocumentProcessor

    /** The notes of an ended chat session wait in WorkManager until the chat model is idle. */
    @Binds
    @Singleton
    abstract fun bindSessionNotesQueue(
        impl: com.postsaimanager.core.data.worker.WorkManagerSessionNotesQueue,
    ): com.postsaimanager.core.domain.memory.SessionNotesQueue

    @Binds
    @Singleton
    abstract fun bindDocumentExporter(impl: PdfGenerator): DocumentExporter

    // ── Importing PDFs and images as documents ──

    @Binds
    @Singleton
    abstract fun bindPageImageSource(impl: com.postsaimanager.core.data.importing.AndroidPageImageSource): com.postsaimanager.core.domain.importing.PageImageSource

    @Binds
    @Singleton
    abstract fun bindImportQueue(impl: com.postsaimanager.core.data.importing.WorkManagerImportQueue): com.postsaimanager.core.domain.importing.ImportQueue

    @Binds
    @Singleton
    abstract fun bindImportRequestJournal(impl: com.postsaimanager.core.data.importing.ImportRequestStore): com.postsaimanager.core.domain.importing.ImportRequestJournal

    // ── The app's one reminder scheduler (the bundled skills are bound in the app's SkillModule) ──

    @Binds
    @Singleton
    abstract fun bindChatImageStore(impl: FileChatImageStore): ChatImageStore

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: WorkManagerReminderScheduler): ReminderScheduler

    @Binds
    @Singleton
    abstract fun bindReadingFinishedNotifier(
        impl: com.postsaimanager.core.data.worker.ReadingFinishedNotificationCenter,
    ): com.postsaimanager.core.domain.reading.ReadingFinishedNotifier

    // ── "Gemma reads the letter" (the trial; off by default) ──

    @Binds
    @Singleton
    abstract fun bindGemmaReaderTrial(impl: SharedPreferencesGemmaReaderTrial): GemmaReaderTrial

    @Binds
    @Singleton
    abstract fun bindGemmaTrialReading(impl: GemmaTrialReader): GemmaTrialReading

    @Binds
    @Singleton
    abstract fun bindEntityAnnotator(impl: MlKitEntityAnnotator): EntityAnnotator

    @Binds
    @Singleton
    abstract fun bindGemmaDocumentReader(impl: ChatEngineGemmaReader): GemmaDocumentReader

    @Binds
    @Singleton
    abstract fun bindGemmaTextGenerator(impl: ChatEngineGemmaTextGenerator): GemmaTextGenerator
}
