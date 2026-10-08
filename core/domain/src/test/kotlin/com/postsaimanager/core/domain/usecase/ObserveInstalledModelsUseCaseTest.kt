package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.InstalledModelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ObserveInstalledModelsUseCaseTest {

    private fun summary(id: String, supportsChat: Boolean) =
        InstalledModelSummary(id, id, "/$id", 1L, null, 4096, supportsChat = supportsChat)

    private val repository = object : InstalledModelsRepository {
        override val installed: Flow<List<InstalledModelSummary>> =
            MutableStateFlow(listOf(summary("chat", true), summary("reading-only", false)))
        override val activeModelId: Flow<String?> = MutableStateFlow("chat")
        override suspend fun setActive(modelId: String) = Unit
    }

    @Test
    @DisplayName("the chat model picker lists only the models that can chat")
    fun `only chat models are listed`() = runTest {
        val state = ObserveInstalledModelsUseCase(repository)().first()

        assertThat(state.models.map { it.id }).containsExactly("chat")
        assertThat(state.activeModelId).isEqualTo("chat")
    }
}
