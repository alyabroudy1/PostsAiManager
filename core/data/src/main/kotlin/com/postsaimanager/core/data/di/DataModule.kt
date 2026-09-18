package com.postsaimanager.core.data.di

import com.postsaimanager.core.data.repository.ConversationRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentChunkRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentProcessingPipeline
import com.postsaimanager.core.data.repository.DocumentRepositoryImpl
import com.postsaimanager.core.data.repository.EntityProfileLinker
import com.postsaimanager.core.data.repository.ProfileMatcher
import com.postsaimanager.core.data.repository.ProfileRepositoryImpl
import com.postsaimanager.core.data.repository.TimelineRepositoryImpl
import com.postsaimanager.core.data.repository.UserPreferencesRepositoryImpl
import com.postsaimanager.core.data.util.PdfGenerator
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.EntityProposalService
import com.postsaimanager.core.domain.document.ProfileMatchingService
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

    @Binds
    @Singleton
    abstract fun bindProfileMatchingService(impl: ProfileMatcher): ProfileMatchingService

    @Binds
    @Singleton
    abstract fun bindDocumentExporter(impl: PdfGenerator): DocumentExporter

    @Binds
    @Singleton
    abstract fun bindEntityProposalService(impl: EntityProfileLinker): EntityProposalService
}
