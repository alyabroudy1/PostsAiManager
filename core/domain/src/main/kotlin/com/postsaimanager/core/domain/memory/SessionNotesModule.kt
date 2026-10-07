package com.postsaimanager.core.domain.memory

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the session-end note generation to the chat engine. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionNotesModule {

    @Binds
    abstract fun bindSessionNoteGenerator(impl: ChatEngineSessionNoteGenerator): SessionNoteGenerator
}
