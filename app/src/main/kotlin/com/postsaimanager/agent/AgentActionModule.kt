package com.postsaimanager.agent

import com.postsaimanager.core.domain.skills.AgentActionExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The executor of agent actions lives in the app (it opens other apps); the domain only knows the port. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentActionModule {

    @Binds
    abstract fun bindAgentActionExecutor(impl: AndroidAgentActionExecutor): AgentActionExecutor
}
