package com.postsaimanager.core.ai.local

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.ai.ReadingModel
import com.postsaimanager.core.model.InferenceConfig

/**
 * Makes sure a reading run scores on the reader model and on nothing else.
 *
 * Only one model is resident at a time, and a chat-model load can replace the reader between two steps of a document (it did on
 * the phone: Qwen was replaced mid-document and the reading went on scoring on the resident Gemma). Before a prompt session is
 * opened, this checks that the resident model is the [ReadingModel] the run says it reads with and, when it is not, loads the
 * reader. A chat that is active is never evicted from under the user: [open] waits for it to be idle, as background work does
 * through [ChatActivityGate]; [reopen] (inside the engine lock, where waiting would block the very chat it waits for) refuses
 * instead, and the reading asks to be run again later.
 *
 * Pure over its ports, so the decisions are unit-testable without a service.
 *
 * @param isResident whether the model at the given path is the one resident right now.
 * @param load loads a model (the coordinator's single-flight load).
 */
internal class ReaderModelGuard(
    private val chatGate: ChatActivityGate,
    private val isResident: (String) -> Boolean,
    private val load: suspend (String, InferenceConfig) -> PamResult<*>,
    private val maxWaitMs: Long = ChatActivityGate.DEFAULT_MAX_WAIT_MS,
) {

    /** Before the engine lock is taken: waits (bounded) for an active chat to be idle, then loads the reader if it is not resident. */
    suspend fun open(reading: ReadingModel?): PamResult<Unit> {
        if (reading == null || isResident(reading.path)) return PamResult.Success(Unit)
        if (!chatGate.awaitIdle(maxWaitMs)) return chatBusy()
        return loadReader(reading)
    }

    /** Inside the engine lock: the same check without waiting; a chat that is active is not replaced. */
    suspend fun reopen(reading: ReadingModel?): PamResult<Unit> {
        if (reading == null || isResident(reading.path)) return PamResult.Success(Unit)
        if (chatGate.isChatActive()) return chatBusy()
        return loadReader(reading)
    }

    private suspend fun loadReader(reading: ReadingModel): PamResult<Unit> =
        when (val loaded = load(reading.path, reading.config)) {
            is PamResult.Error -> loaded
            is PamResult.Success -> PamResult.Success(Unit)
        }

    private fun chatBusy(): PamResult<Unit> =
        PamResult.Error(PamError.ModelNotLoaded("A chat is using the model; the reader model will be loaded when it is idle."))
}
