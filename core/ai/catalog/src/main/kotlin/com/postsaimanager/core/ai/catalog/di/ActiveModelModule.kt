package com.postsaimanager.core.ai.catalog.di

import com.postsaimanager.core.ai.catalog.CatalogActiveModelProvider
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ActiveModelModule {

    @Binds
    @Singleton
    abstract fun bindActiveModelProvider(impl: CatalogActiveModelProvider): ActiveModelProvider
}
