package com.postsaimanager.feature.profiles

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [ProfilesViewModel].
 *
 * First ViewModel suite in the project, and the first tests to run in a `feature` module —
 * both only possible because `pam.test-conventions` activated the JUnit platform there.
 *
 * **On observing `Loading`:** `uiState` is a `stateIn` flow whose initial value is
 * `Loading`. Under [kotlinx.coroutines.test.UnconfinedTestDispatcher] the upstream runs
 * eagerly on subscription, so by the time Turbine receives its first item the state has
 * already settled — `Loading` is never emitted *to a collector*. It is therefore asserted
 * through `StateFlow.value` before collection starts, which is also closer to what a
 * screen actually observes on first composition.
 */
@ExtendWith(MainDispatcherExtension::class)
class ProfilesViewModelTest {

    private val repo = FakeProfileRepository()

    private fun viewModel() = ProfilesViewModel(repo)

    @Test
    fun `initial state before any collection is Loading`() = runTest {
        assertThat(viewModel().uiState.value).isEqualTo(ProfilesUiState.Loading)
    }

    @Test
    fun `settles on Empty when there are no profiles`() = runTest {
        viewModel().uiState.test {
            assertThat(awaitItem()).isEqualTo(ProfilesUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `settles on Success with the seeded profiles`() = runTest {
        repo.seed(testProfile(id = "p1", name = "Jobcenter Berlin"))

        viewModel().uiState.test {
            val success = awaitItem() as ProfilesUiState.Success
            assertThat(success.profiles.map { it.id }).containsExactly("p1")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search narrows the result set`() = runTest {
        repo.seed(
            testProfile(id = "p1", name = "Jobcenter Berlin"),
            testProfile(id = "p2", name = "Deutsche Telekom"),
        )
        val vm = viewModel()

        vm.uiState.test {
            assertThat((awaitItem() as ProfilesUiState.Success).profiles).hasSize(2)

            vm.onSearchQueryChanged("Telekom")

            val filtered = awaitItem() as ProfilesUiState.Success
            assertThat(filtered.profiles.map { it.id }).containsExactly("p2")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search with no hits emits Empty rather than an error`() = runTest {
        repo.seed(testProfile(id = "p1", name = "Jobcenter Berlin"))
        val vm = viewModel()

        vm.uiState.test {
            skipItems(1) // settled Success
            vm.onSearchQueryChanged("nichts-passendes")
            assertThat(awaitItem()).isEqualTo(ProfilesUiState.Empty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank query restores the full list`() = runTest {
        repo.seed(
            testProfile(id = "p1", name = "Jobcenter Berlin"),
            testProfile(id = "p2", name = "Deutsche Telekom"),
        )
        val vm = viewModel()

        vm.uiState.test {
            skipItems(1)
            vm.onSearchQueryChanged("Telekom")
            assertThat((awaitItem() as ProfilesUiState.Success).profiles).hasSize(1)

            vm.onSearchQueryChanged("")
            assertThat((awaitItem() as ProfilesUiState.Success).profiles).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search query is exposed as state`() = runTest {
        val vm = viewModel()
        assertThat(vm.searchQuery.value).isEmpty()

        vm.onSearchQueryChanged("Jobcenter")

        assertThat(vm.searchQuery.value).isEqualTo("Jobcenter")
    }

    /**
     * Task 7.14.11c: nothing in the app called [ProfileRepository.deleteProfile], so the
     * tombstone-on-delete that stops a machine-created profile from silently reappearing after
     * its document is reprocessed was exercised only by tests, never by a real user action.
     * These pin the ViewModel side of the fix — the delete button itself.
     */
    @Nested
    @DisplayName("Deleting a profile")
    inner class Deleting {

        @Test
        @DisplayName("requesting a delete does not touch the repository")
        fun `no delete on first tap`() = runTest {
            // A stray or accidental tap on the delete icon must not destroy anything by
            // itself — only surface a confirmation. Without this, "delete" would be one tap
            // away from irreversible, on a screen with no undo.
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            val vm = viewModel()

            vm.requestDelete(profile)

            assertThat(vm.pendingDeletion.value).isEqualTo(profile)
            vm.uiState.test {
                assertThat((awaitItem() as ProfilesUiState.Success).profiles)
                    .containsExactly(profile)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        @DisplayName("confirming a pending delete removes the profile")
        fun `confirmDelete removes the profile that was requested`() = runTest {
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            val vm = viewModel()
            vm.requestDelete(profile)

            vm.confirmDelete()

            assertThat(vm.pendingDeletion.value).isNull()
            vm.uiState.test {
                assertThat(awaitItem()).isEqualTo(ProfilesUiState.Empty)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        @DisplayName("cancelling a pending delete leaves the profile untouched")
        fun `cancelDelete clears the confirmation without deleting anything`() = runTest {
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            val vm = viewModel()
            vm.requestDelete(profile)

            vm.cancelDelete()

            assertThat(vm.pendingDeletion.value).isNull()
            vm.uiState.test {
                assertThat((awaitItem() as ProfilesUiState.Success).profiles)
                    .containsExactly(profile)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        @DisplayName("confirming with nothing pending calls nothing")
        fun `confirmDelete without a prior request is a no-op`() = runTest {
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            val vm = viewModel()

            vm.confirmDelete()

            vm.uiState.test {
                assertThat((awaitItem() as ProfilesUiState.Success).profiles)
                    .containsExactly(profile)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        @DisplayName("a repository failure is surfaced, not swallowed")
        fun `a failed delete leaves the profile in place and reports a message`() = runTest {
            // Silently doing nothing here is exactly the failure this screen exists to
            // prevent: the confirmation dialog would close, the profile would reappear on
            // the next recomposition, and the user would have no idea why the app just
            // ignored them — the same "ignoring the user" symptom the tombstone mechanism
            // is meant to guard against, just from the opposite direction.
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            repo.failWith = PamError.DatabaseError()
            val vm = viewModel()
            vm.requestDelete(profile)

            vm.confirmDelete()

            assertThat(vm.message.value).isEqualTo(PamError.DatabaseError().userMessage)
            vm.uiState.test {
                assertThat((awaitItem() as ProfilesUiState.Success).profiles)
                    .containsExactly(profile)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        @DisplayName("consuming the message clears it so a snackbar cannot repeat")
        fun `consumeMessage resets the message to null`() = runTest {
            val profile = testProfile(id = "p1", name = "Jobcenter Berlin")
            repo.seed(profile)
            repo.failWith = PamError.DatabaseError()
            val vm = viewModel()
            vm.requestDelete(profile)
            vm.confirmDelete()
            assertThat(vm.message.value).isNotNull()

            vm.consumeMessage()

            assertThat(vm.message.value).isNull()
        }
    }
}
