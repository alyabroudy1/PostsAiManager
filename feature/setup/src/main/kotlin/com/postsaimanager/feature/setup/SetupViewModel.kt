package com.postsaimanager.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.setup.ConnectionMeter
import com.postsaimanager.core.domain.setup.ModelSetupGateway
import com.postsaimanager.core.domain.setup.SetModelSetupSkippedUseCase
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupPartStatus
import com.postsaimanager.core.model.SetupProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which page of the setup the user sees. */
enum class SetupStage {
    /** Reading what would be downloaded. */
    LOADING,

    /** What the app does, the privacy line, "Download the AI model" and "Skip for now". */
    INTRO,

    /** On mobile data (or an unknown connection): ask before spending it. */
    ASK_MOBILE_DATA,

    /** A progress bar per model, and Cancel. */
    DOWNLOADING,

    /** A download failed (or has no source): Retry. */
    FAILED,

    /** Both models are installed. */
    FINISHED,
}

data class SetupUiState(
    val offer: SetupOffer? = null,
    val progress: SetupProgress = SetupProgress.NOT_STARTED,
    val stage: SetupStage = SetupStage.LOADING,
    /** The chat model is installed but the search model failed: the user may go on, search by word still works. */
    val canContinueWithoutSearch: Boolean = false,
    /** Leave the screen for Home: setup finished or was skipped. Consumed by the screen once. */
    val exit: Boolean = false,
)

/** What the user did, as opposed to what the downloads report. */
private data class Choices(
    val started: Boolean = false,
    val askMobileData: Boolean = false,
    val sourceMissing: Boolean = false,
    val exit: Boolean = false,
)

/**
 * First-run setup: one action downloads the recommended chat model and the search model, with a progress bar each, Wi-Fi/mobile-data
 * handling, cancel and retry. "Skip for now" lets the user scan without AI. The downloads themselves are the existing machinery behind
 * [ModelSetupGateway]; this only decides what the screen shows.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val gateway: ModelSetupGateway,
    private val connection: ConnectionMeter,
    private val setSkipped: SetModelSetupSkippedUseCase,
) : ViewModel() {

    private val offer = MutableStateFlow<SetupOffer?>(null)
    private val progress = MutableStateFlow(SetupProgress.NOT_STARTED)
    private val choices = MutableStateFlow(Choices())

    val uiState: StateFlow<SetupUiState> =
        combine(offer, progress, choices) { offer, progress, choices ->
            SetupUiState(
                offer = offer,
                progress = progress,
                stage = stageOf(offer, progress, choices),
                canContinueWithoutSearch = progress.chat is SetupPartStatus.Done && progress.search is SetupPartStatus.Failed,
                exit = choices.exit,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState())

    init {
        viewModelScope.launch { offer.value = gateway.offer() }
        viewModelScope.launch {
            gateway.progress.collect { current ->
                progress.value = current
                if (current.isComplete) finish()
            }
        }
    }

    /** "Download the AI model" and "Retry": asks first when the connection might cost money. */
    fun download() {
        if (offer.value?.canInstallChatModel == false) return
        if (connection.isMetered()) {
            choices.update { it.copy(askMobileData = true, sourceMissing = false) }
        } else {
            begin(allowMetered = false)
        }
    }

    fun useMobileData() = begin(allowMetered = true)

    /** The user would rather wait: the downloads queue until Wi-Fi is there. */
    fun waitForWifi() = begin(allowMetered = false)

    /** Back from the mobile-data question to the page before it. */
    fun dismissMobileDataQuestion() {
        choices.update { it.copy(askMobileData = false) }
    }

    fun cancel() {
        gateway.cancel()
        choices.update { it.copy(started = false, askMobileData = false) }
    }

    /** Scan without AI for now; Home shows the "AI model not installed" banner until a model exists. */
    fun skip() {
        viewModelScope.launch {
            setSkipped(true)
            choices.update { it.copy(exit = true) }
        }
    }

    /** The chat model is installed; leave without the search model. */
    fun continueWithoutSearch() {
        if (progress.value.chat is SetupPartStatus.Done) finish()
    }

    private fun begin(allowMetered: Boolean) {
        choices.update { it.copy(started = true, askMobileData = false, sourceMissing = false) }
        viewModelScope.launch {
            if (!gateway.start(allowMetered)) {
                choices.update { it.copy(started = false, sourceMissing = true) }
            }
        }
    }

    private fun finish() {
        if (choices.value.exit) return
        choices.update { it.copy(exit = true) }
        viewModelScope.launch { setSkipped(false) }
    }

    internal companion object {
        /** Pure, so every combination is testable. See [SetupStage]. */
        internal fun stageOf(offer: SetupOffer?, progress: SetupProgress): SetupStage =
            stageOf(offer, progress, Choices())

        private fun stageOf(offer: SetupOffer?, progress: SetupProgress, choices: Choices): SetupStage {
            val active = listOf(progress.chat, progress.search)
                .any { it is SetupPartStatus.Downloading || it is SetupPartStatus.Waiting }
            return when {
                offer == null -> SetupStage.LOADING
                progress.isComplete -> SetupStage.FINISHED
                choices.askMobileData -> SetupStage.ASK_MOBILE_DATA
                progress.anyFailed || choices.sourceMissing -> SetupStage.FAILED
                active || choices.started -> SetupStage.DOWNLOADING
                else -> SetupStage.INTRO
            }
        }
    }
}
