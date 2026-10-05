package com.postsaimanager.core.ai.local.di

import com.postsaimanager.core.ai.local.RemoteAiEngine
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.v2.InterpreterFactory
import com.postsaimanager.core.domain.extraction.zones.ProfileInterpreterFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the on-device engine to the domain ports.
 *
 * Online providers are excluded from this interface by design — cloud escalation is a
 * separate type taking an `ApprovedPayload`, so it can never be substituted here and
 * quietly bypass the consent gate.
 *
 * [PromptSession] (read once, ask many short questions) is a second, separate port over the same
 * engine instance: it shares [RemoteAiEngine]'s mutex and native context, and [AiEngine] does not grow.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalAiModule {

    @Binds
    @Singleton
    abstract fun bindAiEngine(impl: RemoteAiEngine): AiEngine

    @Binds
    @Singleton
    abstract fun bindPromptSession(impl: RemoteAiEngine): PromptSession

    @Binds
    abstract fun bindInterpreterFactory(impl: ProfileInterpreterFactory): InterpreterFactory
}
