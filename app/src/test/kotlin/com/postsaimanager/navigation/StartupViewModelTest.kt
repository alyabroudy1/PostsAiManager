package com.postsaimanager.navigation

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.setup.ObserveSetupNeedUseCase
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.SetupNeed
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** First-run routing: a fresh install opens the setup, a phone with a model (or a skipped setup) opens Home. */
@ExtendWith(MainDispatcherExtension::class)
class StartupViewModelTest {

    private val installed = MutableStateFlow<List<InstalledModelSummary>>(emptyList())
    private val repository = object : InstalledModelsRepository {
        override val installed: Flow<List<InstalledModelSummary>> = this@StartupViewModelTest.installed
        override val activeModelId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setActive(modelId: String) = Unit
    }

    private fun route(prefs: UserPreferences = UserPreferences()): String? =
        StartupViewModel(ObserveSetupNeedUseCase(repository, FakeUserPreferencesRepository(prefs))).startRoute.value

    @Test
    fun `a fresh install with no model opens the setup`() {
        assertThat(route()).isEqualTo(StartRoutes.SETUP)
    }

    @Test
    fun `an installed model opens home`() {
        installed.value = listOf(InstalledModelSummary("m", "Model", "/m.gguf", 1L, "Q4_K_M", 4096))
        assertThat(route()).isEqualTo(StartRoutes.HOME)
    }

    @Test
    fun `a skipped setup opens home, where the banner waits`() {
        assertThat(route(UserPreferences(modelSetupSkipped = true))).isEqualTo(StartRoutes.HOME)
    }

    @Test
    fun `only a required setup routes to the setup`() {
        assertThat(StartRoutes.forNeed(SetupNeed.REQUIRED)).isEqualTo(StartRoutes.SETUP)
        assertThat(StartRoutes.forNeed(SetupNeed.SKIPPED)).isEqualTo(StartRoutes.HOME)
        assertThat(StartRoutes.forNeed(SetupNeed.NOT_NEEDED)).isEqualTo(StartRoutes.HOME)
    }

    @Test
    fun `the route is decided once, a later install does not move the user`() = runTest {
        val vm = StartupViewModel(ObserveSetupNeedUseCase(repository, FakeUserPreferencesRepository()))
        vm.startRoute.test {
            assertThat(expectMostRecentItem()).isEqualTo(StartRoutes.SETUP)
            installed.value = listOf(InstalledModelSummary("m", "Model", "/m.gguf", 1L, "Q4_K_M", 4096))
            expectNoEvents()
        }
    }
}
