package com.postsaimanager.core.ai.di

import android.content.Context
import com.postsaimanager.core.ai.engine.NativeArabicTtsEngine
import com.postsaimanager.core.ai.tools.TtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /**
     * Document read-aloud via Android's system TTS.
     *
     * The Arabic syntax engine (`OnnxArabicSyntaxEngine`) and the Kokoro TTS engine
     * were removed 2026-08-07 — see documentation/02-architecture.md §9.
     * ONNX Runtime is retained and will be retargeted at embeddings for retrieval
     * in Phase 7.7.
     */
    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine {
        return NativeArabicTtsEngine(context)
    }
}
