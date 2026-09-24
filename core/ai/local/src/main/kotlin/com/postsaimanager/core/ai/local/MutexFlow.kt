package com.postsaimanager.core.ai.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [source] so the whole of its collection — from the first suspension point through
 * completion, error, or the collector cancelling — runs under this [Mutex].
 *
 * ### Why not `.onStart { lock() }.onCompletion { unlock() }`
 *
 * That shape looks equivalent but is not: `onCompletion` runs even when the collector is
 * cancelled while still *suspended inside* `onStart`'s `lock()` call — i.e. before the lock was
 * ever actually acquired by this caller. `kotlinx.coroutines.sync.Mutex.unlock()` called with no
 * owner token releases whichever *other* caller currently holds the lock (or throws if nobody
 * does) — exactly the interleaving-on-the-native-context bug [RemoteAiEngine]'s `engineMutex`
 * exists to prevent: caller A cancelled mid-wait releases caller B's lock, and a third caller C
 * can now run concurrently with B on the shared native context.
 *
 * `withLock` ties acquisition and release to the same structured scope instead: a cancellation
 * before the lock is acquired never unlocks anything (there is nothing to unlock — the
 * suspended `lock()` call simply never returns), and a cancellation after it is acquired always
 * releases the lock this same call took, via `withLock`'s `finally`. See
 * `MutexFlowTest` for the regression test.
 */
internal fun <T> Mutex.serialised(source: Flow<T>): Flow<T> = flow { withLock { emitAll(source) } }
