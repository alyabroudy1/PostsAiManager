package com.postsaimanager.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [SettingsViewModel].
 *
 * Settings are the one screen whose state is *only* meaningful if it round-trips to
 * storage, so these assert the write reached the repository rather than just that the
 * ViewModel accepted the call.
 */
@ExtendWith(MainDispatcherExtension::class)
class SettingsViewModelTest {

    private val repo = FakeUserPreferencesRepository()
    private val models = FakeActiveModelProvider()
    private val inferenceSettingsRepo = FakeInferenceSettingsRepository()

    private fun viewModel(userPreferencesRepository: FakeUserPreferencesRepository = repo) = SettingsViewModel(
        userPreferencesRepository = userPreferencesRepository,
        observeInferenceSettings = ObserveInferenceSettingsUseCase(models, inferenceSettingsRepo),
        updateInferenceSetting = UpdateInferenceSettingUseCase(inferenceSettingsRepo),
        resetInferenceSettings = ResetInferenceSettingsUseCase(inferenceSettingsRepo),
    )

    @Test
    fun `starts with default preferences`() = runTest {
        viewModel().preferences.test {
            assertThat(awaitItem()).isEqualTo(UserPreferences())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reflects preferences already stored`() = runTest {
        val repo = FakeUserPreferencesRepository(
            UserPreferences(theme = AppTheme.DARK, defaultLanguage = "ar"),
        )

        viewModel(repo).preferences.test {
            val emitted = awaitItem()
            assertThat(emitted.theme).isEqualTo(AppTheme.DARK)
            assertThat(emitted.defaultLanguage).isEqualTo("ar")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setTheme persists and re-emits`() = runTest {
        val vm = viewModel()

        vm.preferences.test {
            skipItems(1)
            vm.setTheme(AppTheme.DARK)
            assertThat(awaitItem().theme).isEqualTo(AppTheme.DARK)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.current.theme).isEqualTo(AppTheme.DARK)
    }

    @Test
    fun `every toggle round-trips to the repository`() = runTest {
        val vm = viewModel()

        vm.setAutoProcess(false)
        vm.setNotificationsEnabled(false)
        vm.setBiometricEnabled(true)
        vm.setDefaultLanguage("de")

        assertThat(repo.current.autoProcessAfterScan).isFalse()
        assertThat(repo.current.notificationsEnabled).isFalse()
        assertThat(repo.current.biometricEnabled).isTrue()
        assertThat(repo.current.defaultLanguage).isEqualTo("de")
    }

    @Test
    @DisplayName("LIMITATION: a failed write is swallowed — the UI is never told")
    fun `repository failure produces no error signal`() = runTest {
        // Every setter is `viewModelScope.launch { repo.setX(...) }` with the PamResult
        // discarded. If the write fails the toggle silently reverts on the next emission
        // and the user sees no explanation.
        //
        // This is exactly the pattern Phase 6.5 (ErrorPresentation) exists to remove;
        // pinned here so the fix has a test waiting for it.
        repo.failWith = PamError.DatabaseError(IllegalStateException("disk full"))
        val vm = viewModel()

        vm.setTheme(AppTheme.DARK)

        assertThat(repo.current.theme).isEqualTo(AppTheme.SYSTEM) // write did not land
        // …and the ViewModel exposes no way for the screen to discover that.
    }
}
