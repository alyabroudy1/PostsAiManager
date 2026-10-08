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
 * The debug switch "Reader: Gemma (default) / Qwen scorer (old)": on (the default), every reading is Gemma's and the old reading runs only
 * when Gemma cannot read; off, the old Qwen scorer reads. Settings shows the switch in a debug build only.
 */
@HiltViewModel
class GemmaTrialViewModel @Inject constructor(
    private val trial: GemmaReaderTrial,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = trial.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { trial.setEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
