package com.postsaimanager.core.domain.document.contacts

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the same-contact question to the loaded model, with the way it is asked as data. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SameContactModule {

    @Binds
    abstract fun bindSameContact(impl: ModelSameContact): SameContact

    companion object {
        @Provides
        fun provideSameContactProfile(): SameContactProfile = SameContactProfile()
    }
}
