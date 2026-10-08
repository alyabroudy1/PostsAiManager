package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.ai.ReadingAcceleratorSetting
import com.postsaimanager.core.domain.ai.SetReadingAcceleratorUseCase
import com.postsaimanager.core.model.Accelerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The debug switch "Reading: CPU / GPU": which accelerator the chat model runs on while it reads documents, apart from the chat's own
 * (CPU by default). Settings shows it in a debug build only.
 */
@HiltViewModel
class ReadingAcceleratorViewModel @Inject constructor(
    setting: ReadingAcceleratorSetting,
    private val setReading: SetReadingAcceleratorUseCase,
): ViewModel() {

    /** True when readings run on the GPU. */
    val onGpu: StateFlow<Boolean> = setting.accelerator
        .map { it == Accelerator.GPU }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun setOnGpu(onGpu: Boolean) {
        viewModelScope.launch { setReading(if (onGpu) Accelerator.GPU else Accelerator.CPU) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
