package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression test for [Mutex.serialised] — see its KDoc for the bug this exists to catch:
 * `.onStart { lock() }.onCompletion { unlock() }` releases whoever else holds the lock when
 * *this* caller is cancelled before ever acquiring it, letting a third caller run concurrently
 * with the native-context caller that should still be exclusive. [Mutex.serialised] must not
 * do that.
 */
class MutexFlowTest {

    @Test
    @DisplayName("a caller cancelled while waiting for the lock never releases another caller's lock")
    fun `cancelling a waiter does not unlock the current holder`() = runTest {
        val mutex = Mutex()
        val holderStarted = Channel<Unit>(1)
        val releaseHolder = Channel<Unit>(1)

        // Caller A: acquires the lock and holds it until told to release.
        val holderFlow: Flow<String> = flow {
            holderStarted.send(Unit)
            releaseHolder.receive()
            emit("A")
        }
        val holderJob = launch {
            mutex.serialised(holderFlow).toList()
        }
        holderStarted.receive()
        assertThat(mutex.isLocked).isTrue()

        // Caller B: queues behind A, waiting on `lock()` inside `serialised` — never reaches
        // its own flow body while A holds the lock.
        val waiterFlow: Flow<String> = flow { emit("B") }
        val waiterJob = launch {
            mutex.serialised(waiterFlow).toList()
        }
        yield()
        yield()

        // Cancel B while it is still suspended waiting for the lock — before it ever
        // acquired it. The bug this test pins: a naive onStart/onCompletion wrapper would
        // still run `onCompletion { unlock() }` for B here, releasing A's lock out from under
        // it even though B never held it.
        waiterJob.cancel()
        waiterJob.join()

        // A must still hold the lock — nothing has released it on A's behalf.
        assertThat(mutex.isLocked).isTrue()

        // A third caller queued now must still wait for A, not run concurrently with it.
        val thirdStarted = Channel<Unit>(1)
        val thirdFlow: Flow<String> = flow {
            thirdStarted.send(Unit)
            emit("C")
        }
        val thirdJob = async { mutex.serialised(thirdFlow).toList() }
        // Give the third caller every chance to (wrongly) start while A still holds the lock.
        yield()
        yield()
        assertThat(thirdStarted.tryReceive().isSuccess).isFalse()

        releaseHolder.send(Unit)
        holderJob.join()
        assertThat(thirdJob.await()).containsExactly("C")
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("the lock is released after normal completion")
    fun `lock released on completion`() = runTest {
        val mutex = Mutex()
        mutex.serialised(flow { emit("x") }).toList()
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("the lock is released when the wrapped flow throws")
    fun `lock released on failure`() = runTest {
        val mutex = Mutex()
        val boom = IllegalStateException("boom")
        val caught = runCatching {
            mutex.serialised(flow<String> { throw boom }).toList()
        }.exceptionOrNull()
        assertThat(caught).isEqualTo(boom)
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("the lock is released when the collector cancels mid-stream")
    fun `lock released on collector cancellation`() = runTest {
        val mutex = Mutex()
        val started = Channel<Unit>(1)
        val job = launch {
            mutex.serialised(
                flow {
                    started.send(Unit)
                    emit("first")
                    kotlinx.coroutines.awaitCancellation()
                },
            ).collect { }
        }
        started.receive()
        assertThat(mutex.isLocked).isTrue()
        job.cancel()
        job.join()
        assertThat(mutex.isLocked).isFalse()
    }
}
