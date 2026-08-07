package com.postsaimanager.core.ai.local.di

import com.postsaimanager.core.ai.local.LocalAiEngine
import com.postsaimanager.core.domain.ai.AiEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the on-device engine to the domain port.
 *
 * Online providers are excluded from this interface by design — cloud escalation is a
 * separate type taking an `ApprovedPayload`, so it can never be substituted here and
 * quietly bypass the consent gate.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalAiModule {

    @Binds
    @Singleton
    abstract fun bindAiEngine(impl: LocalAiEngine): AiEngine
}
