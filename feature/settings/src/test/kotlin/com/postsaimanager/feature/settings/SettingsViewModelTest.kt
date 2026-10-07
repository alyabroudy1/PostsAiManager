package com.postsaimanager.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.core.testing.FakeMonotonicClock
import com.postsaimanager.core.domain.applock.DeviceAuthAvailability
import com.postsaimanager.core.domain.applock.DeviceAuthPurpose
import com.postsaimanager.core.domain.applock.DeviceAuthResult
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeDeviceAuthenticator
import com.postsaimanager.core.testing.FakeInferenceSettingsRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.reminder.SetDeadlineRemindersUseCase
import com.postsaimanager.core.domain.skills.ReminderScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.Clock
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
    private val authenticator = FakeDeviceAuthenticator()

    private val reminders = mockk<ReminderScheduler>(relaxed = true)
    private val documents = mockk<ObserveDocumentListItemsUseCase> { every { this@mockk.invoke("") } returns flowOf(emptyList()) }

    private fun viewModel(userPreferencesRepository: FakeUserPreferencesRepository = repo) = SettingsViewModel(
        setDeadlineReminders = SetDeadlineRemindersUseCase(userPreferencesRepository, documents, reminders, Clock.systemUTC()),
        userPreferencesRepository = userPreferencesRepository,
        observeInferenceSettings = ObserveInferenceSettingsUseCase(models, inferenceSettingsRepo),
        updateInferenceSetting = UpdateInferenceSettingUseCase(inferenceSettingsRepo),
        resetInferenceSettings = ResetInferenceSettingsUseCase(inferenceSettingsRepo),
        deviceAuthenticator = authenticator,
        externalFlowGuard = AppLockState(FakeMonotonicClock()),
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
    fun `the deadline reminders switch cancels the scheduled reminders when turned off and reschedules when turned on`() = runTest {
        val vm = viewModel()

        vm.setNotificationsEnabled(false)
        coVerify(exactly = 1) { reminders.cancelDeadlines() }
        assertThat(repo.current.notificationsEnabled).isFalse()

        vm.setNotificationsEnabled(true)
        coVerify(exactly = 2) { reminders.cancelDeadlines() }
        verify(exactly = 1) { documents.invoke("") }
        assertThat(repo.current.notificationsEnabled).isTrue()
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
    fun `updating older letters is on by default and can be turned off`() = runTest {
        assertThat(repo.current.updateOlderLettersAutomatically).isTrue()

        viewModel().setUpdateOlderLettersAutomatically(false)

        assertThat(repo.current.updateOlderLettersAutomatically).isFalse()
    }

    @Nested
    @DisplayName("app lock toggle")
    inner class AppLockToggle {

        @Test
        fun `is off by default`() {
            assertThat(repo.current.biometricEnabled).isFalse()
            assertThat(repo.current.appLockTimeoutMinutes).isEqualTo(1)
        }

        @Test
        fun `turning on authenticates first and then persists`() = runTest {
            val vm = viewModel()

            vm.setBiometricEnabled(true)

            assertThat(authenticator.prompts).containsExactly(DeviceAuthPurpose.ENABLE_APP_LOCK)
            assertThat(repo.current.biometricEnabled).isTrue()
            assertThat(vm.appLockNotice.value).isNull()
        }

        @Test
        fun `a cancelled prompt leaves the lock off without an error`() = runTest {
            authenticator.result = DeviceAuthResult.Cancelled
            val vm = viewModel()

            vm.setBiometricEnabled(true)

            assertThat(repo.current.biometricEnabled).isFalse()
            assertThat(vm.appLockNotice.value).isNull()
        }

        @Test
        fun `a failed prompt leaves the lock off and says so`() = runTest {
            authenticator.result = DeviceAuthResult.Failed
            val vm = viewModel()

            vm.setBiometricEnabled(true)

            assertThat(repo.current.biometricEnabled).isFalse()
            assertThat(vm.appLockNotice.value).isEqualTo(AppLockNotice.AuthenticationFailed)
        }

        @Test
        fun `nothing enrolled explains itself, never prompts, and leaves the lock off`() = runTest {
            authenticator.availability = DeviceAuthAvailability.NOT_ENROLLED
            val vm = viewModel()

            vm.setBiometricEnabled(true)

            assertThat(authenticator.prompts).isEmpty()
            assertThat(repo.current.biometricEnabled).isFalse()
            assertThat(vm.appLockNotice.value).isEqualTo(AppLockNotice.NotEnrolled)
        }

        @Test
        fun `no usable authenticator is reported separately from not enrolled`() = runTest {
            authenticator.availability = DeviceAuthAvailability.UNAVAILABLE
            val vm = viewModel()

            vm.setBiometricEnabled(true)

            assertThat(authenticator.prompts).isEmpty()
            assertThat(vm.appLockNotice.value).isEqualTo(AppLockNotice.Unavailable)
        }

        @Test
        fun `the notice can be dismissed`() = runTest {
            authenticator.availability = DeviceAuthAvailability.NOT_ENROLLED
            val vm = viewModel()
            vm.setBiometricEnabled(true)

            vm.dismissAppLockNotice()

            assertThat(vm.appLockNotice.value).isNull()
        }

        @Test
        fun `turning off needs no authentication`() = runTest {
            val repo = FakeUserPreferencesRepository(UserPreferences(biometricEnabled = true))
            val vm = viewModel(repo)

            vm.setBiometricEnabled(false)

            assertThat(authenticator.prompts).isEmpty()
            assertThat(repo.current.biometricEnabled).isFalse()
        }

        @Test
        fun `turning off works even when nothing is enrolled any more`() = runTest {
            authenticator.availability = DeviceAuthAvailability.NOT_ENROLLED
            val repo = FakeUserPreferencesRepository(UserPreferences(biometricEnabled = true))
            val vm = viewModel(repo)

            vm.setBiometricEnabled(false)

            assertThat(repo.current.biometricEnabled).isFalse()
            assertThat(vm.appLockNotice.value).isNull()
        }

        @Test
        fun `timeout accepts only the offered options`() = runTest {
            val vm = viewModel()

            vm.setAppLockTimeoutMinutes(15)
            assertThat(repo.current.appLockTimeoutMinutes).isEqualTo(15)

            vm.setAppLockTimeoutMinutes(0)
            assertThat(repo.current.appLockTimeoutMinutes).isEqualTo(0)

            vm.setAppLockTimeoutMinutes(7)
            assertThat(repo.current.appLockTimeoutMinutes).isEqualTo(0)
        }
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
