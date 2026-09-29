package com.postsaimanager.core.config

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    /** Lenient so a newer server cannot break an older client. */
    @Provides
    @Singleton
    fun provideConfigJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Small JSON requests only — model bytes go through the catalog's download client. */
    @Provides
    @Singleton
    fun provideConfigHttpClient(): HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
}
