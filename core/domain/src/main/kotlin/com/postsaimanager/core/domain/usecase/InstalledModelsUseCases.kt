package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.InstalledModelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** What the chat header/model sheet needs: every installed model, and which one is active. */
data class InstalledModelsUiState(
    val models: List<InstalledModelSummary>,
    val activeModelId: String?,
)

/** Streams installed models and the active one — see [InstalledModelsRepository]. */
class ObserveInstalledModelsUseCase @Inject constructor(
    private val repository: InstalledModelsRepository,
) {
    operator fun invoke(): Flow<InstalledModelsUiState> =
        combine(repository.installed, repository.activeModelId, ::InstalledModelsUiState)
}

/** Sets which installed model chats. The next message loads it — see [PreloadActiveModelUseCase]. */
class SelectActiveModelUseCase @Inject constructor(
    private val repository: InstalledModelsRepository,
) {
    suspend operator fun invoke(modelId: String) = repository.setActive(modelId)
}

/**
 * Loads the active chat model right away, rather than waiting for the next message.
 *
 * Selecting a different model in the chat header sheet should visibly move the header from
 * "Ready" to "Loading…" to "Ready" on the *new* model, not sit still until the user types.
 * [AiEngine.load] already no-ops or does the cheapest reload the scope calls for (see
 * `InferenceConfig.requiresReload` / `ModelLoadCoordinator`), so calling it eagerly here
 * costs nothing when nothing actually changed.
 */
class PreloadActiveModelUseCase @Inject constructor(
    private val engine: AiEngine,
    private val activeModelProvider: ActiveModelProvider,
) {
    suspend operator fun invoke() {
        val modelPath = activeModelProvider.activeModelPath() ?: return
        engine.load(modelPath, activeModelProvider.activeModelConfig())
    }
}
