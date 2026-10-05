package com.postsaimanager.feature.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.list.DueFieldsActionHint
import com.postsaimanager.core.domain.document.list.IdentityPartyNameResolver
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.setup.ObserveSetupNeedUseCase
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Home shows the same rows as the Documents list, the first ten of them. */
@ExtendWith(MainDispatcherExtension::class)
class HomeViewModelTest {

    private val repository = FakeDocumentRepository()
    private val clock = Clock.fixed(LocalDate.of(2026, 9, 30).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private val preferences = FakeUserPreferencesRepository()
    private val installedModels = MutableStateFlow<List<InstalledModelSummary>>(emptyList())
    private val installedRepository = object : InstalledModelsRepository {
        override val installed: Flow<List<InstalledModelSummary>> = installedModels
        override val activeModelId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setActive(modelId: String) = Unit
    }

    private fun viewModel() = HomeViewModel(
        observeDocumentListItems = ObserveDocumentListItemsUseCase(
            repository, IdentityPartyNameResolver(), DueFieldsActionHint(), clock,
        ),
        documentProcessor = FakeDocumentProcessor(),
        observeSetupNeed = ObserveSetupNeedUseCase(installedRepository, preferences),
    )

    @Test
    fun `no documents is Empty`() = runTest {
        viewModel().uiState.test {
            assertThat(expectMostRecentItem()).isEqualTo(HomeUiState.Empty)
        }
    }

    @Test
    fun `only the first ten rows are recent, the total counts all`() = runTest {
        repository.seed(*(1..12).map { testDocument(id = "d$it") }.toTypedArray())
        viewModel().uiState.test {
            val state = expectMostRecentItem() as HomeUiState.Success
            assertThat(state.recentDocuments).hasSize(10)
            assertThat(state.recentDocuments.first().id).isEqualTo("d1")
            assertThat(state.totalCount).isEqualTo(12)
        }
    }

    @Test
    fun `rows carry the status the use case derived`() = runTest {
        repository.seed(testDocument(id = "d1", status = DocumentStatus.FAILED))
        viewModel().uiState.test {
            val state = expectMostRecentItem() as HomeUiState.Success
            assertThat(state.recentDocuments.single().status).isEqualTo(DocumentListStatus.Failed)
        }
    }

    @Test
    fun `no banner on a fresh install, where the setup screen shows instead`() = runTest {
        viewModel().showModelBanner.test { assertThat(expectMostRecentItem()).isFalse() }
    }

    @Test
    fun `the banner shows after skipping the setup while no model exists`() = runTest {
        preferences.setModelSetupSkipped(true)
        viewModel().showModelBanner.test { assertThat(expectMostRecentItem()).isTrue() }
    }

    @Test
    fun `the banner goes away once a model is installed`() = runTest {
        preferences.setModelSetupSkipped(true)
        viewModel().showModelBanner.test {
            assertThat(expectMostRecentItem()).isTrue()
            installedModels.value = listOf(InstalledModelSummary("m", "Model", "/m.gguf", 1L, "Q4_K_M", 4096))
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `a failing read is an Error state`() = runTest {
        repository.throwOnObserve = IllegalStateException("db closed")
        viewModel().uiState.test {
            assertThat(expectMostRecentItem()).isEqualTo(HomeUiState.Error("db closed"))
        }
    }
}
