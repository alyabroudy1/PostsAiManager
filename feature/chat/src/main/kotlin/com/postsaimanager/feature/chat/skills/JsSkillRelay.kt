package com.postsaimanager.feature.chat.skills

import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.skills.JsSkillExecutor
import com.postsaimanager.core.domain.skills.RelayJsSkillsUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the `run_js` calls of the chat model flowing to the offline sandbox for as long as the app runs: started once by the chat
 * (it is idempotent), because the engine's request flow is hot and a call published while nobody listens would wait for nothing.
 */
@Singleton
class JsSkillRelay @Inject constructor(
    private val engine: ChatEngine,
    private val executor: JsSkillExecutor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            RelayJsSkillsUseCase(engine.jsRequests, executor, engine::deliverJsResult).collect(scope)
        }
    }
}

/** The one sandbox the chat runs JS skills in. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChatSkillsModule {

    @Binds
    abstract fun bindJsSkillExecutor(impl: WebViewJsSkillExecutor): JsSkillExecutor
}
