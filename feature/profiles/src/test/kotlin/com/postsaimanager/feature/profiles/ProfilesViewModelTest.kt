package com.postsaimanager.feature.profiles

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
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
}
