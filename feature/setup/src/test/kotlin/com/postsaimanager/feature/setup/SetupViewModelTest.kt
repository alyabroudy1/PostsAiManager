package com.postsaimanager.feature.setup

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.setup.ConnectionMeter
import com.postsaimanager.core.domain.setup.ModelSetupGateway
import com.postsaimanager.core.domain.setup.SetModelSetupSkippedUseCase
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupPartStatus
import com.postsaimanager.core.model.SetupProgress
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.MainDispatcherExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** The first-run setup screen's states: intro, the mobile-data question, progress, failure and retry, skip, and finishing. */
@ExtendWith(MainDispatcherExtension::class)
class SetupViewModelTest {

    private class FakeGateway(var offer: SetupOffer) : ModelSetupGateway {
        val state = MutableStateFlow(SetupProgress.NOT_STARTED)
        val starts = mutableListOf<Boolean>()
        var cancels = 0
        var hasSource = true

        override suspend fun offer() = offer
        override val progress: Flow<SetupProgress> = state
        override suspend fun start(allowMetered: Boolean): Boolean {
            starts += allowMetered
            return hasSource
        }
        override fun cancel() {
            cancels++
        }
    }

    private val offer = SetupOffer("Qwen3.5 0.8B", 532_517_120L, 270_000_000L, canInstallChatModel = true)
    private val gateway = FakeGateway(offer)
    private val prefs = FakeUserPreferencesRepository()
    private var metered = false

    private fun viewModel() = SetupViewModel(gateway, ConnectionMeter { metered }, SetModelSetupSkippedUseCase(prefs))

    private fun downloading(done: Long = 10L) = SetupPartStatus.Downloading(done, 100L)

    @Test
    fun `a fresh start shows the intro with the offer`() = runTest {
        viewModel().uiState.test {
            val state = expectMostRecentItem()
            assertThat(state.stage).isEqualTo(SetupStage.INTRO)
            assertThat(state.offer).isEqualTo(offer)
            assertThat(state.exit).isFalse()
        }
    }

    @Test
    fun `a phone that cannot run the model cannot start a download`() = runTest {
        gateway.offer = offer.copy(canInstallChatModel = false)
        val vm = viewModel()
        vm.uiState.test {
            assertThat(expectMostRecentItem().offer!!.canInstallChatModel).isFalse()
            vm.download()
            assertThat(gateway.starts).isEmpty()
        }
    }

    @Test
    fun `on an unmetered network the download starts without asking`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            assertThat(gateway.starts).containsExactly(false)
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.DOWNLOADING)
        }
    }

    @Test
    fun `on a metered network it asks first, and mobile data starts a download that may use it`() = runTest {
        metered = true
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.ASK_MOBILE_DATA)
            assertThat(gateway.starts).isEmpty()

            vm.useMobileData()
            assertThat(gateway.starts).containsExactly(true)
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.DOWNLOADING)
        }
    }

    @Test
    fun `waiting for Wi-Fi starts a download that stays off mobile data`() = runTest {
        metered = true
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            vm.waitForWifi()
            assertThat(gateway.starts).containsExactly(false)
            gateway.state.value = SetupProgress(SetupPartStatus.Waiting, SetupPartStatus.Waiting)
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.DOWNLOADING)
        }
    }

    @Test
    fun `going back from the mobile data question returns to the intro`() = runTest {
        metered = true
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            vm.dismissMobileDataQuestion()
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.INTRO)
        }
    }

    @Test
    fun `progress per model is passed through`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            gateway.state.value = SetupProgress(downloading(40), SetupPartStatus.Waiting)
            val state = expectMostRecentItem()
            assertThat(state.stage).isEqualTo(SetupStage.DOWNLOADING)
            assertThat((state.progress.chat as SetupPartStatus.Downloading).fraction).isEqualTo(0.4f)
            assertThat(state.progress.search).isEqualTo(SetupPartStatus.Waiting)
        }
    }

    @Test
    fun `cancel stops both downloads and returns to the intro`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            gateway.state.value = SetupProgress(downloading(), downloading())
            expectMostRecentItem()

            vm.cancel()
            gateway.state.value = SetupProgress.NOT_STARTED
            assertThat(gateway.cancels).isEqualTo(1)
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.INTRO)
        }
    }

    @Test
    fun `a failed download is an error state and retry starts again`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            gateway.state.value = SetupProgress(SetupPartStatus.Failed, downloading())
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.FAILED)

            vm.download()
            assertThat(gateway.starts).containsExactly(false, false)
            gateway.state.value = SetupProgress(downloading(), downloading())
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.DOWNLOADING)
        }
    }

    @Test
    fun `a model with no download source is an error state`() = runTest {
        gateway.hasSource = false
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            assertThat(expectMostRecentItem().stage).isEqualTo(SetupStage.FAILED)
        }
    }

    @Test
    fun `finishing both downloads exits and clears the skip choice`() = runTest {
        prefs.setModelSetupSkipped(true)
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.download()
            gateway.state.value = SetupProgress(SetupPartStatus.Done, SetupPartStatus.Done)
            val state = expectMostRecentItem()
            assertThat(state.stage).isEqualTo(SetupStage.FINISHED)
            assertThat(state.exit).isTrue()
            assertThat(prefs.current.modelSetupSkipped).isFalse()
        }
    }

    @Test
    fun `skip is remembered and exits`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            vm.skip()
            assertThat(expectMostRecentItem().exit).isTrue()
            assertThat(prefs.current.modelSetupSkipped).isTrue()
        }
    }

    @Test
    fun `with the chat model ready and the search model failed the user may continue`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            gateway.state.value = SetupProgress(SetupPartStatus.Done, SetupPartStatus.Failed)
            val failed = expectMostRecentItem()
            assertThat(failed.stage).isEqualTo(SetupStage.FAILED)
            assertThat(failed.canContinueWithoutSearch).isTrue()

            vm.continueWithoutSearch()
            assertThat(expectMostRecentItem().exit).isTrue()
        }
    }

    @Test
    fun `continuing without search is refused while the chat model is missing`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            expectMostRecentItem()
            gateway.state.value = SetupProgress(SetupPartStatus.Failed, SetupPartStatus.Done)
            vm.continueWithoutSearch()
            assertThat(expectMostRecentItem().exit).isFalse()
        }
    }

    @Test
    fun `stage is loading until the offer is known`() {
        assertThat(SetupViewModel.stageOf(null, SetupProgress.NOT_STARTED)).isEqualTo(SetupStage.LOADING)
    }

    @Test
    fun `the user preferences default is not skipped`() {
        assertThat(UserPreferences().modelSetupSkipped).isFalse()
    }
}
