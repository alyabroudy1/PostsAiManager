package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderStyle
import com.postsaimanager.core.domain.extraction.gemma.ReaderStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The reader style in memory: JSON until a test chooses Questions, like the real one. */
class FakeGemmaReaderStyle(initial: ReaderStyle = ReaderStyle.JSON) : GemmaReaderStyle {

    private val state = MutableStateFlow(initial)

    override val style: Flow<ReaderStyle> = state.asStateFlow()

    override suspend fun current(): ReaderStyle = state.value

    override suspend fun set(style: ReaderStyle) {
        state.value = style
    }
}
