package com.postsaimanager.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The one lock every reading goes through (the model reads one letter at a time), with two priorities instead of a first-come queue.
 *
 * A reading a person is waiting for (a scan, an import, "Read again", the second stage that follows them) takes the lock before any
 * background re-read of an older extractor version, however late it arrives: a background run only takes the lock while no foreground
 * one is waiting, and a background run that was waiting yields when one arrives. A run already holding the lock is never interrupted,
 * so the longest a person waits is the one reading in progress.
 */
class ProcessingLock {

    private data class State(val locked: Boolean = false, val foregroundWaiting: Int = 0)

    private val state = MutableStateFlow(State())

    /** Runs [block] holding the lock; a [background] run waits for every foreground one. */
    suspend fun <T> withLock(background: Boolean = false, block: suspend () -> T): T {
        acquire(background)
        try {
            return block()
        } finally {
            state.update { it.copy(locked = false) }
        }
    }

    private suspend fun acquire(background: Boolean) {
        if (!background) state.update { it.copy(foregroundWaiting = it.foregroundWaiting + 1) }
        try {
            while (true) {
                val seen = state.first { !it.locked && (!background || it.foregroundWaiting == 0) }
                val taken = if (background) seen.copy(locked = true) else seen.copy(locked = true, foregroundWaiting = seen.foregroundWaiting - 1)
                if (state.compareAndSet(seen, taken)) return
            }
        } catch (e: CancellationException) {
            if (!background) state.update { it.copy(foregroundWaiting = it.foregroundWaiting - 1) }
            throw e
        }
    }
}
