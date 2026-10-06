package com.postsaimanager.core.domain.document.list

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** Today's binding for the list row's seam; a later phase swaps the implementation here and nowhere else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentListModule {

    @Binds
    abstract fun bindPartyNameResolver(impl: IdentityPartyNameResolver): PartyNameResolver

    companion object {
        @Provides
        fun provideClock(): Clock = Clock.systemDefaultZone()
    }
}
