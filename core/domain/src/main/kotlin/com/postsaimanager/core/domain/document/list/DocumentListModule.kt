package com.postsaimanager.core.domain.document.list

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/** Today's bindings for the list row's seams; a later phase swaps the two implementations here and nowhere else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentListModule {

    @Binds
    abstract fun bindPartyNameResolver(impl: IdentityPartyNameResolver): PartyNameResolver

    @Binds
    abstract fun bindActionHint(impl: DueFieldsActionHint): ActionHint

    companion object {
        @Provides
        fun provideClock(): Clock = Clock.systemDefaultZone()
    }
}
