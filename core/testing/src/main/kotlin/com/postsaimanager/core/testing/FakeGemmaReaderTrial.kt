package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderTrial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The reader switch in memory: Gemma (on) until a test chooses the old reader, like the real one. */
class FakeGemmaReaderTrial(enabled: Boolean = true) : GemmaReaderTrial {

    private val state = MutableStateFlow(enabled)

    override val enabled: Flow<Boolean> = state.asStateFlow()

    private val requested = mutableSetOf<String>()

    override suspend fun isEnabled(): Boolean = state.value

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
    }

    override fun requestOnce(documentId: String) {
        requested += documentId
    }

    override fun takeRequest(documentId: String): Boolean = requested.remove(documentId)

    /** Whether a request for [documentId] is still waiting to be spent. */
    fun isRequested(documentId: String): Boolean = documentId in requested
}
