package com.postsaimanager.core.ai.local.di

import com.postsaimanager.core.ai.local.InferenceChatActivityGate
import com.postsaimanager.core.ai.local.RemoteAiEngine
import com.postsaimanager.core.ai.local.RemoteLiteRtChatEngine
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.ai.RoutingChatEngine
import com.postsaimanager.core.domain.extraction.v2.InterpreterFactory
import com.postsaimanager.core.domain.extraction.zones.ProfileInterpreterFactory
import com.postsaimanager.core.model.ModelRuntime
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the on-device engines to the domain ports.
 *
 * Online providers are excluded from this interface by design — cloud escalation is a
 * separate type taking an `ApprovedPayload`, so it can never be substituted here and
 * quietly bypass the consent gate.
 *
 * [PromptSession] (read once, ask many short questions) is a second, separate port over the same
 * engine instance: it shares [RemoteAiEngine]'s mutex and native context, and [AiEngine] does not grow.
 *
 * [ChatEngine] is the port chat talks to: a router over one engine per runtime (llama.cpp's [RemoteAiEngine] and
 * LiteRT-LM's [RemoteLiteRtChatEngine]) that picks the engine from the chat model's descriptor. Reading documents keeps
 * asking for [AiEngine], which only llama.cpp implements.
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
    abstract fun bindChatActivityGate(impl: InferenceChatActivityGate): ChatActivityGate

    @Binds
    abstract fun bindInterpreterFactory(impl: ProfileInterpreterFactory): InterpreterFactory

    companion object {
        @Provides
        @Singleton
        fun provideChatEngine(llamaCpp: RemoteAiEngine, liteRtLm: RemoteLiteRtChatEngine): ChatEngine =
            RoutingChatEngine(
                mapOf(
                    ModelRuntime.LLAMA_CPP to llamaCpp,
                    ModelRuntime.LITERT_LM to liteRtLm,
                ),
            )
    }
}
