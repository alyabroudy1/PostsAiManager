package com.postsaimanager.core.ai.catalog.di

import com.postsaimanager.core.ai.catalog.CatalogActiveModelProvider
import com.postsaimanager.core.ai.catalog.CatalogInstalledModelsRepository
import com.postsaimanager.core.ai.catalog.DataStoreInferenceSettingsRepository
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
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

    @Binds
    @Singleton
    abstract fun bindInferenceSettingsRepository(
        impl: DataStoreInferenceSettingsRepository,
    ): InferenceSettingsRepository

    @Binds
    @Singleton
    abstract fun bindInstalledModelsRepository(
        impl: CatalogInstalledModelsRepository,
    ): InstalledModelsRepository
}
