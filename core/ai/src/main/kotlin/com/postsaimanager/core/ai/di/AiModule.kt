package com.postsaimanager.core.ai.di

import com.postsaimanager.core.ai.engine.OnnxArabicSyntaxEngine
import com.postsaimanager.core.ai.engine.NativeArabicTtsEngine
import com.postsaimanager.core.ai.tools.TtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideOnnxArabicSyntaxEngine(): OnnxArabicSyntaxEngine {
        return OnnxArabicSyntaxEngine()
    }

    @Provides
    @Singleton
    fun provideTtsEngine(@ApplicationContext context: Context): TtsEngine {
        // Uses Android native TTS for Arabic, with Kokoro fallback for English
        return NativeArabicTtsEngine(context)
    }
}

