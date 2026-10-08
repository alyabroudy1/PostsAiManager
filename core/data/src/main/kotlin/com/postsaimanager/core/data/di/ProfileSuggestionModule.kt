package com.postsaimanager.core.data.di

import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.dao.ProfileSuggestionDao
import com.postsaimanager.core.data.repository.ProfileSuggestionRepositoryImpl
import com.postsaimanager.core.domain.repository.ProfileSuggestionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The organisation suggestions: their DAO and their repository binding (kept apart from the large data module). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileSuggestionModule {

    @Binds
    @Singleton
    abstract fun bindProfileSuggestionRepository(impl: ProfileSuggestionRepositoryImpl): ProfileSuggestionRepository

    companion object {
        @Provides
        fun provideProfileSuggestionDao(database: PamDatabase): ProfileSuggestionDao = database.profileSuggestionDao()
    }
}
