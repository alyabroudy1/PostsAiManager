package com.postsaimanager.core.ai.embed.di

import com.postsaimanager.core.ai.embed.LazyEmbeddingService
import com.postsaimanager.core.domain.ai.EmbeddingService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the embedding port to its ONNX implementation.
 *
 * [LazyEmbeddingService], not `OnnxEmbeddingService` directly: the raw service reports
 * `isReady == false` until someone has explicitly called `load`, and nothing in the app
 * would ever be the one to do that. The lazy wrapper is what makes the port work by simply
 * being injected.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingService(impl: LazyEmbeddingService): EmbeddingService
}
