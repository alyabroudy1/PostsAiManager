package com.postsaimanager.setup

import android.content.Context
import android.net.ConnectivityManager
import com.postsaimanager.core.domain.setup.ConnectionMeter
import com.postsaimanager.core.domain.setup.ModelSetupGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SetupBindings {

    @Binds
    abstract fun bindModelSetupGateway(impl: CatalogModelSetupGateway): ModelSetupGateway

    companion object {
        /** Mobile data and metered Wi-Fi both count: the question is "might this cost the user money?". */
        @Provides
        fun provideConnectionMeter(@ApplicationContext context: Context): ConnectionMeter = ConnectionMeter {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            // Unknown (no active network) counts as metered, so the download asks rather than assumes.
            manager?.isActiveNetworkMetered ?: true
        }
    }
}
