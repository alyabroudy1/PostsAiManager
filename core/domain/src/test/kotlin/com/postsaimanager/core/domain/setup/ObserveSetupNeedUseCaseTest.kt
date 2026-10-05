package com.postsaimanager.core.domain.setup

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.SetupNeed
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** First-run routing: setup is owed until a chat model exists, and "Skip for now" turns it into a banner. */
class ObserveSetupNeedUseCaseTest {

    private class FakeInstalledModels : InstalledModelsRepository {
        val models = MutableStateFlow<List<InstalledModelSummary>>(emptyList())
        override val installed: Flow<List<InstalledModelSummary>> = models
        override val activeModelId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setActive(modelId: String) = Unit
    }

    private val model = InstalledModelSummary("m", "Model", "/m.gguf", 1L, "Q4_K_M", 4096)

    @Test
    fun `a fresh install with no model needs the setup`() = runTest {
        val need = ObserveSetupNeedUseCase(FakeInstalledModels(), FakeUserPreferencesRepository())
        need().test { assertThat(awaitItem()).isEqualTo(SetupNeed.REQUIRED) }
    }

    @Test
    fun `skipping with no model is a banner, not the setup`() = runTest {
        val prefs = FakeUserPreferencesRepository(UserPreferences(modelSetupSkipped = true))
        ObserveSetupNeedUseCase(FakeInstalledModels(), prefs)().test {
            assertThat(awaitItem()).isEqualTo(SetupNeed.SKIPPED)
        }
    }

    @Test
    fun `an installed model needs nothing, skipped or not`() = runTest {
        val models = FakeInstalledModels().apply { this.models.value = listOf(model) }
        ObserveSetupNeedUseCase(models, FakeUserPreferencesRepository())().test {
            assertThat(awaitItem()).isEqualTo(SetupNeed.NOT_NEEDED)
        }
        val skipped = FakeUserPreferencesRepository(UserPreferences(modelSetupSkipped = true))
        ObserveSetupNeedUseCase(models, skipped)().test {
            assertThat(awaitItem()).isEqualTo(SetupNeed.NOT_NEEDED)
        }
    }

    @Test
    fun `the banner goes away when a model gets installed, and comes back when it is removed`() = runTest {
        val models = FakeInstalledModels()
        val prefs = FakeUserPreferencesRepository(UserPreferences(modelSetupSkipped = true))
        ObserveSetupNeedUseCase(models, prefs)().test {
            assertThat(awaitItem()).isEqualTo(SetupNeed.SKIPPED)
            models.models.value = listOf(model)
            assertThat(awaitItem()).isEqualTo(SetupNeed.NOT_NEEDED)
            models.models.value = emptyList()
            assertThat(awaitItem()).isEqualTo(SetupNeed.SKIPPED)
        }
    }

    @Test
    fun `the skip choice is stored and cleared`() = runTest {
        val prefs = FakeUserPreferencesRepository()
        val set = SetModelSetupSkippedUseCase(prefs)
        set(true)
        assertThat(prefs.current.modelSetupSkipped).isTrue()
        set(false)
        assertThat(prefs.current.modelSetupSkipped).isFalse()
    }
}
