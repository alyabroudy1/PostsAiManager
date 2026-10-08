package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chat's load against a reading that holds the engine: the chat has priority, the reading is waited for in its running call only
 * and then finds the engine taken (so it is queued again, not degraded to another reader).
 */
class ChatLoadPriorityTest {

    private val engine = Mutex()
    private val priority = ChatLoadPriority(engine)

    @Test
    @DisplayName("a load that replaces the resident model waits for the reading's running call, never cuts it")
    fun `waits for the running call`() = runTest(StandardTestDispatcher()) {
        val readingEnds = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        // The reading's call: holds the engine until it ends (as a structured generation does).
        launch {
            engine.lock()
            events += "reading call started"
            readingEnds.await()
            events += "reading call ended"
            engine.unlock()
        }
        advanceUntilIdle()
        val chatLoad = async { priority.load(replacesResident = true) { events += "chat model loaded"; "loaded" } }

        advanceUntilIdle()
        assertThat(events).containsExactly("reading call started")
        assertThat(chatLoad.isCompleted).isFalse()

        readingEnds.complete(Unit)
        advanceUntilIdle()

        assertThat(chatLoad.await()).isEqualTo("loaded")
        assertThat(events).containsExactly("reading call started", "reading call ended", "chat model loaded").inOrder()
    }

    @Test
    @DisplayName("the reading's next step finds the engine taken by the chat load and is queued again (it cannot slip in between)")
    fun `the next reading step is refused`() = runTest(StandardTestDispatcher()) {
        val readingEnds = CompletableDeferred<Unit>()
        val loading = CompletableDeferred<Unit>()
        launch {
            engine.lock()
            readingEnds.await()
            engine.unlock()
        }
        advanceUntilIdle()
        val chatLoad = launch { priority.load(replacesResident = true) { loading.await() } }
        advanceUntilIdle()

        readingEnds.complete(Unit)
        advanceUntilIdle()

        // The chat's load now holds the engine; a reading's call takes it only when it is free (as `generateStructured` does).
        assertThat(engine.tryLock()).isFalse()

        loading.complete(Unit)
        advanceUntilIdle()
        chatLoad.join()
        assertThat(engine.tryLock()).isTrue()
    }

    @Test
    @DisplayName("a load that changes nothing never waits for a reading")
    fun `an unchanged load does not wait`() = runTest(StandardTestDispatcher()) {
        engine.lock()

        val result = priority.load(replacesResident = false) { "no-op" }

        assertThat(result).isEqualTo("no-op")
        assertThat(engine.isLocked).isTrue()
    }
}
