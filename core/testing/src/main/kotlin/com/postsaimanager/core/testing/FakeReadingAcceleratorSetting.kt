package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.ai.ReadingAcceleratorSetting
import com.postsaimanager.core.model.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The "Reading: CPU / GPU" setting in memory: CPU until a test chooses otherwise, like the real one. */
class FakeReadingAcceleratorSetting(initial: Accelerator = Accelerator.CPU) : ReadingAcceleratorSetting {

    private val state = MutableStateFlow(initial)

    override val accelerator: Flow<Accelerator> = state.asStateFlow()

    override suspend fun current(): Accelerator = state.value

    override suspend fun set(accelerator: Accelerator) {
        state.value = accelerator
    }
}
