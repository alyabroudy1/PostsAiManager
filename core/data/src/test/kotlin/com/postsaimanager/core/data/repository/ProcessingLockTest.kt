package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingLockTest {

    @Test
    @DisplayName("a reading a person waits for runs before background re-reads that queued earlier")
    fun `foreground goes first`() = runTest {
        val lock = ProcessingLock()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        launch { lock.withLock(background = true) { order += "running"; release.await() } }
        advanceUntilIdle()
        launch { lock.withLock(background = true) { order += "bg1" } }
        launch { lock.withLock(background = true) { order += "bg2" } }
        advanceUntilIdle()
        launch { lock.withLock { order += "user" } }
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(order.take(2)).containsExactly("running", "user").inOrder()
        assertThat(order.drop(2)).containsExactly("bg1", "bg2")
    }

    @Test
    @DisplayName("a foreground run that is cancelled while waiting does not hold the background back")
    fun `cancelled foreground`() = runTest {
        val lock = ProcessingLock()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        launch { lock.withLock { release.await() } }
        advanceUntilIdle()
        val waiting = launch { lock.withLock { order += "never" } }
        launch { lock.withLock(background = true) { order += "bg" } }
        advanceUntilIdle()
        waiting.cancel()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(order).containsExactly("bg")
    }

    @Test
    @DisplayName("the lock is released when the block throws")
    fun `released on failure`() = runTest {
        val lock = ProcessingLock()
        runCatching { lock.withLock { error("boom") } }
        var ran = false
        lock.withLock(background = true) { ran = true }
        assertThat(ran).isTrue()
    }
}
