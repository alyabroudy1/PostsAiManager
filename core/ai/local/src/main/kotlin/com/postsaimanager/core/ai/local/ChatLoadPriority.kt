package com.postsaimanager.core.ai.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one policy for a model load that would replace the resident model: **the user's chat has priority over a reading, and the
 * reading is queued again, never degraded.**
 *
 * A load that replaces the resident model (a different model, or a changed config) frees the resident handle at once, so while a
 * reading's native call is in flight it would be cut from under the reader. Such a load therefore waits for the engine call that is
 * running (the call's own end is the only polite point to take the model: a generation cannot be interrupted without losing its
 * work) and runs holding the engine, so no other call starts in between. The reading's *next* step then finds the engine taken, or
 * a chat active ([com.postsaimanager.core.domain.ai.ChatActivityGate]), and goes back to the queue (`ReaderRetry`) instead of falling
 * back to a smaller reader.
 *
 * A load that changes nothing (the common case: the chat sends a message on the resident model) never waits, so it never queues
 * behind a reading that does not need the model replaced.
 *
 * The mutex is the call mutex of [InferenceConnection]. Loads that recover from a crash run inside an engine call that already holds
 * it, and go straight to the [ModelLoadCoordinator]; only the engines' public `load` goes through here.
 */
internal class ChatLoadPriority(private val engineMutex: Mutex) {

    /** Runs [load], first waiting for the running engine call when [replacesResident]. */
    suspend fun <T> load(replacesResident: Boolean, load: suspend () -> T): T =
        if (replacesResident) engineMutex.withLock { load() } else load()
}
