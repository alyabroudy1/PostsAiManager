package com.postsaimanager

import com.postsaimanager.core.model.FormFillingFlag
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Feature flags, bound from the build type: form filling is on in debug builds and off in release builds. */
@Module
@InstallIn(SingletonComponent::class)
object FeatureFlagsModule {

    @Provides
    @Singleton
    fun provideFormFillingFlag(): FormFillingFlag = formFillingFlagFor(BuildConfig.DEBUG)
}

/** The form-filling flag for a build type; split out so both values are testable on the JVM. */
internal fun formFillingFlagFor(debugBuild: Boolean): FormFillingFlag = if (debugBuild) FormFillingFlag.ON else FormFillingFlag.OFF
