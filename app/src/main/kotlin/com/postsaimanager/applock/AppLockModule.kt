package com.postsaimanager.applock

import android.os.SystemClock
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.core.domain.applock.DeviceAuthenticator
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.MonotonicClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppLockBindings {

    /** The same instance MainActivity attaches itself to — one authenticator per process. */
    @Binds
    abstract fun bindDeviceAuthenticator(impl: BiometricDeviceAuthenticator): DeviceAuthenticator

    /** Features get the port; the owner of the lock state answers it. */
    @Binds
    abstract fun bindExternalFlowGuard(impl: AppLockState): ExternalFlowGuard

    companion object {
        @Provides
        @Singleton
        fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
    }
}
