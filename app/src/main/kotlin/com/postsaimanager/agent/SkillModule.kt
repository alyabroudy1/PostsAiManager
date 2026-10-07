package com.postsaimanager.agent

import android.content.Context
import com.postsaimanager.core.ai.litert.skills.AssetSkillCatalog
import com.postsaimanager.core.domain.skills.SkillCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The bundled skills of the main process; the domain only knows the [SkillCatalog] port. */
@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    @Provides
    @Singleton
    fun provideSkillCatalog(@ApplicationContext context: Context): SkillCatalog = AssetSkillCatalog(context)
}
