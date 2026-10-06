package com.postsaimanager.core.domain.document.people

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the concerned-people question to the loaded model, with the way it is asked as data. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConcernedPeopleModule {

    @Binds
    abstract fun bindConcernedPeople(impl: ModelConcernedPeople): ConcernedPeople

    companion object {
        @Provides
        fun provideConcernedPeopleProfile(): ConcernedPeopleProfile = ConcernedPeopleProfile()
    }
}
