package com.postsaimanager.core.ai.catalog.di

import com.postsaimanager.core.ai.catalog.download.ModelDownloader
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes the large-file download client from any future API client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    /**
     * HTTP client tuned for multi-gigabyte transfers.
     *
     * `socketTimeoutMillis` is deliberately generous and `requestTimeoutMillis` disabled:
     * a whole-request timeout would abort a large download on a slow connection even while
     * bytes are still arriving. Stalls are caught by the socket timeout instead.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadHttpClient(): HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            // requestTimeoutMillis is deliberately left unset (= no limit). A whole-request
            // timeout would abort a multi-gigabyte download on a slow connection even while
            // bytes are still arriving. Genuine stalls are caught by socketTimeoutMillis.
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        // Redirects are followed by default; model CDNs commonly 302 to a storage host.
        expectSuccess = false // status handling lives in ResumePolicy
    }

    @Provides
    @Singleton
    fun provideModelDownloader(
        @DownloadHttpClient httpClient: HttpClient,
        @Dispatcher(PamDispatcher.IO) ioDispatcher: CoroutineDispatcher,
    ): ModelDownloader = ModelDownloader(httpClient, ioDispatcher)
}
