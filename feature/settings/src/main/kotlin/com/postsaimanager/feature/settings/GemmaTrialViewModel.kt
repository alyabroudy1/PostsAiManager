package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The debug switch of the "Gemma reads the letter" trial (off by default): while it is on, every new reading is Gemma's, and the usual
 * reading runs when Gemma cannot read. Settings shows the switch in a debug build only.
 */
@HiltViewModel
class GemmaTrialViewModel @Inject constructor(
    private val trial: GemmaReaderTrial,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = trial.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { trial.setEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
