package com.postsaimanager.core.data.di

import com.postsaimanager.core.data.repository.ConversationRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentChunkRepositoryImpl
import com.postsaimanager.core.data.repository.DocumentRepositoryImpl
import com.postsaimanager.core.data.repository.ProfileRepositoryImpl
import com.postsaimanager.core.data.repository.TimelineRepositoryImpl
import com.postsaimanager.core.data.repository.UserPreferencesRepositoryImpl
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
}
